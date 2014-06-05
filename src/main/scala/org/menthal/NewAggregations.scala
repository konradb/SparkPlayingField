package org.menthal

import org.apache.spark.{Partitioner, SparkContext}
import org.menthal.AppSessionMonoid._
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import com.twitter.algebird.Operators._
import org.joda.time.DateTime

/**
 * Created by mark on 04.06.14.
 */
object NewAggregations {
  def main(args:Array[String]) {
    if (args.length < 2) {
      System.err.println("Usage: NewAggregations dumpFile")
      System.exit(1)
    }
    val sc = new SparkContext(args(0), "Aggregations", System.getenv("SPARK_HOME"))//, SparkContext.jarOfClass(this.getClass))
    val dumpFile = args(1)
    val eventsDump = sc.textFile(dumpFile,2)
    val events = linesToEvents(eventsDump)
    reduceToAppContainers(events)
    sc.stop()
  }

  def linesToEvents(lines:RDD[String]):RDD[Event[_ <: EventData]] = 
    lines.flatMap(Event.tryToParseLine) 

  def reduceToAppContainers(events:RDD[Event[_ <: EventData]]):RDD[Pair[Long, AppSessionContainer]] = {
    val containers: RDD[Pair[Pair[Long, Long],AppSessionContainer]] = for {
      event <- events
      time = event.time.getMillis
      user = event.userId
      container = AppSessionContainer(event)
      if AppSessionContainer.handledEvents.contains(event.data.eventType)
    } yield ((time, user), container)

    val sortedAndGrouped = containers.sortByKey().map{case ((time,user), container) => (user,container)}
    sortedAndGrouped.reduceByKey( _ + _ )
  }
}
