package streams

import java.util.concurrent.atomic.AtomicReference

import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream.Supervision.{ Restart, Resume, Stop }
import akka.stream._
import akka.stream.stage._
import RetryableMapAsync.{ RetryableFailure, RetryPolicy, GiveupRetrying }

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

object RetryableMapAsync {
  case class RetryPolicy(rate: FiniteDuration, giveup: FiniteDuration, fuzziness: FiniteDuration)
  case class GiveupRetrying(cause: Throwable, attempts: Int, duration: FiniteDuration)
      extends Exception(s"Gave up retrying after ${ attempts } attempts, ${ duration.toCoarsest }",
                        cause)
  case class RetryableFailure(attempt: Int, firstAttemptTime: Long, cause: Throwable)
      extends Exception(cause)

  def apply[In, Out](policy: RetryPolicy)(f: In => Future[Out]) =
    new RetryableMapAsync(policy, f)

  object Attempt {
    def initial = Attempt(1, System.currentTimeMillis)
  }

  case class Attempt(count: Int, firstAttemptTime: Long) {
    def next = copy(count = count + 1)
  }


  sealed trait ProcessingState[-In, +Out]
  case object WaitingForInput extends ProcessingState[Any, Nothing]
  case class Processing[In, Out](input: In, future: Future[Out], attempt: Attempt)
      extends ProcessingState[In, Out]
  case class WaitingForRetry[In](input: In, attempt: Attempt) extends ProcessingState[In, Nothing]
  case class Done[In, Out](input: In, result: Out) extends ProcessingState[In, Out]
}

class RetryableMapAsync[In, Out](policy: RetryPolicy, f: In => Future[Out])
    extends GraphStageWithMaterializedValue[FlowShape[In, Out],
      AtomicReference[Option[RetryableFailure]]] {
  import RetryableMapAsync._

  val in = Inlet[In]("RetryableMapAsync.in")
  val out = Outlet[Out]("RetryableMapAsync.out")

  override def shape: FlowShape[In, Out] = FlowShape(in, out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes)
    : (GraphStageLogic, AtomicReference[Option[RetryableFailure]]) = {
    val currentFailure = new AtomicReference[Option[RetryableFailure]](None)
    val logic: TimerGraphStageLogic with InHandler with OutHandler =
      new TimerGraphStageLogic(shape) with InHandler with OutHandler { graphLogic =>
        setHandler(in, graphLogic)
        setHandler(out, graphLogic)

        val decider = inheritedAttributes
          .get[SupervisionStrategy](SupervisionStrategy(Supervision.stoppingDecider))
          .decider

        @volatile var processingState: ProcessingState[In, Out] = WaitingForInput

        var futureCB: AsyncCallback[Try[(In, Out)]] = getAsyncCallback[Try[(In, Out)]] { result =>
          (result, processingState) match {
            case (Success((input, result)), Processing(_, _, attempt)) =>
              if (attempt.count > 1) {
                // log.info(
                //   "Failure cleared",
                //   "failure_count"    -> (attempt.count - 1),
                //   "failure_duration" -> (System.currentTimeMillis - attempt.firstAttemptTime)
                // )
                currentFailure.set(None)
              }

              if (isAvailable(out))
                pushResult(result)
              else
                processingState = Done(input, result)

            case (Failure(e), Processing(_, _, _)) =>
              handleFailure(e)

            case otherwise =>
              // log.error("Illegal state in futureCB", "state" -> processingState)
              failStage(
                  new IllegalStateException(
                      s"Illegal state in futureCB; state = ${ processingState }"))
          }
        }

        override def onPush(): Unit = processingState match {
          case WaitingForInput =>
            val input = grab(in)
            runProcessing(input, Attempt.initial)

          case Processing(_, _, _) | WaitingForRetry(_, _) | Done(_, _) =>
          // do nothing - still processing
        }

        override def onPull(): Unit = processingState match {
          case WaitingForInput =>
            if (!hasBeenPulled(in)) {
              pull(in)
            }

          case Processing(_, _, _) | WaitingForRetry(_, _) =>
          // do nothing - still processing

          case Done(_, result) =>
            pushResult(result)
        }

        override def onTimer(timerKey: Any): Unit = (timerKey, processingState) match {
          case (a: Attempt, WaitingForRetry(input, storedAttempt)) if a == storedAttempt =>
            // Scala 'forgets' that the type of input here is In, for some reason...
            runProcessing(input.asInstanceOf[In], storedAttempt)

          case otherwise =>
            // log.error(
            //   "Illegal state in onTimer",
            //   "state"        -> processingState,
            //   "received_key" -> timerKey
            // )

            failStage(new IllegalStateException(
                  s"Illegal state in onTimer; state = ${ processingState }, timerKey = ${ timerKey }"))
        }

        override def onUpstreamFinish(): Unit = processingState match {
          case WaitingForInput                                          => completeStage()
          case Processing(_, _, _) | WaitingForRetry(_, _) | Done(_, _) => // suppress completion
        }

        def runProcessing(input: In, attempt: Attempt): Unit = {
          val future =
            try {
              f(input)
            } catch {
              case NonFatal(e) => Future.failed(e)
            }

          future
            .map((input, _))(materializer.executionContext)
            .onComplete(futureCB.invoke)(materializer.executionContext)
          processingState = Processing(input, future, attempt)
        }

        def pushResult(result: Out): Unit = {
          push(out, result)
          processingState = WaitingForInput

          if (isClosed(in))
            completeStage()
          else
            pull(in)
        }

        def restartState(): Unit = {
          processingState = WaitingForInput

          if (isAvailable(in)) {
            val input = grab(in)
            runProcessing(input, Attempt.initial)
          } else {
            if (!hasBeenPulled(in))
              pull(in)
          }
        }

        def handleFailure(failure: Throwable): Unit = processingState match {
          case Processing(input, _, attempt @ Attempt(failureCount, startTime))
              if (System.currentTimeMillis() - startTime).millis < policy.giveup =>
            val delay = policy.rate * failureCount + policy.fuzziness
            val ttl =
              (policy.giveup.toMillis - (System.currentTimeMillis - startTime)).millis
            val e = RetryableFailure(failureCount, startTime, failure)

            // log.error(
            //   e,
            //   s"Failure #${ failureCount } while processing ${ input.getClass.getSimpleName }; " +
            //     s"retrying in ${ delay.toCoarsest.toString }, giving up in ${ ttl.toCoarsest.toString }",
            //   "failure_count"    -> failureCount,
            //   "failure_duration" -> (System.currentTimeMillis - startTime)
            // )

            currentFailure.set(Some(e))

            scheduleOnce(attempt.next, delay)

            processingState = WaitingForRetry(input, attempt.next)

          case Processing(input, _, Attempt(failureCount, startTime))
              if (System.currentTimeMillis() - startTime).millis >= policy.giveup =>
            val failureDuration = (System.currentTimeMillis() - startTime).millis

            // log.error(
            //   failure,
            //   s"Failure #${ failureCount } while processing ${ input.getClass.getSimpleName };" +
            //     s"giving up after ${ (System.currentTimeMillis() - startTime).millis.toCoarsest.toString }",
            //   "failure_count"    -> failureCount,
            //   "failure_duration" -> (System.currentTimeMillis - startTime)
            // )

            val finalFailure = GiveupRetrying(failure, failureCount, failureDuration)

            decider(finalFailure) match {
              case Stop             => failStage(finalFailure)
              case Resume | Restart => restartState()
            }
        }
      }

    (logic, currentFailure)
  }

}
