package io.eels.component.json

import com.fasterxml.jackson.databind.ObjectMapper
import io.eels.{Row, FrameSchema, Sink, Writer}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}

case class JsonSink(path: Path) extends Sink {

  import scala.collection.JavaConverters._

  override def writer: Writer = new Writer {

    val fs = FileSystem.get(new Configuration)
    val mapper = new ObjectMapper

    val out = fs.create(path)

    override def close(): Unit = out.close()

    override def write(row: Row, schema: FrameSchema): Unit = {
      val map = schema.columnNames.zip(row).toMap.asJava
      val json = mapper.writeValueAsString(map)
      out.writeBytes(json)
      out.writeBytes("\n")
    }
  }
}
