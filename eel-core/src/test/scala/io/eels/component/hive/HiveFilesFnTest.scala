package io.eels.component.hive

import io.eels.Frame
import io.eels.schema.Schema
import io.eels.testkit.HiveTestKit
import org.scalatest.{Matchers, WordSpec}

import scala.reflect.internal.util.TableDef.Column

class HiveFilesFnTest extends WordSpec with Matchers with HiveTestKit {

  import scala.concurrent.ExecutionContext.Implicits.global

  val data = Seq.fill(100000)(Seq(Seq("1", "2"), Seq("2", "3"), Seq("3", "4"))).flatten

  "HiveFilesFn" should {
    "scan table root on non partitioned table" in {

      val schema = Schema("a", "b")
      HiveOps.createTable("sam", "hivefilesfn1", schema, format = HiveFormat.Parquet)
      Frame(schema, data).to(HiveSink("sam", "hivefilesfn1").withIOThreads(2))

      val table = client.getTable("sam", "hivefilesfn1")

      // we should have 2 files, one per thread, as data is written straight to the table root
      HiveFilesFn(table, Nil).size shouldBe 2
    }
    "scan partition paths for partitioned table" in {

      val schema = Schema("a", "b")
      HiveOps.createTable("sam", "hivefilesfn2", schema, format = HiveFormat.Parquet, partitionKeys = List("a"))
      Frame(schema, data).to(HiveSink("sam", "hivefilesfn2").withIOThreads(2))

      val table = client.getTable("sam", "hivefilesfn2")

      // we should have 6 files, 3 partition values, and then 2 files for each partition (2 threads)
      HiveFilesFn(table, Nil).size shouldBe 6
    }
  }
}
