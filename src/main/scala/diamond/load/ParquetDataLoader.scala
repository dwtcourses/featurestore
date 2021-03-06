package diamond.load

import java.net.URI
import java.nio.charset.Charset
import java.sql.{Date, Timestamp}

import com.github.nscala_time.time.Imports._
import common.utility.dateFunctions._
import common.utility.hashFunctions._
import common.utility.stringFunctions._
import common.utility.udfs._
import diamond.AppConfig
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SQLContext, SaveMode}
import org.json4s.native.Serialization

/**
  * Parquet writes columns out of order (compared to the schema)
  * https://issues.apache.org/jira/browse/PARQUET-188
  * Fixed in 1.6.0
  *
  * Created by markmo on 23/01/2016.
  */
class ParquetDataLoader(implicit val conf: AppConfig) extends DataLoader {

  import conf.data.filename

  val FILE_EXT = ".parquet"
  val FILE_NEW = s"${filename("new")}"
  val FILE_CHANGED = s"${filename("changed")}$FILE_EXT"
  val FILE_REMOVED = s"${filename("removed")}$FILE_EXT"
  val FILE_CURRENT = s"${filename("current")}$FILE_EXT"
  val FILE_HISTORY = s"${filename("history")}$FILE_EXT"
  val FILE_PROCESS = s"${filename("process")}.csv"
  val FILE_META = s"${filename("meta")}.json"
  val FILE_PREV = s"${filename("prev")}$FILE_EXT"

  val LAYER_ACQUISITION = conf.data.acquisition.path

  def loadAll(sqlContext: SQLContext, processType: String, processId: String, userId: String) =
    loadAllInternal(sqlContext,
      source => sqlContext.read.load(source),
      processType, processId, userId)

  def loadHub(df: DataFrame,
              isDelta: Boolean,
              entityType: String,
              idFields: List[String],
              idType: String,
              source: String,
              processType: String,
              processId: String,
              userId: String,
              tableName: Option[String] = None,
              validStartTimeField: Option[(String, String)] = None,
              validEndTimeField: Option[(String, String)] = None,
              deleteIndicatorField: Option[(String, Any)] = None,
              newNames: Map[String, String] = Map(),
              overwrite: Boolean = false): Unit = {

    if (isDelta && overwrite) throw sys.error("isDelta and overwrite options are mutually exclusive")
    val fs = FileSystem.get(new URI(BASE_URI), new Configuration())
    val tn = if (tableName.isDefined) tableName.get else s"${entityType.toLowerCase}_hub"
    val path = s"/$LAYER_ACQUISITION/$tn/$FILE_HISTORY"
    val currentPath = s"$BASE_URI/$LAYER_ACQUISITION/$tn/$FILE_CURRENT"
    val sqlContext = df.sqlContext
    sqlContext.udf.register("hashKey", hashKey(_: String))
    sqlContext.udf.register("convertStringToTimestamp", convertStringToTimestamp(_: String, _: String))
    val renamed = newNames.foldLeft(df) {
      case (d, (oldName, newName)) => d.withColumnRenamed(oldName, newName)
    }

    // dedup
    val in = renamed.distinct()
    in.registerTempTable("imported")

    val pk = idFields.map(f => newNames.getOrElse(f, f))
    val (validStartTimeExpr, validEndTimeExpr) =
      if (validStartTimeField.isDefined && validEndTimeField.isDefined) {
        (
          s"convertStringToTimestamp(i.${validStartTimeField.get._1}, '${validStartTimeField.get._2}'",
          s"convertStringToTimestamp(i.${validEndTimeField.get._1}, '${validEndTimeField.get._2}'"
          )
      } else {
        ("current_timestamp()", s"'$META_OPEN_END_DATE_VALUE'")
      }

    val inIdCols = pk.map("i." + _).mkString(",")
    val sql =
      s"""
         |select hashKey(concat('$idType',$inIdCols)) as $META_ENTITY_ID
         |,'$entityType' as $META_ENTITY_TYPE
         |,$inIdCols
         |,'$idType' as $META_ID_TYPE
         |,current_timestamp() as $META_START_TIME
         |,'$META_OPEN_END_DATE_VALUE' as $META_END_TIME
         |,'$source' as $META_SOURCE
         |,'$processType' as $META_PROCESS_TYPE
         |,'$processId' as $META_PROCESS_ID
         |,current_date() as $META_PROCESS_DATE
         |,'$userId' as $META_USER_ID
         |,$validStartTimeExpr as $META_VALID_START_TIME
         |,$validEndTimeExpr as $META_VALID_END_TIME
         |,'$RECTYPE_INSERT' as $META_RECTYPE
         |,1 as $META_VERSION
         |from imported i
       """.stripMargin

    val saveMode = if (overwrite) SaveMode.Overwrite else SaveMode.Append
    val now = DateTime.now()

    if (fs.exists(new Path(path))) {
      val existing = sqlContext.read.load(s"$BASE_URI$path")
      existing.registerTempTable("existing")

      val joinPredicates = pk.map(f => s"e.$f = i.$f").mkString(" and ")

      val sqlNewEntities =
        s"""
           |$sql
           |left join existing e on $joinPredicates and e.$META_ID_TYPE = '$idType'
           |where e.$META_ENTITY_ID is null
         """.stripMargin

      val validStartExpr =
        if (validStartTimeField.isDefined && validEndTimeField.isDefined) {
          validStartTimeExpr
        } else {
          s"e.$META_VALID_START_TIME"
        }

      // spark (as of 1.5.2) doesn't support subqueries
      // https://issues.apache.org/jira/browse/SPARK-4226

      val latest = sqlContext.sql(
        s"""
           |select $META_ENTITY_ID, max($META_VERSION) as $META_MAX_VERSION
           |from existing
           |group by $META_ENTITY_ID
         """.stripMargin)

      latest.registerTempTable("latest")

      val exIdCols = pk.map("e." + _).mkString(",")
      val selectExisting =
        s"""
           |select e.$META_ENTITY_ID
           |,e.$META_ENTITY_TYPE
           |,e.$META_START_TIME
           |,$exIdCols
           |,e.$META_ID_TYPE
           |,current_timestamp() as $META_END_TIME
           |,'$source' as $META_SOURCE
           |,'$processType' as $META_PROCESS_TYPE
           |,'$processId' as $META_PROCESS_ID
           |,current_date() as $META_PROCESS_DATE
           |,'$userId' as $META_USER_ID
           |,$validStartExpr $META_VALID_START_TIME
           |,$validEndTimeExpr as $META_VALID_END_TIME
           |,'$RECTYPE_DELETE' as $META_RECTYPE
           |,e.$META_VERSION + 1 as $META_VERSION
           |from existing e
           |inner join latest l on l.$META_ENTITY_ID = e.$META_ENTITY_ID
           |and l.$META_MAX_VERSION = e.$META_VERSION
         """.stripMargin

      val (all: DataFrame, insertsCount: Long, deletesCount: Long) =
        if (deleteIndicatorField.isDefined) {
          val delIndField = deleteIndicatorField.get._1
          val delIndFieldVal = deleteIndicatorField.get._2.toString
          val delIndFieldLit = if (delIndFieldVal.isNumber) delIndFieldVal else s"'$delIndFieldVal'"

          // inserts
          val inserts = sqlContext.sql(
            s"""
               |$sqlNewEntities
               |and i.$delIndField <> $delIndFieldLit
             """.stripMargin)

          if (overwrite) {
            (inserts, inserts.count(), 0L)
          } else {
            // union deletes
            val deletes = sqlContext.sql(
              s"""
                 |$selectExisting
                 |join imported i on $joinPredicates
                 |where e.$META_ID_TYPE = '$idType'
                 |where i.$delIndField = $delIndFieldLit
               """.stripMargin)

            (inserts.unionAll(deletes), inserts.count(), deletes.count())
          }
        } else if (!isDelta) {
          // inserts
          val inserts = sqlContext.sql(sqlNewEntities)
          if (overwrite) {
            (inserts, inserts.count(), 0L)
          } else {
            // union deletes
            val deletes = sqlContext.sql(
              s"""
                 |$selectExisting
                 |left join imported i on $joinPredicates
                 |where e.$META_ID_TYPE = '$idType'
                 |and i.${pk.head} is null
               """.stripMargin)

            (inserts.unionAll(deletes), inserts.count(), deletes.count())
          }
        } else {
          // inserts
          val inserts = sqlContext.sql(sqlNewEntities)
          (inserts, inserts.count(), 0L)
        }

      all.write
        .partitionBy(META_ID_TYPE)
        .mode(saveMode)
        .parquet(s"$BASE_URI$path")

      // write snapshot
      val latestDF = snapshot(all)

      latestDF
        .write
        .mode(SaveMode.Overwrite)
        .parquet(currentPath)

      val readCount = df.count()
      writeProcessLog(fs, sqlContext, tn, processId, processType, userId,
        readCount, readCount - in.count(), insertsCount, 0,
        deletesCount, now, now)

    } else {
      val entities = sqlContext.sql(sql)
      val renamed = newNames.foldLeft(entities) {
        case (d, (oldName, newName)) => d.withColumnRenamed(oldName, newName)
      }
      renamed.cache()

      val writer = renamed.write
        .partitionBy(META_ID_TYPE)
        .mode(saveMode)

      writer.parquet(s"$BASE_URI$path")
      writer.parquet(currentPath)
      val readCount = df.count()
      writeProcessLog(fs, sqlContext, tn, processId, processType, userId,
        readCount, readCount - in.count(), readCount, 0, 0,
        now, now)

      val metadata = Map(
        "entityType" -> entityType,
        "idFields" -> idFields,
        "idType" -> idType,
        "newNames" -> newNames,
        "validStartTimeField" -> validStartTimeField,
        "validEndTimeField" -> validEndTimeField,
        "deleteIndicatorField" -> deleteIndicatorField
      )
      writeMetaFile(fs, tn, metadata)

      renamed.unpersist()
    }
  }

  def loadSatellite(df: DataFrame,
                    isDelta: Boolean,
                    tableName: String,
                    idFields: List[String],
                    idType: String,
                    source: String,
                    processType: String,
                    processId: String,
                    userId: String,
                    projection: Option[List[String]] = None,
                    validStartTimeField: Option[(String, String)] = None,
                    validEndTimeField: Option[(String, String)] = None,
                    deleteIndicatorField: Option[(String, Any)] = None,
                    partitionKeys: Option[List[String]] = None,
                    newNames: Map[String, String] = Map(),
                    overwrite: Boolean = false,
                    writeChangeTables: Boolean = false): Unit = {

    if (isDelta && overwrite) throw sys.error("isDelta and overwrite options are mutually exclusive")

    // dedup
    val distinct = if (projection.isDefined) {
      df.select(projection.get.map(col): _*).distinct()
    } else {
      df.distinct()
    }
    val renamed = newNames.foldLeft(distinct) {
      case (d, (oldName, newName)) => d.withColumnRenamed(oldName, newName)
    }
    val pk = idFields.map(f => newNames.getOrElse(f, f))
    val baseNames = renamed.schema.fieldNames.toList diff pk
    val t = renamed
      .withColumn(META_ENTITY_ID, hashKeyUDF(concat(lit(idType), concat(pk.map(col): _*))))
      .withColumn(META_START_TIME, current_timestamp().cast(TimestampType))
      .withColumn(META_END_TIME, lit(META_OPEN_END_DATE_VALUE).cast(TimestampType))
      .withColumn(META_SOURCE, lit(source))
      .withColumn(META_PROCESS_TYPE, lit(processType))
      .withColumn(META_PROCESS_ID, lit(processId))
      .withColumn(META_PROCESS_DATE, current_date())
      .withColumn(META_USER_ID, lit(userId))
      .withColumn(META_HASHED_VALUE, fastHashUDF(concat(baseNames.map(col): _*)))
      .withColumn(META_VALID_START_TIME, current_timestamp().cast(TimestampType))
      .withColumn(META_VALID_END_TIME, lit(META_OPEN_END_DATE_VALUE).cast(TimestampType))

    val in = if (validStartTimeField.isDefined && validEndTimeField.isDefined) {
      t
        .withColumn(META_VALID_START_TIME, convertStringToTimestampUDF(col(validStartTimeField.get._1), lit(validStartTimeField.get._2)))
        .withColumn(META_VALID_END_TIME, convertStringToTimestampUDF(col(validEndTimeField.get._1), lit(validEndTimeField.get._2)))
    } else {
      t
    }

    // add column headers for process metadata
    val names = META_ENTITY_ID :: baseNames ++
      List(
        META_VALID_START_TIME, META_VALID_END_TIME,
        META_START_TIME, META_END_TIME,
        META_PROCESS_DATE, META_HASHED_VALUE)

    val header = names ++ List(META_RECTYPE, META_VERSION)

    val tablePath = s"/$LAYER_ACQUISITION/$tableName/$FILE_HISTORY"
    val currentPath = s"$BASE_URI/$LAYER_ACQUISITION/$tableName/$FILE_CURRENT"
    val sqlContext = df.sqlContext
    val saveMode = if (overwrite) SaveMode.Overwrite else SaveMode.Append
    val fs = FileSystem.get(new URI(BASE_URI), new Configuration())
    val now = DateTime.now()

    if (fs.exists(new Path(tablePath))) {

      // need to use the __current__ set or join below will match multiple rows
      val ex = sqlContext.read.load(currentPath)
        .where(s"$META_RECTYPE <> '$RECTYPE_DELETE'")
        .cache()

      // with update capability, read would filter on `end_time = '9999-12-31'`
      // to select current records, but end_time is not being updated on old records

      // records in the new set that aren't in the existing set
      val newRecords = in
        .join(ex, in(META_ENTITY_ID) === ex(META_ENTITY_ID), "left_outer")
        .where(ex(META_ENTITY_ID).isNull)
        .select(names.map(in(_)): _*)
        .withColumn(META_RECTYPE, lit(RECTYPE_INSERT))
        .withColumn(META_VERSION, lit(1))

      // records in the new set that are also in the existing set
      // but whose values have changed
      val changed = in
        .join(ex, META_ENTITY_ID)
        .where(in(META_HASHED_VALUE) !== ex(META_HASHED_VALUE))
        .cache()

      val (inserts, updates, deletes) =
        if (deleteIndicatorField.isDefined) {
          val deletesNew = newRecords
            .where(col(deleteIndicatorField.get._1) === lit(deleteIndicatorField.get._2))
            .withColumn(META_RECTYPE, lit(RECTYPE_DELETE))

          val deletesExisting = changed
            .where(col(deleteIndicatorField.get._1) === lit(deleteIndicatorField.get._2))
            .select(in(META_START_TIME) :: header.map(ex(_)): _*)
            .withColumn(META_END_TIME, in(META_START_TIME))
            .drop(in(META_START_TIME))
            .withColumn(META_RECTYPE, lit(RECTYPE_DELETE))

          (
            // inserts
            newRecords.where(col(deleteIndicatorField.get._1) !== lit(deleteIndicatorField.get._2)),

            // updates
            changed.where(col(deleteIndicatorField.get._1) !== lit(deleteIndicatorField.get._2)),

            // deletes
            if (overwrite) {
              Some(deletesExisting
                .withColumn(META_VERSION, ex(META_VERSION) + lit(1))
                .unionAll(deletesNew))
            } else {
              Some(deletesExisting.unionAll(deletesNew))
            }
            )
        } else if (!isDelta) {
          val deletesExisting = ex
            .join(in, ex(META_ENTITY_ID) === in(META_ENTITY_ID), "left_outer")
            .where(in(META_ENTITY_ID).isNull)
            .select(header.map(ex(_)): _*)
            .withColumn(META_END_TIME, current_timestamp().cast(TimestampType))
            .withColumn(META_RECTYPE, lit(RECTYPE_DELETE))

          (
            // inserts
            newRecords,

            // updates
            changed,

            // deletes
            if (overwrite) {
              Some(deletesExisting)
            } else {
              Some(deletesExisting.withColumn(META_VERSION, ex(META_VERSION) + lit(1)))
            }
            )
        } else {
          (newRecords, changed, None)
        }

      updates.cache().registerTempTable("updated")

      // TODO up to here

      val updatesNew = updates
        .select(ex(META_VERSION).as(META_OLD_VERSION) :: names.map(in(_)): _*)
        .withColumn(META_RECTYPE, lit(RECTYPE_UPDATE))
        .withColumn(META_VERSION, col(META_OLD_VERSION) + lit(1))
        .drop(META_OLD_VERSION)

      if (writeChangeTables) {
        inserts.cache()
        updatesNew.cache()
        if (deletes.isDefined) deletes.get.cache()
      }

      val updatez =
        if (overwrite) {
          val cols =
            in(META_START_TIME).as(META_NEW_START_TIME) ::
              in(META_ENTITY_ID) :: (header diff List(META_ENTITY_ID)).map(ex(_))

          val updatesExisting = updates
            .select(cols: _*)
            .withColumn(META_RECTYPE, lit(RECTYPE_UPDATE))
            .withColumn(META_END_TIME, col(META_NEW_START_TIME))
            .select(header.map(col): _*)

          updatesNew.unionAll(updatesExisting)
        } else {
          updatesNew
        }

      val all = deletes match {
        case Some(ds) => inserts.unionAll(updatez).unionAll(ds)
        case None => inserts.unionAll(updatez)
      }

      val main =
        if (overwrite) {
          val ex = sqlContext.read.load(s"$BASE_URI$tablePath")
          val prevPath = s"$BASE_URI/$LAYER_ACQUISITION/$tableName/$FILE_PREV"
          ex.write.mode(SaveMode.Overwrite).parquet(prevPath)
          val prev = sqlContext.read.load(prevPath)
          prev
            .join(all, prev(META_ENTITY_ID) === all(META_ENTITY_ID) && prev(META_VERSION) === all(META_VERSION), "left_outer")
            .where(all(META_ENTITY_ID).isNull)
            .select(header.map(prev(_)): _*)
            .unionAll(all)
        } else {
          all
        }

      main.cache()

      val writer =
        if (partitionKeys.isDefined) {
          main.write.mode(saveMode).partitionBy(partitionKeys.get: _*)
        } else {
          main.write.mode(saveMode)
        }

      writer.parquet(s"$BASE_URI$tablePath")

      // write snapshot
      val latestDF = snapshot(ex.unionAll(main))

      latestDF
        .write
        .mode(SaveMode.Overwrite)
        .parquet(currentPath)

      // append to process log
      val readCount = df.count()
      val deletesCount = if (deletes.isDefined) deletes.get.count() else 0
      writeProcessLog(fs, sqlContext, tableName, processId, processType, userId,
        readCount, readCount - distinct.count(), inserts.count(),
        updatesNew.count(), deletesCount, now, now)

      // write change tables if set
      if (writeChangeTables) {
        val daysAgo = 3
        writeChangeTable(fs, inserts, header, s"$BASE_URI/$LAYER_ACQUISITION/$tableName/$FILE_NEW", daysAgo)
        writeChangeTable(fs, updatesNew, header, s"$BASE_URI/$LAYER_ACQUISITION/$tableName/$FILE_CHANGED", daysAgo)

        updatesNew.unpersist()
        inserts.unpersist()

        if (deletes.isDefined) {
          writeChangeTable(fs, deletes.get, header, s"$BASE_URI/$LAYER_ACQUISITION/$tableName/$FILE_REMOVED", daysAgo)
          deletes.get.unpersist()
        }
      }

      main.unpersist()
      changed.unpersist()
      ex.unpersist()

    } else {
      val out = pk.foldLeft(in)({
        case (d, idField) => d.drop(idField)
      })
      out.cache()
      val w = out
        .withColumn(META_RECTYPE, lit(RECTYPE_INSERT))
        .withColumn(META_VERSION, lit(1))
        .select(header.map(col): _*)
        .write
        .mode(saveMode)

      val writer = if (partitionKeys.isDefined) w.partitionBy(partitionKeys.get: _*) else w

      writer.parquet(s"$BASE_URI$tablePath")
      writer.parquet(currentPath)
      val readCount = df.count()
      writeProcessLog(fs, sqlContext, tableName, processId, processType, userId,
        readCount, readCount - distinct.count(), readCount, 0, 0,
        now, now)

      // write meta file
      val metadata = Map(
        "idFields" -> idFields,
        "idType" -> idType,
        "partitionKeys" -> partitionKeys,
        "newNames" -> newNames,
        "validStartTimeField" -> validStartTimeField,
        "validEndTimeField" -> validEndTimeField,
        "deleteIndicatorField" -> deleteIndicatorField
      )
      writeMetaFile(fs, tableName, metadata)

      out.unpersist()
    }
  }

  def loadLink(df: DataFrame,
               isDelta: Boolean,
               srcEntityType: String, srcIdFields: List[String], srcIdType: String,
               dstEntityType: String, dstIdFields: List[String], dstIdType: String,
               source: String,
               processType: String,
               processId: String,
               userId: String,
               tableName: Option[String] = None,
               validStartTimeField: Option[(String, String)] = None,
               validEndTimeField: Option[(String, String)] = None,
               deleteIndicatorField: Option[(String, Any)] = None,
               overwrite: Boolean = false): Unit = {

    if (isDelta && overwrite) throw sys.error("isDelta and overwrite options are mutually exclusive")
    val fs = FileSystem.get(new URI(BASE_URI), new Configuration())
    val tn = if (tableName.isDefined) tableName.get else s"${srcEntityType.toLowerCase}_${dstEntityType.toLowerCase}_link"
    val path = s"/$LAYER_ACQUISITION/$tn/$FILE_HISTORY"
    val currentPath = s"$BASE_URI/$LAYER_ACQUISITION/$tn/$FILE_CURRENT"
    val sqlContext = df.sqlContext
    sqlContext.udf.register("hashKey", hashKey(_: String))
    sqlContext.udf.register("convertStringToTimestamp", convertStringToTimestamp(_: String, _: String))

    // dedup
    val distinct = df.distinct()
    distinct.registerTempTable("imported")
    val idCols1 = srcIdFields.map("i." + _).mkString(",")
    val idCols2 = dstIdFields.map("i." + _).mkString(",")
    val (validStartTimeExpr, validEndTimeExpr) =
      if (validStartTimeField.isDefined && validEndTimeField.isDefined) {
        (
          s"convertStringToTimestamp(i.${validStartTimeField.get._1}, '${validStartTimeField.get._2}'",
          s"convertStringToTimestamp(i.${validEndTimeField.get._1}, '${validEndTimeField.get._2}'"
          )
      } else {
        ("current_timestamp()", s"'$META_OPEN_END_DATE_VALUE'")
      }

    val sql =
      s"""
         |select hashKey(concat('$srcIdType',$idCols1)) as $META_SRC_ENTITY_ID
         |,hashKey(concat('$dstIdType',$idCols2)) as $META_DST_ENTITY_ID
         |,'$srcEntityType' as $META_SRC_ENTITY_TYPE
         |,'$dstEntityType' as $META_DST_ENTITY_TYPE
         |,current_timestamp() as $META_START_TIME
         |,'$META_OPEN_END_DATE_VALUE' as $META_END_TIME
         |,'$source' as $META_SOURCE
         |,'$processType' as $META_PROCESS_TYPE
         |,'$processId' as $META_PROCESS_ID
         |,current_date() as $META_PROCESS_DATE
         |,'$userId' as $META_USER_ID
         |,$validStartTimeExpr as $META_VALID_START_TIME
         |,$validEndTimeExpr as $META_VALID_END_TIME
         |,'$RECTYPE_INSERT' as $META_RECTYPE
         |,1 as $META_VERSION
         |from imported i
       """.stripMargin

    val saveMode = if (overwrite) SaveMode.Overwrite else SaveMode.Append
    val now = DateTime.now()

    if (fs.exists(new Path(path))) {
      val existing = sqlContext.read.load(s"$BASE_URI$path")
      existing.registerTempTable("existing")
      val sqlNewLinks =
        s"""
           |$sql
           |left join existing e on e.$META_SRC_ENTITY_ID = hashKey(concat('$srcIdType',$idCols1))
           |and e.$META_DST_ENTITY_ID = hashKey(concat('$dstIdType',$idCols2))
           |where e.$META_SRC_ENTITY_ID is null
         """.stripMargin

      val validStartExpr =
        if (validStartTimeField.isDefined && validEndTimeField.isDefined) {
          validStartTimeExpr
        } else {
          s"e.$META_VALID_START_TIME"
        }

      val selectExisting =
        s"""
           |select e.$META_SRC_ENTITY_ID
           |,e.$META_DST_ENTITY_ID
           |,e.$META_SRC_ENTITY_TYPE
           |,e.$META_DST_ENTITY_TYPE
           |,e.$META_START_TIME
           |,current_timestamp() as $META_END_TIME
           |,'$source' as $META_SOURCE
           |,'$processType' as $META_PROCESS_TYPE
           |,'$processId' as $META_PROCESS_ID
           |,current_date() as $META_PROCESS_DATE
           |,'$userId' as $META_USER_ID
           |,$validStartExpr as $META_VALID_START_TIME
           |,$validEndTimeExpr as $META_VALID_END_TIME
           |,'$RECTYPE_DELETE' as $META_RECTYPE
           |,e.$META_VERSION + 1 as $META_VERSION
           |from existing e
           |inner join
           |(select $META_SRC_ENTITY_ID, $META_DST_ENTITY_ID,
           |max($META_VERSION) as $META_MAX_VERSION
           |from existing
           |group by $META_SRC_ENTITY_ID, $META_DST_ENTITY_ID) e1
           |on e1.$META_SRC_ENTITY_ID = e.$META_SRC_ENTITY_ID
           |and e1.$META_DST_ENTITY_ID = e.$META_DST_ENTITY_ID
           |and e1.$META_VERSION = e.$META_VERSION
         """.stripMargin

      val (all, insertsCount, deletesCount) =
        if (deleteIndicatorField.isDefined) {
          val delIndField = deleteIndicatorField.get._1
          val delIndFieldVal = deleteIndicatorField.get._2.toString
          val delIndFieldLit = if (delIndFieldVal.isNumber) delIndFieldVal else s"'$delIndFieldVal'"

          // inserts
          val inserts = sqlContext.sql(
            s"""
               |$sqlNewLinks
               |and i.$delIndField <> $delIndFieldLit
             """.stripMargin)

          if (overwrite) {
            (inserts, inserts.count(), 0L)
          } else {
            // union deletes
            val deletes = sqlContext.sql(
              s"""
                 |$selectExisting
                 |join imported i on e.$META_SRC_ENTITY_ID = hashKey(concat('$srcIdType',$idCols1))
                 |and e.$META_DST_ENTITY_ID = hashKey(concat('$dstIdType',$idCols2))
                 |where i.$delIndField = $delIndFieldLit
               """.stripMargin)

            (inserts.unionAll(deletes), inserts.count(), deletes.count())
          }
        } else if (!isDelta) {
          // inserts
          val inserts = sqlContext.sql(sqlNewLinks)
          if (overwrite) {
            (inserts, inserts.count(), 0L)
          } else {
            // union deletes
            val deletes = sqlContext.sql(
              s"""
                 |$selectExisting
                 |left join imported i on e.$META_SRC_ENTITY_ID = hashKey(concat('$srcIdType',$idCols1))
                 |and e.$META_DST_ENTITY_ID = hashKey(concat('$dstIdType',$idCols2))
                 |where i.$META_SRC_ENTITY_ID is null
               """.stripMargin)

            (inserts.unionAll(deletes), inserts.count(), deletes.count())
          }
        } else {
          // inserts
          val inserts = sqlContext.sql(sqlNewLinks)
          (inserts, inserts.count(), 0L)
        }

      all.write
        //.partitionBy(META_SRC_ENTITY_TYPE, META_DST_ENTITY_TYPE)
        .mode(saveMode)
        .parquet(s"$BASE_URI$path")

      // write snapshot
      val latest: RDD[Row] = df
        .map(row => ((row.getAs[String](META_SRC_ENTITY_ID), row.getAs[String](META_DST_ENTITY_ID)), row))
        .reduceByKey((a, b) => if (b.getAs[Int](META_VERSION) > a.getAs[Int](META_VERSION)) b else a)
        .map(_._2)

      val latestDF = df.sqlContext.createDataFrame(latest, df.schema)

      latestDF
        .write
        .mode(SaveMode.Overwrite)
        .parquet(currentPath)

      val readCount = df.count()
      writeProcessLog(fs, sqlContext, tn, processId, processType, userId,
        readCount, readCount - distinct.count(), insertsCount,
        0, deletesCount, now, now)

    } else {
      val links = sqlContext.sql(sql).cache()

      val writer = links.write
        //.partitionBy(META_SRC_ENTITY_TYPE, META_DST_ENTITY_TYPE)
        .mode(saveMode)

      writer.parquet(s"$BASE_URI$path")
      writer.parquet(currentPath)
      val readCount = df.count()
      writeProcessLog(fs, sqlContext, tn, processId, processType, userId,
        readCount, readCount - distinct.count(), readCount,
        0, 0, now, now)

      val metadata = Map(
        "entityType1" -> srcEntityType,
        "idFields1" -> srcIdFields,
        "idType1" -> srcIdType,
        "entityType2" -> dstEntityType,
        "idFields2" -> dstIdFields,
        "idType2" -> dstIdType,
        "validStartTimeField" -> validStartTimeField,
        "validEndTimeField" -> validEndTimeField,
        "deleteIndicatorField" -> deleteIndicatorField
      )
      writeMetaFile(fs, tn, metadata)

      links.unpersist()
    }
  }

  def loadMapping(df: DataFrame,
                  isDelta: Boolean,
                  entityType: String,
                  srcIdFields: List[String], srcIdType: String,
                  dstIdFields: List[String], dstIdType: String,
                  confidence: Double,
                  source: String,
                  processType: String,
                  processId: String,
                  userId: String,
                  tableName: Option[String] = None,
                  validStartTimeField: Option[(String, String)] = None,
                  validEndTimeField: Option[(String, String)] = None,
                  deleteIndicatorField: Option[(String, Any)] = None,
                  overwrite: Boolean = false): Unit = {

    if (isDelta && overwrite) throw sys.error("isDelta and overwrite options are mutually exclusive")
    val fs = FileSystem.get(new URI(BASE_URI), new Configuration())
    val tn = if (tableName.isDefined) tableName.get else s"${entityType.toLowerCase}_mapping"
    val path = s"/$LAYER_ACQUISITION/$tn/$FILE_HISTORY"
    val currentPath = s"$BASE_URI/$LAYER_ACQUISITION/$tn/$FILE_CURRENT"
    val sqlContext = df.sqlContext
    sqlContext.udf.register("hashKey", hashKey(_: String))
    sqlContext.udf.register("convertStringToTimestamp", convertStringToTimestamp(_: String, _: String))

    // dedup
    val distinct = df.distinct()
    distinct.registerTempTable("imported")
    val idCols1 = srcIdFields.map("i." + _).mkString(",")
    val idCols2 = dstIdFields.map("i." + _).mkString(",")
    val (validStartTimeExpr, validEndTimeExpr) =
      if (validStartTimeField.isDefined && validEndTimeField.isDefined) {
        (
          s"convertStringToTimestamp(i.${validStartTimeField.get._1}, '${validStartTimeField.get._2}'",
          s"convertStringToTimestamp(i.${validEndTimeField.get._1}, '${validEndTimeField.get._2}'"
          )
      } else {
        ("current_timestamp()", s"'$META_OPEN_END_DATE_VALUE'")
      }

    val sql =
      s"""
         |select hashKey(concat('$srcIdType',$idCols1)) as $META_SRC_ENTITY_ID
         |,hashKey(concat('$dstIdType',$idCols2)) as $META_DST_ENTITY_ID
         |,'$entityType' as $META_ENTITY_TYPE
         |,'$srcIdType' as $META_SRC_ID_TYPE
         |,'$dstIdType' as $META_DST_ID_TYPE
         |,$confidence as $META_CONFIDENCE
         |,current_timestamp() as $META_START_TIME
         |,'$META_OPEN_END_DATE_VALUE' as $META_END_TIME
         |,'$source' as $META_SOURCE
         |,'$processType' as $META_PROCESS_TYPE
         |,'$processId' as $META_PROCESS_ID
         |,current_date() as $META_PROCESS_DATE
         |,'$userId' as $META_USER_ID
         |,$validStartTimeExpr as $META_VALID_START_TIME
         |,$validEndTimeExpr as $META_VALID_END_TIME
         |,'$RECTYPE_INSERT' as $META_RECTYPE
         |,1 as $META_VERSION
         |from imported i
       """.stripMargin

    val saveMode = if (overwrite) SaveMode.Overwrite else SaveMode.Append
    val now = DateTime.now()

    if (fs.exists(new Path(path))) {
      val existing = sqlContext.read.load(s"$BASE_URI$path")
      existing.registerTempTable("existing")
      val sqlNewLinks =
        s"""
           |$sql
           |left join existing e on e.$META_SRC_ENTITY_ID = hashKey(concat('$srcIdType',$idCols1))
           |and e.$META_DST_ENTITY_ID = hashKey(concat('$dstIdType',$idCols2))
           |where e.$META_SRC_ENTITY_ID is null
         """.stripMargin

      val validStartExpr =
        if (validStartTimeField.isDefined && validEndTimeField.isDefined) {
          validStartTimeExpr
        } else {
          s"e.$META_VALID_START_TIME"
        }

      val selectExisting =
        s"""
           |select e.$META_SRC_ENTITY_ID
           |,e.$META_DST_ENTITY_ID
           |,e.$META_ENTITY_TYPE
           |,e.$META_SRC_ID_TYPE
           |,e.$META_DST_ID_TYPE
           |,e.$META_CONFIDENCE
           |,e.$META_START_TIME
           |,current_timestamp() as $META_END_TIME
           |,'$source' as $META_SOURCE
           |,'$processType' as $META_PROCESS_TYPE
           |,'$processId' as $META_PROCESS_ID
           |,current_date() as $META_PROCESS_DATE
           |,'$userId' as $META_USER_ID
           |,$validStartExpr as $META_VALID_START_TIME
           |,$validEndTimeExpr as $META_VALID_END_TIME
           |,'$RECTYPE_DELETE' as $META_RECTYPE
           |,e.$META_VERSION + 1 as $META_VERSION
           |from existing e
           |inner join
           |(select $META_SRC_ENTITY_ID, $META_DST_ENTITY_ID,
           |max($META_VERSION) as $META_MAX_VERSION
           |from existing
           |group by $META_SRC_ENTITY_ID, $META_DST_ENTITY_ID) e1
           |on e1.$META_SRC_ENTITY_ID = e.$META_SRC_ENTITY_ID
           |and e1.$META_DST_ENTITY_ID = e.$META_DST_ENTITY_ID
           |and e1.$META_VERSION = e.$META_VERSION
         """.stripMargin

      val (all, insertsCount, deletesCount) =
        if (deleteIndicatorField.isDefined) {
          val delIndField = deleteIndicatorField.get._1
          val delIndFieldVal = deleteIndicatorField.get._2.toString
          val delIndFieldLit = if (delIndFieldVal.isNumber) delIndFieldVal else s"'$delIndFieldVal'"

          // inserts
          val inserts = sqlContext.sql(
            s"""
               |$sqlNewLinks
               |and i.$delIndField <> $delIndFieldLit
             """.stripMargin)

          if (overwrite) {
            (inserts, inserts.count(), 0L)
          } else {
            // union deletes
            val deletes = sqlContext.sql(
              s"""
                 |$selectExisting
                 |join imported i on e.$META_SRC_ENTITY_ID = hashKey(concat('$srcIdType',$idCols1))
                 |and e.$META_DST_ENTITY_ID = hashKey(concat('$dstIdType',$idCols2))
                 |where i.$delIndField = $delIndFieldLit
               """.stripMargin)

            (inserts.unionAll(deletes), inserts.count(), deletes.count())
          }
        } else if (!isDelta) {
          // inserts
          val inserts = sqlContext.sql(sqlNewLinks)
          if (overwrite) {
            (inserts, inserts.count(), 0L)
          } else {
            // union deletes
            val deletes = sqlContext.sql(
              s"""
                 |$selectExisting
                 |left join imported i on e.$META_SRC_ENTITY_ID = hashKey(concat('$srcIdType',$idCols1))
                 |and e.$META_DST_ENTITY_ID = hashKey(concat('$dstIdType',$idCols2))
                 |where i.e$META_SRC_ENTITY_ID is null
               """.stripMargin)

            (inserts.unionAll(deletes), inserts.count(), deletes.count())
          }
        } else {
          // inserts
          val inserts = sqlContext.sql(sqlNewLinks)
          (inserts, inserts.count(), 0L)
        }

      all.write
        //        .partitionBy(META_SRC_ID_TYPE, META_DST_ID_TYPE)
        .mode(saveMode)
        .parquet(s"$BASE_URI$path")

      // write snapshot
      val latestDF = snapshot(all)

      latestDF
        .write
        .mode(SaveMode.Overwrite)
        .parquet(currentPath)

      val readCount = df.count()
      writeProcessLog(fs, sqlContext, tn, processId, processType, userId,
        readCount, readCount - distinct.count(), insertsCount,
        0, deletesCount, now, now)

    } else {
      val mapping = sqlContext.sql(sql).cache()

      val writer = mapping.write
        //        .partitionBy(META_SRC_ID_TYPE, META_DST_ID_TYPE)
        .mode(saveMode)

      writer.parquet(s"$BASE_URI$path")
      writer.parquet(currentPath)
      val readCount = df.count()
      writeProcessLog(fs, sqlContext, tn, processId, processType, userId,
        readCount, readCount - distinct.count(), readCount,
        0, 0, now, now)

      val metadata = Map(
        "entityType" -> entityType,
        "idFields1" -> srcIdFields,
        "idType1" -> srcIdType,
        "idFields2" -> dstIdFields,
        "idType2" -> dstIdType,
        "validStartTimeField" -> validStartTimeField,
        "validEndTimeField" -> validEndTimeField,
        "deleteIndicatorField" -> deleteIndicatorField
      )
      writeMetaFile(fs, tn, metadata)

      mapping.unpersist()
    }
  }

  /**
    * For tables with a single 'entity_id' only. Not for link or mapping tables!
    *
    * @param df DataFrame
    * @return
    */
  def snapshot(df: DataFrame) = {
    val latest: RDD[Row] = df
      .map(row => (row.getAs[String](META_ENTITY_ID), row))
      .reduceByKey((a, b) => if (b.getAs[Int](META_VERSION) > a.getAs[Int](META_VERSION)) b else a)
      .map(_._2)

    df.sqlContext.createDataFrame(latest, df.schema)
  }

  def readCurrentMapping(sqlContext: SQLContext, entityType: String, tableName: Option[String] = None) = {
    val tn = if (tableName.isDefined) tableName.get else s"${entityType.toLowerCase}_mapping"
    val fs = FileSystem.get(new URI(BASE_URI), new Configuration())
    val currentPath = s"$BASE_URI/$LAYER_ACQUISITION/$tn/$FILE_CURRENT"
    if (fs.exists(new Path(currentPath))) {
      sqlContext.read.load(currentPath)
    } else {
      readMapping(sqlContext, entityType, tableName)
    }
  }

  def readMapping(sqlContext: SQLContext, entityType: String, tableName: Option[String] = None) = {
    val tn = if (tableName.isDefined) tableName.get else s"${entityType.toLowerCase}_mapping"
    val df = sqlContext.read.load(s"$BASE_URI/$LAYER_ACQUISITION/$tn$FILE_EXT")
    val latest: RDD[Row] = df
      .map(row => ((row.getAs[String](META_SRC_ENTITY_ID), row.getAs[String](META_DST_ENTITY_ID)), row))
      .reduceByKey((a, b) => if (b.getAs[Int](META_VERSION) > a.getAs[Int](META_VERSION)) b else a)
      .map(_._2)

    df.sqlContext.createDataFrame(latest, df.schema)
  }

  def writeProcessLog(fs: FileSystem,
                      sqlContext: SQLContext,
                      tableName: String,
                      processId: String,
                      processType: String,
                      userId: String,
                      readCount: Long,
                      duplicatesCount: Long,
                      insertsCount: Long,
                      updatesCount: Long,
                      deletesCount: Long,
                      processTime: DateTime,
                      processDate: DateTime): Unit = {

    val procRow = Row(processId, processType, userId,
      readCount, duplicatesCount, insertsCount, updatesCount, deletesCount,
      new Timestamp(processTime.getMillis), new Date(processDate.getMillis))

    val procRDD = sqlContext.sparkContext.parallelize(Seq(procRow))
    val procDF = sqlContext.createDataFrame(procRDD, procSchema)
    val path = s"$BASE_URI/$LAYER_ACQUISITION/$tableName/$FILE_PROCESS"

    val all = if (fs.exists(new Path(path))) {
      sqlContext.read
        .format("com.databricks.spark.csv")
        .option("header", "true")
        .schema(procSchema)
        .load(path)
        .unionAll(procDF)
    } else {
      procDF
    }
    val sc = sqlContext.sparkContext
    val out = sqlContext.createDataFrame(sc.parallelize(all.collect()), procSchema)

    out.coalesce(1)
      .write
      .format("com.databricks.spark.csv")
      .option("header", "true")
      //        .mode(SaveMode.Append) // not supported
      .mode(SaveMode.Overwrite)
      .save(path)
  }

  def writeMetaFile(fs: FileSystem, tableName: String, metadata: Map[String, Any]): Unit = {
    implicit val formats = org.json4s.DefaultFormats
    val metaJson = Serialization.write(metadata)
    val os = fs.create(new Path(s"$BASE_URI/$LAYER_ACQUISITION/$tableName/$FILE_META"))
    os.write(metaJson.toString.getBytes(Charset.defaultCharset))
    os.flush()
    os.close()
  }

  def writeChangeTable(fs: FileSystem, df: DataFrame, header: List[String], fileName: String, daysAgo: Int): Unit = {
    // remove partitions > daysAgo old
    try {
      removeParts(fs, fileName, daysAgo)
    } catch {
      case _: Throwable => //ignore error
    }
    if (df.count() > 0) {
      df
        .select(header.map(col): _*)
        .write
        .mode(SaveMode.Append)
        .partitionBy(META_PROCESS_DATE)
        .parquet(fileName)
    }
  }

  /**
    * Remove date partitioned files more than daysAgo old.
    *
    * @param fs      Hadoop FileSystem
    * @param uri     String
    * @param daysAgo Int
    */
  def removeParts(fs: FileSystem, uri: String, daysAgo: Int): Unit = {
    val datePattern = """.*(\d{4}-\d{2}-\d{2})$""".r
    val addedPath = new Path(uri)
    val parts = fs.listFiles(addedPath, false)
    while (parts.hasNext) {
      val part = parts.next().getPath
      val date = for (m <- datePattern findFirstMatchIn part.getName) yield m group 1
      val partDate = new LocalDateTime(date)
      if (partDate < LocalDateTime.now - daysAgo.days) {
        fs.delete(new Path(s"$uri/$META_PROCESS_DATE=$date"), true)
      }
    }
  }

}
