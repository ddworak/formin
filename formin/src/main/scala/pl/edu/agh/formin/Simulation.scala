package pl.edu.agh.formin

import java.io.File

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import com.typesafe.scalalogging.LazyLogging
import pl.edu.agh.formin.algorithm.ForminMovesController
import pl.edu.agh.formin.config.{ForminConfig}
import pl.edu.agh.formin.model.parallel.ForminConflictResolver
import pl.edu.agh.xinuk.config.GuiType
import pl.edu.agh.xinuk.gui.GuiActor
import pl.edu.agh.xinuk.model.WorkerId
import pl.edu.agh.xinuk.model.parallel.{Neighbour, NeighbourPosition}
import pl.edu.agh.xinuk.simulation.WorkerActor

import scala.util.{Failure, Success, Try}

object Simulation extends LazyLogging {
  final val ForminConfigPrefix = "formin"

  private val rawConfig: Config =
    Try(ConfigFactory.parseFile(new File("formin.conf")))
      .filter(_.hasPath(ForminConfigPrefix))
      .getOrElse {
        logger.info("Falling back to reference.conf")
        ConfigFactory.empty()
      }.withFallback(ConfigFactory.load("cluster.conf"))
      .withFallback(ConfigFactory.load())

  implicit val config: ForminConfig = {
    val forminConfig = rawConfig.getConfig(ForminConfigPrefix)
    logger.info(WorkerActor.MetricsMarker, forminConfig.root().render(ConfigRenderOptions.concise()))
    logger.info(WorkerActor.MetricsMarker, "worker:foraminiferaCount;algaeCount;foraminiferaDeaths;foraminiferaTotalEnergy;foraminiferaReproductionsCount;consumedAlgaeCount;foraminiferaTotalLifespan;algaeTotalLifespan")
    ForminConfig.fromConfig(forminConfig) match {
      case Success(parsedConfig) =>
        parsedConfig
      case Failure(parsingError) =>
        logger.error("Config parsing error.", parsingError)
        System.exit(2)
        throw new IllegalArgumentException
    }
  }

  private val system = ActorSystem(rawConfig.getString("application.name"), rawConfig)

  private val workerProps: Props = WorkerActor.props[ForminConfig]((bufferZone, config) =>
    new ForminMovesController(bufferZone)(config), ForminConflictResolver
  )

  ClusterSharding(system).start(
    typeName = WorkerActor.Name,
    entityProps = workerProps,
    settings = ClusterShardingSettings(system),
    extractShardId = WorkerActor.extractShardId,
    extractEntityId = WorkerActor.extractEntityId
  )

  private val WorkerRegionRef: ActorRef = ClusterSharding(system).shardRegion(WorkerActor.Name)

  def main(args: Array[String]): Unit = {
    if (config.isSupervisor) {

      val workers: Vector[WorkerId] =
        (1 to math.pow(config.workersRoot, 2).toInt)
          .map(WorkerId)(collection.breakOut)

      workers.foreach { id =>
        if (config.guiType != GuiType.None) {
          system.actorOf(GuiActor.props(WorkerRegionRef, id))
        }
        val neighbours: Vector[Neighbour] = NeighbourPosition.values.flatMap { pos =>
          pos.neighbourId(id).map(_ => Neighbour(pos))
        }(collection.breakOut)
        WorkerRegionRef ! WorkerActor.NeighboursInitialized(id, neighbours, WorkerRegionRef)
      }
    }
  }

}

