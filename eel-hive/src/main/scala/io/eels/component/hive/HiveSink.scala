package io.eels.component.hive

import com.sksamuel.exts.Logging
import com.sksamuel.exts.OptionImplicits._
import com.typesafe.config.{Config, ConfigFactory}
import io.eels.component.hive.partition.{DefaultHivePathStrategy, PartitionPathStrategy}
import io.eels.schema.StructType
import io.eels.{Sink, SinkWriter}
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.permission.FsPermission
import org.apache.hadoop.hive.metastore.{IMetaStoreClient, TableType}
import org.apache.hadoop.security.UserGroupInformation

import scala.math.BigDecimal.RoundingMode
import scala.math.BigDecimal.RoundingMode.RoundingMode

object HiveSink {
  val config: Config = ConfigFactory.load()
  val schemaEvolutionDefault = config.getBoolean("eel.hive.sink.schemaEvolution")
  val dynamicPartitioningDefault = config.getBoolean("eel.hive.sink.dynamicPartitioning")
  val upperCaseAction = config.getString("eel.hive.sink.upper-case-action")
}

case class HiveSink(dbName: String,
                    tableName: String,
                    dynamicPartitioning: Option[Boolean] = None,
                    permission: Option[FsPermission] = None,
                    inheritPermissions: Option[Boolean] = None,
                    principal: Option[String] = None,
                    partitionFields: Seq[String] = Nil,
                    partitionPathStrategy: PartitionPathStrategy = DefaultHivePathStrategy,
                    filenameStrategy: FilenameStrategy = DefaultFilenameStrategy,
                    stagingStrategy: StagingStrategy = DefaultStagingStrategy,
                    evolutionStrategy: EvolutionStrategy = DefaultEvolutionStrategy,
                    alignStrategy: AlignmentStrategy = DefaultAlignmentStrategy,
                    keytabPath: Option[java.nio.file.Path] = None,
                    fileListener: FileListener = FileListener.noop,
                    createTable: Boolean = false,
                    callbacks: Seq[CommitCallback] = Nil,
                    roundingMode: RoundingMode = RoundingMode.UNNECESSARY,
                    metadata: Map[String, String] = Map.empty)
                   (implicit fs: FileSystem, client: IMetaStoreClient) extends Sink with Logging {

  import HiveSink._

  implicit val conf = fs.getConf
  val ops = new HiveOps(client)

  def withCreateTable(createTable: Boolean, partitionFields: Seq[String] = Nil): HiveSink =
    copy(createTable = createTable, partitionFields = partitionFields)

  def withDynamicPartitioning(partitioning: Boolean): HiveSink = copy(dynamicPartitioning = Some(partitioning))
  def withPermission(permission: FsPermission): HiveSink = copy(permission = Option(permission))
  def withInheritPermission(inheritPermissions: Boolean): HiveSink = copy(inheritPermissions = Option(inheritPermissions))
  def withFileListener(listener: FileListener): HiveSink = copy(fileListener = listener)
  def withFilenameStrategy(filenameStrategy: FilenameStrategy): HiveSink = copy(filenameStrategy = filenameStrategy)
  def withPartitionPathStrategy(strategy: PartitionPathStrategy): HiveSink = copy(partitionPathStrategy = strategy)
  def withMetaData(map: Map[String, String]): HiveSink = copy(metadata = map)
  def withRoundingMode(mode: RoundingMode): HiveSink = copy(roundingMode = mode)
  def withStagingStrategy(strategy: StagingStrategy): HiveSink = copy(stagingStrategy = strategy)
  def withEvolutionStrategy(strategy: EvolutionStrategy): HiveSink = copy(evolutionStrategy = strategy)
  def withAlignmentStrategy(strategy: AlignmentStrategy): HiveSink = copy(alignStrategy = strategy)

  /**
    * Add a callback that will be invoked when commit operations are taking place.
    */
  def addCommitCallback(callback: CommitCallback): HiveSink = copy(callbacks = callbacks :+ callback)

  def withKeytabFile(principal: String, keytabPath: java.nio.file.Path): HiveSink = {
    login()
    copy(principal = principal.some, keytabPath = keytabPath.option)
  }

  private def dialect(): HiveDialect = {
    login()
    val format = ops.tableFormat(dbName, tableName)
    logger.debug(s"Table format is $format; detecting dialect...")
    io.eels.component.hive.HiveDialect(format)
  }

  private def login(): Unit = {
    for (user <- principal; path <- keytabPath) {
      UserGroupInformation.loginUserFromKeytab(user, path.toString)
    }
  }

  def containsUpperCase(schema: StructType): Boolean = schema.fieldNames().exists(name => name.exists(Character.isUpperCase))

  override def open(schema: StructType, n: Int): Seq[SinkWriter] = List.tabulate(n) { k => open(schema, Some(k.toString)) }
  override def open(schema: StructType): SinkWriter = open(schema, None)

  def open(schema: StructType, discriminator: Option[String]): SinkWriter = {
    login()

    if (containsUpperCase(schema)) {
      upperCaseAction match {
        case "error" =>
          sys.error("Writing to hive with a schema that contains upper case characters is discouraged because Hive will lowercase the fields, which could lead to subtle case sensitivity bugs. " +
            "It is recommended that you lower case the schema before writing (eg, datastream.withLowerCaseSchema). " +
            "To disable this exception, set eel.hive.sink.upper-case-action=warn or eel.hive.sink.upper-case-action=none")
        case "warn" =>
          logger.warn("Writing to hive with a schema that contains upper case characters is discouraged because Hive will lowercase the fields, which could lead to subtle case sensitivity bugs. " +
            "It is recommended that you lower case the schema before writing (eg, datastream.withLowerCaseSchema). " +
          "To disable this warning, set eel.hive.sink.upper-case-action=none")
        case _ =>
      }
    }

    if (createTable) {
      if (!ops.tableExists(dbName, tableName)) {
        ops.createTable(dbName, tableName, schema,
          partitionKeys = schema.partitions.map(_.name.toLowerCase) ++ partitionFields,
          format = HiveFormat.Parquet,
          props = Map.empty,
          tableType = TableType.MANAGED_TABLE
        )
      }
    }

    val metastoreSchema = ops.schema(dbName, tableName)
    logger.trace("Metastore schema" + metastoreSchema)

    // call the evolve method on the evolution strategy to ensure the metastore is good to go
    logger.debug("Invoking evolution strategy to align metastore schema")
    evolutionStrategy.evolve(dbName, tableName, metastoreSchema, schema, client)

    new HiveSinkWriter(
      schema,
      metastoreSchema,
      dbName,
      tableName,
      discriminator,
      dialect(),
      dynamicPartitioning.contains(true) || dynamicPartitioningDefault,
      partitionPathStrategy,
      filenameStrategy,
      stagingStrategy,
      evolutionStrategy,
      alignStrategy,
      inheritPermissions,
      permission,
      fileListener,
      callbacks,
      roundingMode,
      metadata
    )
  }
}