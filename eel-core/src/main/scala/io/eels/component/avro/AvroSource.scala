package io.eels.component.avro

import java.nio.file.Path

import com.sksamuel.scalax.io.Using
import io.eels.{Row, FrameSchema, Reader, Source}
import org.apache.avro.file.{DataFileReader, SeekableFileInput}
import org.apache.avro.generic.{GenericDatumReader, GenericRecord}

import scala.collection.JavaConverters._

case class AvroSource(path: Path) extends Source with Using {

  def createReader: DataFileReader[GenericRecord] = {
    val datumReader = new GenericDatumReader[GenericRecord]()
    new DataFileReader[GenericRecord](new SeekableFileInput(path.toFile), datumReader)
  }

  override def schema: FrameSchema = {
    using(createReader) { reader =>
      val record = reader.next()
      val columns = record.getSchema.getFields.asScala.map(_.name)
      FrameSchema(columns)
    }
  }
  override def readers: Seq[Reader] = {

    val reader = new Reader {

      val reader = createReader

      override def close(): Unit = reader.close()

      override def iterator: Iterator[Row] = new Iterator[Row] {

        override def hasNext: Boolean = {
          val hasNext = reader.hasNext
          if (!hasNext)
            reader.close()
          hasNext
        }

        override def next: Row = AvroRecordFn.fromRecord(reader.next).map(_.toString)
      }
    }

    Seq(reader)
  }
}
