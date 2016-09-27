package streams

import akka.actor.{ Actor, ActorSystem, Props }
import akka.stream.{ ActorAttributes, ActorMaterializer }
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.{ ActorMaterializerSettings, Supervision }
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._

object SimpleErrorHandling {
  def main(args: Array[String]): Unit = {
    val failing = Source(Stream.from(0))
      .map(el => if (el % 3 == 0) el / 0 else el)
      .take(100)
      .toMat(Sink.fold(0)(_ + _))(Keep.right)

    val decider: Supervision.Decider = {
      case e =>
        println(s"Ouch! ${e}")
        Supervision.Resume // Or Supervision.Restart/Stop
    }

    implicit val system = ActorSystem()
    import system.dispatcher

    val settings = ActorMaterializerSettings(system)
      .withSupervisionStrategy(decider)
    implicit val mat = ActorMaterializer(settings)

    failing.run() onSuccess {
      case v =>
        println(s"Result: ${v}")
        system.terminate
    }
  }
}

object NoEscalations {
  def main(args: Array[String]): Unit = {
    val parentDecider: Supervision.Decider = {
      case e =>
        println(s"In parent decider: ${e}")
        Supervision.Resume // Or Supervision.Restart/Stop
    }

    val nestedDecider: Supervision.Decider = {
      case e =>
        println(s"In nested decider: ${e}")
        Supervision.Resume // Or Supervision.Restart/Stop
    }

    val mapStage = Flow[Int] map { el =>
      if (el % 3 == 0) el / 0 else el
    } withAttributes ActorAttributes.supervisionStrategy(nestedDecider)

    val failing = Source(Stream.from(0))
      .via(mapStage)
      .take(100)
      .toMat(Sink.fold(0)(_ + _))(Keep.right)

    implicit val system = ActorSystem()
    import system.dispatcher

    val settings = ActorMaterializerSettings(system)
      .withSupervisionStrategy(parentDecider)
    implicit val mat = ActorMaterializer(settings)

    failing.run() onComplete {
      case v =>
        println(s"Result: ${v}")
        system.terminate
    }
  }
}

object ErrorHandlingWithActors {
  case class StreamError(e: Throwable)

  class StreamActor extends Actor {
    val decider: Supervision.Decider = {
      case e =>
        self ! StreamError(e)
        Supervision.Stop
    }
    
    val settings = ActorMaterializerSettings(context.system)
      .withSupervisionStrategy(decider)

    implicit val mat = ActorMaterializer(settings)

    Source(Stream.from(1))
      .delay(1.second)
      .map { el =>
        if (el % 5 == 0) el / 0
        else {
          println(el)
          el
        }
      }
      .take(100)
      .runWith(Sink.ignore)

    override def preStart(): Unit = {
      println("Actor starting")
    }

    override def receive = {
      case StreamError(e) =>
        throw e
    }
  }

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem()
    system.actorOf(Props[StreamActor])
  }
}

object RecoverWithRetries {
  def stream(start: Int) = Source(Stream.from(start))
    .delay(2.second)
    .map(el => if (el % 3 == 0) el / 0 else el)
    .take(100)

  def main(args: Array[String]): Unit = {
    val counter = new AtomicInteger(0)

    val failing = stream(0)
      .recoverWithRetries(5, {
        case e =>
          val currentAttempt = counter.incrementAndGet()
          println(s"Failed with ${e}. Running another stream; retry #${currentAttempt}.")
          stream(currentAttempt)
      })
      .toMat(Sink.fold(0)(_ + _))(Keep.right)

    val decider: Supervision.Decider = {
      case e =>
        println(s"Ouch! ${e}")
        Supervision.Stop // Or Supervision.Restart/Stop
    }

    implicit val system = ActorSystem()
    import system.dispatcher

    val settings = ActorMaterializerSettings(system)
      .withSupervisionStrategy(decider)
    implicit val mat = ActorMaterializer(settings)

    failing.run() onComplete {
      case v =>
        println(s"Result: ${v}")
        system.terminate
    }
  }
}
