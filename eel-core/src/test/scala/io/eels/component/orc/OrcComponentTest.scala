package io.eels.component.orc

import io.eels.Frame
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path, FileSystem}
import org.scalatest.{Matchers, WordSpec}

class OrcComponentTest extends WordSpec with Matchers {

  "OrcComponent" should {
    "read and write orc files" in {

      implicit val fs = FileSystem.get(new Configuration)

      val frame = Frame(
        List("name", "job", "location"),
        List("clint eastwood", "actor", "carmel"),
        List("elton john", "musician", "pinner"),
        List("david bowie", "musician", "surrey")
      )

      val path = new Path("test.orc")
      frame.to(OrcSink(path)).run

      val rows = OrcSource(path).toSeq.run
      fs.delete(path, false)

      rows shouldBe Seq(
        List("clint eastwood", "actor", "carmel"),
        List("elton john", "musician", "pinner"),
        List("david bowie", "musician", "surrey")
      )

    }
  }
}
