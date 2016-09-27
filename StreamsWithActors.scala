package streams

import akka.actor.{ Actor, ActorRef, ActorSystem, Props }
import akka.pattern.{ ask, pipe }
import akka.stream.{ ActorMaterializer, OverflowStrategy }
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.Timeout

import scala.concurrent.duration._

object Sender {
  case object Start
  case object Ack
  case object Done
  case class Element(i: Int)
}

class Sender(receiver: ActorRef) extends Actor {
  import Sender._
  import context.dispatcher

  implicit val mat = ActorMaterializer()
  implicit val timeout = Timeout(5.seconds)

  Source(Stream.from(0))
    .delay(1.second)
    .map(Element(_))
    .to(Sink.actorRefWithAck(
          ref = self,
          onInitMessage = Start,
          ackMessage = Ack,
          onCompleteMessage = Done,
          onFailureMessage = e => e
        ))
    .run()

  override def receive = {
    case Start =>
      sender() ! Ack

    case e: Element =>
      println(s"Sending ${e}")

      val requester = sender()
      receiver.ask(e).mapTo[Ack.type].pipeTo(requester)
  }
}

class Receiver extends Actor {
  import context.dispatcher
  implicit val mat = ActorMaterializer()

  val streamQueue = Source
    .queue[Sender.Element](10, OverflowStrategy.backpressure)
    .map(_.i * 2)
    .to(Sink.foreach(println(_)))
    .run()

  override def receive = {
    case e: Sender.Element =>
      val offerResult = streamQueue.offer(e)
      val requester = sender()

      offerResult map { res =>
        println(s"Got ${res} from queue")
        Sender.Ack
      } pipeTo requester
  }
}

object StreamsWithActors {
  def main(args: Array[String]): Unit = {
    val system = ActorSystem()

    val receiverRef = system.actorOf(Props[Receiver])
    val senderRef = system.actorOf(Props(classOf[Sender], receiverRef))
  }
}

