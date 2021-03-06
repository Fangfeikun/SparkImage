package com.fang.spark

import kafka.serializer.{DefaultDecoder, StringDecoder}
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.client.{Put, Scan}
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapred.TableOutputFormat
import org.apache.hadoop.hbase.mapreduce.TableInputFormat
import org.apache.hadoop.hbase.protobuf.ProtobufUtil
import org.apache.hadoop.hbase.util.{Base64, Bytes}
import org.apache.hadoop.mapred.JobConf
import org.apache.spark.SparkConf
import org.apache.spark.mllib.clustering.KMeansModel
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.{Milliseconds, StreamingContext}
import org.apache.spark.streaming.kafka.KafkaUtils
import org.opencv.core.Core

import scala.util.control.Breaks._

/**
  * Created by fang on 16-12-21.
  * 从Kafka获取图像数据,计算该图像的sift直方图,和HBase中的图像直方图对比,输出最相近的图像名
  * Spark会读取本地的Hadoop配置文件
  */
object KafkaImageConsumer {
  def main(args: Array[String]): Unit = {
    val sparkConf = ImagesUtil.loadSparkConf("KafkaImageProcess")

    //批次间隔800ms
    val ssc = new StreamingContext(sparkConf, Milliseconds(800))
    ssc.checkpoint("checkpoint")
    ssc.sparkContext.setLogLevel("WARN")
    //连接HBase参数配置
    val hbaseConf = ImagesUtil.loadHBaseConf()
    val tableName =ImagesUtil.imageTableName
    hbaseConf.set(TableInputFormat.INPUT_TABLE, tableName)

    val scan = new Scan()
    scan.addColumn(Bytes.toBytes("image"), Bytes.toBytes("histogram"))
    val proto = ProtobufUtil.toScan(scan)
    val ScanToString = Base64.encodeBytes(proto.toByteArray())
    hbaseConf.set(TableInputFormat.SCAN, ScanToString)

    //获取HBase中的图像直方图
    //TODO 是否需要过滤特征值比较少的图像?
    val hBaseRDD = ssc.sparkContext.newAPIHadoopRDD(hbaseConf, classOf[TableInputFormat],
      classOf[org.apache.hadoop.hbase.io.ImmutableBytesWritable],
      classOf[org.apache.hadoop.hbase.client.Result])

    //获取HBase中图像的特征直方图
    val histogramFromHBaseRDD = hBaseRDD.map {
      case (_, result) => {
        val key = Bytes.toString(result.getRow)
        val histogram = ImagesUtil.deserializeArray(result.getValue("image".getBytes, "histogram".getBytes))
        (key, histogram)
      }
    }

    //缓存图像特征直方图库
    //TODO 解决内存不足的情况
    histogramFromHBaseRDD
      //.repartition(30)
      .cache()
    println("============"+histogramFromHBaseRDD.count()+"===========")
    //加载kmeans模型
    val myKmeansModel = KMeansModel.load(ssc.sparkContext, ImagesUtil.kmeansModelPath)

    //从Kafka接收图像数据
    val topics = Set("image_topic")
    val kafkaParams = Map[String, String](
      "metadata.broker.list" -> "202.114.30.171:9092,202.114.30.172:9092,202.114.30.173:9092"
      //"serializer.class" -> "kafka.serializer.DefaultDecoder",
      //"key.serializer.class" -> "kafka.serializer.StringEncoder"
    )

    val imageFromKafkaDStream = KafkaUtils
      .createDirectStream[String, Array[Byte], StringDecoder, DefaultDecoder](ssc, kafkaParams, topics)
      .repartition(30)
      .cache()

    //计算kafkaStream中每个图像的特征直方图,返回图像名称和相应的特征直方图RDD
    val imageTupleDStream = imageFromKafkaDStream.map {
      imageTuple => {
        getReceiveImageHistogram(imageTuple,myKmeansModel)
      }
    }
   // imageTupleDStream.cache()
    //获取接受图像的名称和特征直方图
    val histogramFromKafkaDStream = imageTupleDStream.map(tuple => (tuple._1, tuple._4))

    //获取最相似的n张图片
    val topNSimilarImageDStream = histogramFromKafkaDStream.transform {
      imageHistogramRDD => {
        getNTopSimilarImage(imageHistogramRDD,histogramFromHBaseRDD)
      }
    }

    //保存查询到的相似图像名称
    topNSimilarImageDStream.foreachRDD {
      rdd => {
        saveSimilarImageName(rdd)
      }
    }
    //保存从kafka接受的图像到HBase中
//    imageTupleDStream.foreachRDD {
//      rdd => {
//        saveImagesFromKafka(rdd)
//      }
//    }
    //启动程序
    ssc.start()
    ssc.awaitTermination()
    ssc.stop()
  }


  /** 保存从kafka接受的图像数据
    * object not serializable (class: org.apache.hadoop.hbase.io.ImmutableBytesWritable
    * 调换foreachRDD 和map
    * @param rdd
    */
  def saveImagesFromKafka
  (rdd:RDD[(String, Array[Byte], Option[Array[Byte]], Array[Int])]){
    if (!rdd.isEmpty()) {
      val hConfig = HBaseConfiguration.create()
      val tableName = "imagesTest"
      hConfig.set("hbase.zookeeper.property.clientPort", "2181")
      hConfig.set("hbase.zookeeper.quorum", "fang-ubuntu,fei-ubuntu,kun-ubuntu")
      val jobConf = new JobConf(hConfig)
      jobConf.setOutputFormat(classOf[TableOutputFormat])
      jobConf.set(TableOutputFormat.OUTPUT_TABLE, tableName)
      //保存从kafka接受的图片数据
      rdd.map {
        tuple => {
          val put: Put = new Put(Bytes.toBytes(tuple._1))
          put.addColumn(Bytes.toBytes("image"), Bytes.toBytes("binary"), tuple._2)
          //TODO 改了序列化
          put.addColumn(Bytes.toBytes("image"), Bytes.toBytes("histogram"), ImagesUtil.ObjectToBytes(tuple._4))
//          val sift = tuple._3
//          if (!sift.isEmpty) {
//            put.addColumn(Bytes.toBytes("image"), Bytes.toBytes("sift"), sift.get)
//          }
          val harris = tuple._3
          if (!harris.isEmpty) {
            put.addColumn(Bytes.toBytes("image"), Bytes.toBytes("harris"), harris.get)
          }
          (new ImmutableBytesWritable, put)
        }
      }.saveAsHadoopDataset(jobConf)
      println("================保存接收的图像完成==================")
    }
  }

  /**
    *
    * @param imageTuple
    * @param myKmeansModel
    * @return
    */
  def getReceiveImageHistogram(imageTuple:(String,Array[Byte]),myKmeansModel:KMeansModel)
  :(String, Array[Byte], Option[Array[Byte]], Array[Int]) ={
    //加载Opencv库,在每个分区都需加载
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    val imageBytes = imageTuple._2
    //val sift = ImagesUtil.getImageSift(imageBytes)
    val sift = ImagesUtil.getImageHARRIS(imageBytes)
    val histogramArray = new Array[Int](myKmeansModel.clusterCenters.length)
    if (!sift.isEmpty) {
      val siftByteArray = sift.get
      val siftFloatArray = ImagesUtil.byteArrToFloatArr(siftByteArray)
      val size = siftFloatArray.length / 128
      for (i <- 0 to size - 1) {
        val xs: Array[Float] = new Array[Float](128)
        for (j <- 0 to 127) {
          xs(j) = siftFloatArray(i * 128 + j)
        }
        val predictedClusterIndex: Int = myKmeansModel.predict(Vectors.dense(xs.map(i => i.toDouble)))
        histogramArray(predictedClusterIndex) = histogramArray(predictedClusterIndex) + 1
      }
    }
    //计算harris
 //   val harris = ImagesUtil.getImageHARRIS(imageBytes)
//    if (!harris.isEmpty) {
//      val harrisByteArray = sift.get
//      val harrisFloatArray = ImagesUtil.byteArrToFloatArr(harrisByteArray)
//      val size = harrisFloatArray.length / 128
//      for (i <- 0 to size - 1) {
//        val xs: Array[Float] = new Array[Float](128)
//        for (j <- 0 to 127) {
//          xs(j) = harrisFloatArray(i * 128 + j)
//        }
//        val predictedClusterIndex: Int = myKmeansModel.predict(Vectors.dense(xs.map(i => i.toDouble)))
//        histogramArray(predictedClusterIndex) = histogramArray(predictedClusterIndex) + 1
//      }
//    }
   (imageTuple._1, imageBytes,sift, histogramArray)
  }

  /**
    *
    * @param imageHistogramRDD
    * @param histogramFromHBaseRDD
    * @return
    */
  def getNTopSimilarImage(imageHistogramRDD:RDD[(String,Array[Int])],histogramFromHBaseRDD:RDD[(String,Array[Int])]):RDD[(String,Array[(Int,String)])] ={
    //将从kafka接收的图像RDD与数据库中的图像RDD求笛卡尔积
    //返回kafka中的图像名称,和HBase数据库中图像的直方图距离sum及数据中的图像名称
    val cartesianRDD = imageHistogramRDD.cartesian(histogramFromHBaseRDD)
    val computeHistogramSum = cartesianRDD.map {
      tuple => {
        val imageHistogram = tuple._1
        val histogramHBase = tuple._2
        var sum = 0
        for (i <- 0 to imageHistogram._2.length - 1) {
          val sub = imageHistogram._2(i) - histogramHBase._2(i)
          sum = sum + sub * sub
        }
        (imageHistogram._1, (sum, histogramHBase._1))
      }
    }
    //根据接收的图像名称分组
    val groupRDD = computeHistogramSum.groupByKey()
    //对每个分组中求最相近的10张图片
    val matchImageRDD = groupRDD.map {
      tuple => {
        val top10 = Array[(Int, String)](
          //出现数组没有满的情况,就是相似图像出现了,名称为0的图像,程序抛出异常
          (Int.MaxValue, "0"), (Int.MaxValue, "0"),
          (Int.MaxValue, "0"), (Int.MaxValue, "0"),
          (Int.MaxValue, "0"), (Int.MaxValue, "0"),
          (Int.MaxValue, "0"), (Int.MaxValue, "0"),
          (Int.MaxValue, "0"), (Int.MaxValue, "0"))
        val imageName = tuple._1
        val similarImageIter = tuple._2
        for (similarImage <- similarImageIter) {
          breakable {
            for (i <- 0 until 10) {
              if (top10(i)._1 == Int.MaxValue) {
                top10(i) = similarImage
                break
              } else if (similarImage._1 < top10(i)._1) {
                var j = 2
                while (j > i) {
                  top10(j) = top10(j - 1)
                  j = j - 1
                }
                top10(i) = similarImage
                break
              }
            }
          }
        }
        (imageName, top10)
      }
    }
    matchImageRDD
  }


  /**
    * 保存查询到的相似图像结果
    * @param rdd
    */
  def saveSimilarImageName(rdd:RDD[(String,Array[(Int,String)])]): Unit ={
    if (!rdd.isEmpty()) {
      val similarImageTable = "similarImageTable"
      val hConfig = HBaseConfiguration.create()
      hConfig.set("hbase.zookeeper.property.clientPort", "2181")
      hConfig.set("hbase.zookeeper.quorum", "fang-ubuntu,fei-ubuntu,kun-ubuntu")
      val jobConf = new JobConf(hConfig)
      jobConf.setOutputFormat(classOf[TableOutputFormat])
      jobConf.set(TableOutputFormat.OUTPUT_TABLE, similarImageTable)
      rdd.map {
        tuple => {
          val put: Put = new Put(Bytes.toBytes(tuple._1))
          val tupleArray = tuple._2
          var i = 1
          for (tup <- tupleArray) {
            put.addColumn(Bytes.toBytes("similarImage"), Bytes.toBytes("image" + "_" + i), Bytes.toBytes(tup._2 + "#" + tup._1))
            i = i + 1
          }
          (new ImmutableBytesWritable, put)
        }
        //.saveAsNewAPIHadoopDataset(jobConf)
        // java.lang.NullPointerException
      }.saveAsHadoopDataset(jobConf)
      // println("处理的图片数量"+rdd.count())
      //       println("=======================保存相似图像=======================")
      //          val saveSimilarTime = System.currentTimeMillis()
      //          println("获得相似图像的时间"+saveSimilarTime)
      //把计算时间保存成文件
      rdd.map(tuple=>tuple._1+" 获得相似图像的时间 "+System.currentTimeMillis()).saveAsTextFile("/sparkStreaming100Pic/"+System.currentTimeMillis())
     // rdd.foreach(tuple=>println(tuple._1+" 获得相似图像的时间 "+System.currentTimeMillis()))
      println("=======================保存相似图像完成=======================")
    }
  }

  /**
   *打印相似图像的名称
   */
  def printStreamRDD(printRDD: DStream[(String, Array[(Int, String)])]): Unit = {
    printRDD.foreachRDD {
      rdd => {
        rdd.foreach {
          imageTuple => {
            println(imageTuple._1 + " similar:")
            println("============================")
            for (i <- imageTuple._2) {
              println(i)
            }
            println("============================")
          }
        }
      }
    }
  }

}
