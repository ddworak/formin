package pl.edu.agh.xinuk.gui

import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.{ImageIcon, UIManager}

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import org.jfree.chart.plot.PlotOrientation
import org.jfree.chart.{ChartFactory, ChartPanel}
import org.jfree.data.xy.{XYSeries, XYSeriesCollection}
import pl.edu.agh.xinuk.config.XinukConfig
import pl.edu.agh.xinuk.gui.GuiActor.{GridInfo}
import pl.edu.agh.xinuk.model.Grid.CellArray
import pl.edu.agh.xinuk.model._
import pl.edu.agh.xinuk.simulation.Metrics
import pl.edu.agh.xinuk.simulation.WorkerActor._

import scala.collection.mutable
import scala.swing.BorderPanel.Position._
import scala.swing.TabbedPane.Page
import scala.swing._
import scala.util.{Random, Try}

class GuiActor private(worker: ActorRef, workerId: WorkerId)(
  implicit config: XinukConfig) extends Actor with ActorLogging {

  override def receive: Receive = started

  private lazy val gui: GuiGrid = new GuiGrid(config.gridSize)

  override def preStart: Unit = {
    worker ! SubscribeGUI(workerId)
    log.info("GUI started")
  }

  def started: Receive = {
    case GridInfo(iteration, grid, metrics) =>
      gui.setNewValues(iteration, grid)
      gui.updatePlot(iteration, metrics)
  }
}

object GuiActor {

  final case class GridInfo private(iteration: Long, grid: Grid, metrics: Metrics)

  def props(worker: ActorRef, workerId: WorkerId)
           (implicit config: XinukConfig): Props = {
    Props(new GuiActor(worker, workerId))
  }

}

private[gui] class GuiGrid(dimension: Int)(implicit config: XinukConfig) extends SimpleSwingApplication {

  Try(UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName))

  private val bgcolor = new Color(220, 220, 220)
  private val cellView = new ParticleCanvas(dimension, config.guiCellSize)
  private val chartPanel = new BorderPanel {
    background = bgcolor
  }
  private val chartPage = new Page("Plot", chartPanel)
  private val workersView = new Table(Array.tabulate(config.workersRoot * config.workersRoot)(id =>
    Array[Any](id + 1, 0)), Seq("Worker", "Iteration")
  )
  private val workersPanel = new BorderPanel {
    background = bgcolor
    layout(new ScrollPane(workersView)) = Center
  }

  def top = new MainFrame {
    title = "Formin model"
    background = bgcolor

    val mainPanel = new BorderPanel {

      val cellPanel = new BorderPanel {
        val view = new BorderPanel {
          background = bgcolor
          layout(cellView) = Center
        }
        background = bgcolor
        layout(view) = Center
      }

      val contentPane = new TabbedPane {
        pages += new Page("Cells", cellPanel)
        pages += chartPage
        pages += new Page("Workers", workersPanel)
      }

      layout(contentPane) = Center
    }

    contents = mainPanel
  }

  def setNewValues(iteration: Long, grid: Grid): Unit = {
    cellView.set(grid.cells.transpose)
  }

  def setWorkerIteration(workerId: Int, iteration: Long): Unit = {
    workersView.update(workerId - 1, 1, iteration)
  }

  sealed trait CellArraySettable extends Component {
    def set(cells: CellArray): Unit
  }

  private class ParticleCanvas(dimension: Int, guiCellSize: Int) extends Label with CellArraySettable {
    private val obstacleColor = new swing.Color(0, 0, 0)
    private val bufferColor = new swing.Color(163, 163, 194)
    private val emptyColor = new swing.Color(255, 255, 255)
    private val img = new BufferedImage(dimension * guiCellSize, dimension * guiCellSize, BufferedImage.TYPE_INT_ARGB)

    icon = new ImageIcon(img)

    private val classToColor = mutable.Map[Class[_], Color](
      Obstacle.getClass -> obstacleColor,
      BufferCell.getClass -> bufferColor,
      EmptyCell.getClass -> emptyColor,
    )

    def set(cells: CellArray): Unit = {
      def generateColor(clazz: Class[_]): Color = {
        val random = new Random(clazz.hashCode())
        val hue = random.nextFloat()
        val saturation = 0.9f
        val luminance = 1.0f
        Color.getHSBColor(hue, saturation, luminance)
      }

      val rgbArray = cells.map(_.map(cell =>
        classToColor.getOrElseUpdate(cell.getClass, generateColor(cell.getClass)))
      )

      for {
        x <- cells.indices
        y <- cells.indices
      } {
        val startX = x * guiCellSize
        val startY = y * guiCellSize
        img.setRGB(startX, startY, guiCellSize, guiCellSize, Array.fill(guiCellSize * guiCellSize)(rgbArray(x)(y).getRGB), 0, guiCellSize)
      }
      this.repaint()
    }
  }

  private val nameToSeries = mutable.Map.empty[String, XYSeries]
  private val dataset = new XYSeriesCollection()
  private val chart = ChartFactory.createXYLineChart(
    "Iteration metrics chart", "X", "Y size", dataset, PlotOrientation.VERTICAL, true, true, false
  )
  private val panel = new ChartPanel(chart)
  chartPanel.layout(swing.Component.wrap(panel)) = Center

  def updatePlot(iteration: Long, metrics: Metrics): Unit = {
    def createSeries(name: String): XYSeries = {
      val series = new XYSeries(name)
      series.setMaximumItemCount(GuiGrid.MaximumPlotSize)
      dataset.addSeries(series)
      series
    }

    metrics.series.foreach { case (name, value) =>
      nameToSeries.getOrElseUpdate(name, createSeries(name)).add(iteration, value)
    }
  }

  main(Array.empty)

}

object GuiGrid {
  final val MaximumPlotSize = 400
}

