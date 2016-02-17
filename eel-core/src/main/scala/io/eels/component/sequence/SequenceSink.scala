package io.eels.component.sequence

import java.io.StringWriter

import com.github.tototoshi.csv.CSVWriter
import io.eels.{FrameSchema, Row, Sink, Writer}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{BytesWritable, IntWritable, SequenceFile}

case class SequenceSink(path: Path) extends Sink {

  override def writer: Writer = new Writer {

    val writer = SequenceFile.createWriter(new Configuration,
      SequenceFile.Writer.file(path),
      SequenceFile.Writer.keyClass(classOf[IntWritable]),
      SequenceFile.Writer.valueClass(classOf[BytesWritable])
    )

    var _writtenHeader = false
    val key = new IntWritable(0)

    def writeHeader(schema: FrameSchema): Unit = {
      val csv = valuesToCsv(schema.columnNames)
      writer.append(key, new BytesWritable(csv.getBytes("UTF8")))
      _writtenHeader = true
    }

    def valuesToCsv(values: Seq[Any]): String = {
      val swriter = new StringWriter
      val csv = CSVWriter.open(swriter)
      csv.writeRow(values)
      csv.close()
      swriter.toString.trim
    }

    override def close(): Unit = writer.close()
    override def write(row: Row, schema: FrameSchema): Unit = {
      if (!_writtenHeader) writeHeader(schema)
      val csv = valuesToCsv(row)
      writer.append(key, new BytesWritable(csv.getBytes("UTF8")))
      key.set(key.get + 1)
    }
  }
}
