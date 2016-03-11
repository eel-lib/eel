package io.eels.component.parquet

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.slf4j.StrictLogging
import io.eels.SchemaType
import org.apache.avro.{Schema, SchemaBuilder}
import org.apache.avro.generic.GenericRecord
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.{AvroParquetReader, AvroReadSupport}
import org.apache.parquet.hadoop.ParquetReader

object ParquetReaderSupport extends StrictLogging {

  val config = ConfigFactory.load()

  lazy val parallelism = {
    val parallelism = config.getInt("eel.parquet.parallelism")
    logger.debug(s"Creating parquet reader with parallelism = $parallelism")
    parallelism.toString
  }

  def createReader(path: Path, columns: Seq[String], schema: io.eels.Schema): ParquetReader[GenericRecord] = {
    require(columns.isEmpty || schema != null, "If pushdown columns are specified, the schema must be available")

    def projection: Schema = {
      val builder = SchemaBuilder.record("dummy").namespace("com")
      columns.foldLeft(builder.fields) { (fields, name) =>
        val schemaType = schema(name).`type`
        schemaType match {
          case SchemaType.BigInt => fields.optionalLong(name)
          case SchemaType.Boolean => fields.optionalBoolean(name)
          case SchemaType.Double => fields.optionalDouble(name)
          case SchemaType.Float => fields.optionalFloat(name)
          case SchemaType.Int => fields.optionalInt(name)
          case SchemaType.Long => fields.optionalLong(name)
          case SchemaType.String => fields.optionalString(name)
          case SchemaType.Short => fields.optionalInt(name)
          case _ =>
            logger.warn(s"Unknown schema type $schemaType; default to string")
            fields.optionalString(name)
        }
      }.endRecord()
    }

    def configuration: Configuration = {
      val conf = new Configuration
      if (columns.nonEmpty) {
        AvroReadSupport.setAvroReadSchema(conf, projection)
        AvroReadSupport.setRequestedProjection(conf, projection)
        conf.set(org.apache.parquet.hadoop.ParquetFileReader.PARQUET_READ_PARALLELISM, parallelism)
      }
      conf
    }

    AvroParquetReader.builder[GenericRecord](path)
      .withConf(configuration)
      .build().asInstanceOf[ParquetReader[GenericRecord]]
  }
}
