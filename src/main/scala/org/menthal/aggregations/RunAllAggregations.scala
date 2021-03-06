package org.menthal.aggregations

import org.menthal.io.hdfs.HDFSFileService
import org.menthal.spark.SparkHelper.getSparkContext

/**
 * Created by konrad on 23.01.15.
 */
object RunAllAggregations {
  val name = "RunAllAggregations"
  def main(args: Array[String]) {
    val (master, datadir, lookupFile, tmpPrefix, output) = args match {
      case Array(m, d) =>
        (m, d, d + "/categories.csv", None, None)
      case Array(m, d, l) =>
        (m, d, l, None, None)
      case Array(m, d, l, t, o) =>
        (m, d, l, Some(t), Some(t))
      case _ =>
        val errorMessage = "First argument is master, second datadir path, third argument is path to categories lookup"
        throw new IllegalArgumentException(errorMessage)
    }

    val sc = getSparkContext(master, name)
    val workingDir= tmpPrefix match {
      case Some(prefix) => HDFSFileService.copyToTmp(datadir, "/tmp", prefix).getOrElse(fail("Cannot create tmp directory"))
      case None => datadir
    }

    AppSessionsAggregations.aggregate(sc, workingDir)
    GeneralAggregations.aggregate(sc, workingDir)
    CategoriesAggregation.aggregateCategories(sc, workingDir, lookupFile)
    SleepAggregations.aggregateSleep(sc, workingDir)
    SummaryAggregation.aggregate(sc, workingDir)

    output.map(path => HDFSFileService.copy(workingDir, path))
    sc.stop()
  }

  def fail(msg:String): String = {
    throw new RuntimeException(msg)
  }

}
