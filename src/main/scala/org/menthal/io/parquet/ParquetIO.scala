package org.menthal.io.parquet

import org.apache.avro.Schema
import org.apache.avro.specific.SpecificRecord
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.mapreduce.Job
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.menthal.io.hdfs.HDFSFileService
import org.menthal.model.Granularity.TimePeriod
import org.menthal.model.events.{Summary, AggregationEntry, MenthalEvent}
import org.menthal.model.{AggregationType, Granularity, EventType}
import parquet.avro._
import parquet.filter.UnboundRecordFilter
import parquet.hadoop.{ParquetInputFormat, ParquetOutputFormat}

import scala.reflect.ClassTag
import scala.util.Try

object ParquetIO {

  def pathFromEventType(dirPath: String, eventType: Int):String = s"$dirPath/${EventType.toPath(eventType)}"
  def pathFromAggrType(dirPath: String, aggrName: String, timePeriod: TimePeriod):String = s"$dirPath/$aggrName/${Granularity.asString(timePeriod)}"


  def filterAndWriteToParquet(sc: SparkContext, dirPath: String, eventType: Int, events: RDD[_ <: MenthalEvent]) = {
    val filteredEvents = for (e ← events if EventType.fromMenthalEvent(e) == eventType) yield e.toAvro
    writeEventType(sc, dirPath, eventType, filteredEvents)
  }

  def writeSummary(sc: SparkContext, dirPath: String, timePeriod: TimePeriod, aggregates: RDD[Summary], overwrite:Boolean = false) = {
    val path = pathFromAggrType(dirPath, AggregationType.Summary, timePeriod)
    if (overwrite)
      ParquetIO.overwrite(sc, aggregates, path, Summary.getClassSchema)
    else
      ParquetIO.write(sc, aggregates, path, Summary.getClassSchema)
  }
  def writeAggrType(sc: SparkContext, dirPath: String, aggrName: String, timePeriod: TimePeriod, aggregates: RDD[AggregationEntry], overwrite:Boolean = false) = {
    val path = pathFromAggrType(dirPath, aggrName, timePeriod)
    if (overwrite)
      ParquetIO.overwrite[AggregationEntry](sc, aggregates, path, AggregationEntry.getClassSchema)
    else
      ParquetIO.write[AggregationEntry](sc, aggregates, path, AggregationEntry.getClassSchema)
  }
  def writeEventType[A <: SpecificRecord](sc:SparkContext, dirPath:String,  eventType: Int, events: RDD[A], overwrite:Boolean = false)(implicit ct:ClassTag[A])= {
    val path = pathFromEventType(dirPath, eventType)
    val schema = EventType.toSchema(eventType)
    if (overwrite)
      ParquetIO.overwrite[A](sc, events, path, schema)(ct)
    else
      ParquetIO.write[A](sc, events, path, schema)(ct)
  }

  def overwrite[A <: SpecificRecord](sc: SparkContext, data: RDD[A], path: String, schema:Schema)(implicit ct:ClassTag[A]) ={
    if (HDFSFileService.exists(path))
          HDFSFileService.removeDir(path)
    ParquetIO.write[A](sc, data, path, schema)(ct)
  }

  def readAggrType(sc: SparkContext, dirPath: String, aggrName: String, timePeriod: TimePeriod, recordFilter: Option[Class[_ <: UnboundRecordFilter]] = None) = {
    val path = pathFromAggrType(dirPath, aggrName, timePeriod)
    read(sc, path, recordFilter)
  }

  def readEventType[A <: SpecificRecord](
    sc: SparkContext,
    dirPath: String,
    eventType: Int,
    recordFilter:Option[Class[_ <: UnboundRecordFilter]]=None)
      (implicit ct:ClassTag[A]): RDD[A] = {
    val path = pathFromEventType(dirPath,eventType)
    read(sc, path, recordFilter)(ct)
  }

  def write[A <: SpecificRecord](sc: SparkContext, data: RDD[A], path: String, schema:Schema)(implicit ct:ClassTag[A]) = {
    //val isEmpty = data.fold(0)
    //val isEmpty = Try(data.first()).isFailure

    //if (!isEmpty) {
      val writeJob = Job.getInstance(new Configuration)
      ParquetOutputFormat.setWriteSupportClass(writeJob, classOf[AvroWriteSupport])
      val pairs: RDD[(Void, A)] = data.map((null, _))
      AvroParquetOutputFormat.setSchema(writeJob, schema)

      pairs.saveAsNewAPIHadoopFile(
        path,
        classOf[Void],
        ct.runtimeClass,
        classOf[ParquetOutputFormat[A]],
        writeJob.getConfiguration)

    //}
  }

  def read[A <: SpecificRecord](sc: SparkContext, path: String, recordFilter:Option[Class[_ <: UnboundRecordFilter]]=None)(implicit ct:ClassTag[A]): RDD[A] = {
    val readJob = Job.getInstance(new Configuration)
    ParquetInputFormat.setReadSupportClass(readJob, classOf[AvroReadSupport[A]])

    if(recordFilter.isDefined){
      ParquetInputFormat.setUnboundRecordFilter(readJob, recordFilter.get)
    }

    val filteredFile = sc.newAPIHadoopFile(
      path,
      classOf[ParquetInputFormat[A]],
      classOf[Void],
      ct.runtimeClass.asInstanceOf[Class[A]],
      readJob.getConfiguration)
      .map(_._2.asInstanceOf[A])

    filteredFile
  }
}
