package io.eels.component.parquet

import com.sksamuel.exts.Logging
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path

/**
  * Will write io.eel Rows out to a given path using an underlying apache parquet writer.
  */
class ParquetRowWriter(path: Path,
                       avroSchema: Schema)(implicit fs: FileSystem) extends Logging {

  val config: Config = ConfigFactory.load()
  val skipCrc = config.getBoolean("eel.parquet.skipCrc")
  logger.info(s"Parquet writer will skipCrc = $this")

  private val writer = ParquetWriterFn.apply(path, avroSchema)

  def write(record: GenericRecord): Unit = {
    writer.write(record)
  }

  def close(): Unit = {
    writer.close()
    if (skipCrc) {
      val crc = new Path("." + path.toString() + ".crc")
      logger.debug("Deleting crc $crc")
      if (fs.exists(crc))
        fs.delete(crc, false)
    }
  }
}
