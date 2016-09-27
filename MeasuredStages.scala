package streams

import akka.stream.Attributes
import akka.stream.stage.{ GraphStageLogic, InHandler, OutHandler }
import akka.stream.{ BidiShape, Inlet, Outlet }
import akka.stream.scaladsl.{ Flow, Source }
import akka.stream.stage.GraphStage
import nl.grons.metrics.scala.Timer
import com.codahale.metrics.{ Timer => DropwizardTimer }

object MeasuredStages {
  def maybeMeasuredFlow[In, Out, Mat](readTimer: Option[Timer], writeTimer: Option[Timer])(
    flow: Flow[In, Out, Mat]) =
    (readTimer, writeTimer) match {
      case (None, None) => flow
      case otherwise    => MeasuredFlow(readTimer, writeTimer)(flow)
    }

  def maybeMeasuredSource[Out, Mat](timer: Option[Timer])(source: Source[Out, Mat]) =
    timer match {
      case Some(t) => MeasuredSource(t)(source)
      case None    => source
    }

  def MeasuredSource[Out, Mat](timer: Timer)(source: Source[Out, Mat]): Source[Out, Mat] =
    source.statefulMapConcat(() => {
      var currentTimerContext: Option[DropwizardTimer.Context] = None

      el =>
        {
          currentTimerContext.foreach(_.close())
          currentTimerContext = Some(timer.timerContext())

          List(el)
        }
    })

  object MeasuredFlow {
    def apply[In, Out, Mat](readTimer: Option[Timer], writeTimer: Option[Timer])(
      flow: Flow[In, Out, Mat]): Flow[In, Out, Mat] =
      flow.join(new MeasuredFlow[Out, In](readTimer, writeTimer))
  }

  class MeasuredFlow[In, Out](readTimer: Option[Timer], writeTimer: Option[Timer])
      extends GraphStage[BidiShape[In, In, Out, Out]] {
    val fromOut = Inlet[In]("MeasuredFlow.fromOut")
    val toFlow = Outlet[In]("MeasuredFlow.toFlow")
    val fromFlow = Inlet[Out]("MeasuredFlow.fromFlow")
    val toOut = Outlet[Out]("MeasuredFlow.toOut")

    override val shape = BidiShape(fromOut, toFlow, fromFlow, toOut)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        var currentReadTimerContext: Option[DropwizardTimer.Context] = None
        var currentWriteTimerContext: Option[DropwizardTimer.Context] = None

        def markRead(): Unit = {
          currentReadTimerContext.foreach(_.close())
          currentReadTimerContext = readTimer.map(_.timerContext())
        }

        def markWrite(): Unit = {
          currentWriteTimerContext.foreach(_.close())
          currentWriteTimerContext = writeTimer.map(_.timerContext())
        }

        override def postStop(): Unit = {
          currentReadTimerContext.foreach(_.close())
          currentWriteTimerContext.foreach(_.close())
        }

        setHandler(fromOut,
          new InHandler {
          override def onPush(): Unit = {
            if (isAvailable(toFlow)) {
              markRead()

              push(toFlow, grab(fromOut))
              pull(fromOut)
            }
          }
        })

        setHandler(toFlow,
          new OutHandler {
          override def onPull(): Unit = {
            if (isAvailable(fromOut)) {
              markRead()

              push(toFlow, grab(fromOut))
            }

            if (!hasBeenPulled(fromOut))
              pull(fromOut)
          }
        })

        setHandler(fromFlow,
          new InHandler {
          override def onPush(): Unit = {
            if (isAvailable(toOut)) {
              markWrite()

              push(toOut, grab(fromFlow))
              pull(fromFlow)
            }
          }
        })

        setHandler(toOut,
          new OutHandler {
          override def onPull(): Unit = {
            if (isAvailable(fromFlow)) {
              markWrite()
              push(toOut, grab(fromFlow))
            }

            if (!hasBeenPulled(fromFlow))
              pull(fromFlow)
          }
        })
      }
  }

}
