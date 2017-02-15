package com.fang.spark

import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.client.Put
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapred.TableOutputFormat
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.mapred.JobConf
import org.apache.spark.{SparkConf, SparkContext}
import org.opencv.core.Core

/**
  * Created by fang on 17-2-13.
  * 优化代码,解决内存溢出的问题
  * 单机情况下,注意开启的线程数(local[*]),默认每个线程占用总内存可能溢出
  * 单机情况下还是出现:Failed to write core dump. Core dumps have been disabled.
  * 初步预计图像文件夹太大会出现上面的问题
  * To enable core dumping, try "ulimit -c unlimited" before starting Java again
  * 修改了foreachPartition转换操作为map
  * ./spark-submit --master spark://fang-ubuntu:7077 --class com.fang.spark.HBaseUpLoadImages --jars opencv-2413.jar MyProject.jar
  */
object SaveImagesInHBase {
  def main(args: Array[String]): Unit = {
    val sparkConf = new SparkConf()
      .setAppName("SaveImagesInHBase")
      //.setMaster("local[2]")
    val sparkContext = new SparkContext(sparkConf)
    val hbaseConf = HBaseConfiguration.create()
    hbaseConf.set("hbase.zookeeper.property.clientPort", "2181")
    hbaseConf.set("hbase.zookeeper.quorum", "fang-ubuntu,fei-ubuntu,kun-ubuntu")
    val jobConf = new JobConf(hbaseConf, this.getClass)
    jobConf.set(TableOutputFormat.OUTPUT_TABLE, "imagesTest")
    //设置job的输出格式
    jobConf.setOutputFormat(classOf[TableOutputFormat])
    val imagesRDD = sparkContext.binaryFiles(SparkUtils.imagePath)
    val imagesResult = imagesRDD.map {
      imageFile => {
        //加载Opencv库,在每个分区都需加载
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val tempPath = imageFile._1.split("/")
        val len = tempPath.length
        val imageName = tempPath(len - 1)
        val imageBinary: scala.Array[Byte] = imageFile._2.toArray()
        val put: Put = new Put(Bytes.toBytes(imageName))
        put.addColumn(Bytes.toBytes("image"), Bytes.toBytes("binary"), imageBinary)
        put.addColumn(Bytes.toBytes("image"), Bytes.toBytes("path"), Bytes.toBytes(imageFile._1))
        val sift = SparkUtils.getImageSift(imageBinary)
        if (!sift.isEmpty) {
          put.addColumn(Bytes.toBytes("image"), Bytes.toBytes("sift"), sift.get)
        }
        (new ImmutableBytesWritable, put)
      }
    }
    // imagesResult.saveAsNewAPIHadoopDataset(jobConf)
    imagesResult.saveAsHadoopDataset(jobConf)
    sparkContext.stop()
  }

}