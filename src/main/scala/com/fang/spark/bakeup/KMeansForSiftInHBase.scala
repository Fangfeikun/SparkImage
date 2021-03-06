package com.fang.spark.bakeup

/**
  * Created by fang on 16-12-15.
  * 对存储在HBase中的image表中的数据进行KMeans聚类，并保存训练完成的模型
  */

import com.fang.spark.{ImagesUtil, Utils}
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.client._
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapred.TableOutputFormat
import org.apache.hadoop.hbase.mapreduce.TableInputFormat
import org.apache.hadoop.hbase.protobuf.ProtobufUtil
import org.apache.hadoop.hbase.util.{Base64, Bytes}
import org.apache.hadoop.mapred.JobConf
import org.apache.spark.mllib.clustering.{KMeans, KMeansModel}
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.storage.StorageLevel
import org.apache.spark.{SparkConf, SparkContext}

object KMeansForSiftInHBase extends App {
  val beginKMeans = System.currentTimeMillis()
  val sparkConf = new SparkConf()//.setMaster("spark://fang-ubuntu:7077")
    .setMaster("local[4]")
    .setAppName("KMeansForSiftInHBase")
    .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
  val sc = new SparkContext(sparkConf)
  sc.setLogLevel("ERROR")
  val readSiftTime = System.currentTimeMillis()
  val hbaseConf = HBaseConfiguration.create()
  val tableName = "imagesTest"
  hbaseConf.set(TableInputFormat.INPUT_TABLE, tableName)
  hbaseConf.set("hbase.zookeeper.property.clientPort", "2181")
  hbaseConf.set("hbase.zookeeper.quorum", "fang-ubuntu")
  val scan = new Scan()
  scan.addColumn(Bytes.toBytes("image"), Bytes.toBytes("sift"))
  val proto = ProtobufUtil.toScan(scan)
  val ScanToString = Base64.encodeBytes(proto.toByteArray())
  hbaseConf.set(TableInputFormat.SCAN, ScanToString)
  val hbaseRDD = sc.newAPIHadoopRDD(hbaseConf, classOf[TableInputFormat],
    classOf[org.apache.hadoop.hbase.io.ImmutableBytesWritable],
    classOf[org.apache.hadoop.hbase.client.Result])
  ImagesUtil.printComputeTime(readSiftTime,"readSiftTime")
 /* val siftRDD = hbaseRDD.map(x => x._2)
    .flatMap {
      result =>
        val siftByte = result.getValue(Bytes.toBytes("image"), Bytes.toBytes("sift"))
        val siftArray: Array[Float] = Utils.deserializeMat(siftByte)
        val size = siftArray.length / 128
        val siftTwoDim = new Array[Array[Float]](size)
        for (i <- 0 to size - 1) {
          val xs: Array[Float] = new Array[Float](128)
          //          println(i*128)
          //          siftArray.copyToArray(xs, i*128, 128)
          //          siftTwoDim(i) = xs
          for (j <- 0 to 127) {
            xs(j) = siftByte(i * 128 + j)
          }
          siftTwoDim(i) = xs
        }
        (result.getRow,siftTwoDim)
    }*/
  val transformSift = System.currentTimeMillis()
 val siftRDD = hbaseRDD.map{
     result =>
       val siftByte = result._2.getValue(Bytes.toBytes("image"), Bytes.toBytes("sift"))
       val siftArray: Array[Float] = Utils.deserializeMat(siftByte)
       val size = siftArray.length / 128
       val siftTwoDim = new Array[Array[Float]](size)
       for (i <- 0 to size - 1) {
         val xs: Array[Float] = new Array[Float](128)
         for (j <- 0 to 127) {
           xs(j) = siftByte(i * 128 + j)
         }
         siftTwoDim(i) = xs
       }
       (result._2.getRow,siftTwoDim)
   }
  siftRDD.persist(StorageLevel.MEMORY_AND_DISK_SER_2)
  val siftDenseRDD = siftRDD.flatMap(_._2).map(data => Vectors.dense(data.map(i => i.toDouble)))
  ImagesUtil.printComputeTime(transformSift,"tranform sift")
  val kmeansTime = System.currentTimeMillis()
  val numClusters = 4
  val numIterations = 30
  val runTimes = 3
  var clusterIndex: Int = 0
  val clusters: KMeansModel = KMeans.train(siftDenseRDD, numClusters, numIterations, runTimes)
  clusters.save(sc,"./kmeansModel")
  ImagesUtil.printComputeTime(kmeansTime,"kmeansTime")
  //  println("Cluster Number:" + clusters.clusterCenters.length)
  //  println("Cluster Centers Information Overview:")
  //clusters.save(sc, "src/main/resources/KMeansModel")
  //  clusters.clusterCenters.foreach(
  //    x => {
  //      println("Center Point of Cluster " + clusterIndex + ":")
  //      println(x)
  //      clusterIndex += 1
  //    })
 // println(siftRDD.count())
//  hbaseConf.unset(TableInputFormat.SCAN)
  val computeHistogram = System.currentTimeMillis()
  hbaseConf.unset(TableInputFormat.INPUT_TABLE)
  val jobConf = new JobConf(hbaseConf)
  jobConf.setOutputFormat(classOf[TableOutputFormat])
  jobConf.set(TableOutputFormat.OUTPUT_TABLE, tableName)
  val histogramRDD = siftRDD.map {
    result => {
      /**一个Put对象就是一行记录，在构造方法中指定主键
       * 所有插入的数据必须用org.apache.hadoop.hbase.util.Bytes.toBytes方法转换
       * Put.add方法接收三个参数：列族，列名，数据
       */
      val myKmeansModel = KMeansModel.load(sc,"./kmeansModel")
      val histogramArray = new Array[Int](myKmeansModel.clusterCenters.length)
      //val siftArray: Array[Float] =result._2.map(i=>i.toDouble)
      for (i <- 0 to result._2.length - 1) {
        val predictedClusterIndex: Int = myKmeansModel.predict(Vectors.dense(result._2(i).map(i=>i.toDouble)))
        histogramArray(predictedClusterIndex) = histogramArray(predictedClusterIndex) + 1
      }
      val put: Put = new Put(result._1)
      put.addColumn(Bytes.toBytes("image"), Bytes.toBytes("histogram"), Utils.serializeObject(histogramArray))
      (new ImmutableBytesWritable, put)
    }
  }
  ImagesUtil.printComputeTime(computeHistogram,"compute images histogram")
  val saveHistogram = System.currentTimeMillis()
  histogramRDD.saveAsHadoopDataset(jobConf)
  ImagesUtil.printComputeTime(saveHistogram,"save images histogram")
  /*val histogramRDD = hbaseRDD.foreachPartition {
    iter => {
      // val hbaseConfig = HBaseConfiguration.create()
      val connection: Connection = ConnectionFactory.createConnection(hbaseConf)
      val tableName = "imagesTest"
      val table: Table = connection.getTable(TableName.valueOf(tableName))
      iter.foreach {
        result =>
          val histogramArray = new Array[Int](numClusters)
          val siftByte = result._2.getValue(Bytes.toBytes("image"), Bytes.toBytes("sift"))
            val siftArray: Array[Float] = Utils.deserializeMat(siftByte)
            val size = siftArray.length / 128
            for (i <- 0 to size - 1) {
              val xs: Array[Float] = new Array[Float](128)
              for (j <- 0 to 127) {
                xs(j) = siftByte(i * 128 + j)
              }
              val predictedClusterIndex: Int = clusters.predict(Vectors.dense(xs.map(x => x.toDouble)))
              histogramArray(predictedClusterIndex) = histogramArray(predictedClusterIndex) + 1
            }
            val put: Put = new Put(result._2.getRow())
            put.addColumn(Bytes.toBytes("image"), Bytes.toBytes("histogram"), Utils.serializeObject(histogramArray))
            table.put(put)
          }
    }
  }*/
  sc.stop()
  ImagesUtil.printComputeTime(beginKMeans,"time for the job")
}

//  val computeHistogram = System.currentTimeMillis()
//  hbaseConf.unset(TableInputFormat.INPUT_TABLE)
//  val jobConf = new JobConf(hbaseConf)
//  jobConf.setOutputFormat(classOf[TableOutputFormat])
//  jobConf.set(TableOutputFormat.OUTPUT_TABLE, tableName)
//  val histogramRDD = siftRDD.map {
//    result => {
//      /**一个Put对象就是一行记录，在构造方法中指定主键
//       * 所有插入的数据必须用org.apache.hadoop.hbase.util.Bytes.toBytes方法转换
//       * Put.add方法接收三个参数：列族，列名，数据
//       */
//      val myKmeansModel = KMeansModel.load(sc,"./kmeansModel")
//      val histogramArray = new Array[Int](myKmeansModel.clusterCenters.length)
//      //val siftArray: Array[Float] =result._2.map(i=>i.toDouble)
//      for (i <- 0 to result._2.length - 1) {
//        val predictedClusterIndex: Int = myKmeansModel.predict(Vectors.dense(result._2(i).map(i=>i.toDouble)))
//        histogramArray(predictedClusterIndex) = histogramArray(predictedClusterIndex) + 1
//      }
//      val put: Put = new Put(result._1)
//      put.addColumn(Bytes.toBytes("image"), Bytes.toBytes("histogram"), Utils.serializeObject(histogramArray))
//      (new ImmutableBytesWritable, put)
//    }
//  }
//  SparkUtils.printComputeTime(computeHistogram,"compute images histogram")
//  val saveHistogram = System.currentTimeMillis()
//  histogramRDD.saveAsHadoopDataset(jobConf)
//  SparkUtils.printComputeTime(saveHistogram,"save images histogram")