package streams

import akka.NotUsed
import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableMessage
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ ConsumerSettings, Subscription }
import akka.kafka.scaladsl.Consumer
import akka.stream.FlowShape
import akka.stream.scaladsl.{ Broadcast, Zip }
import akka.stream.{ ActorMaterializer, ClosedShape, Graph }
import akka.stream.scaladsl.{ Flow, GraphDSL, RunnableGraph, Sink, Source }
import play.api.libs.json.{ Json, Reads }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object GraphDSLExample {
  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem()
    implicit val mat = ActorMaterializer()

    val m = rg.run()
  }

  val g: Graph[ClosedShape, NotUsed] =
    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val src = b.add(Source(List(10, 20, 30)))
      val flow = b.add(Flow[Int].map(_ * 10))
      val sink = b.add(Sink.seq[Int])

      src ~> flow ~> sink

      ClosedShape
    }

  val rg = RunnableGraph.fromGraph(g)
}

object GraphDSLMat {
  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem()
    implicit val mat = ActorMaterializer()

    val m: Future[Seq[Int]] = rg.run()

    m onSuccess {
      case vs =>
        println(vs)
        system.terminate()
    }
  }

  val gMat: Graph[ClosedShape, Future[Seq[Int]]] =
    GraphDSL.create(Sink.seq[Int]) { implicit b => sink =>
      import GraphDSL.Implicits._

      val src = b.add(Source(List(10, 20, 30)))
      val flow = b.add(Flow[Int].map(_ * 10))

      src ~> flow ~> sink

      ClosedShape
    }

  val rg = RunnableGraph.fromGraph(gMat)
}

object KafkaGraph {
  type KafkaMessage = CommittableMessage[Array[Byte], Array[Byte]]

  def kafkaConsumer(settings: ConsumerSettings[Array[Byte], Array[Byte]],
    subscription: Subscription) =
    Consumer.committableSource(settings, subscription)

  def deserializer[T: Reads] = Flow[Array[Byte]]
    .map(Json.parse(_).validate[T].asOpt)

  def committer[T] = Flow[(Option[T], KafkaMessage)].mapAsync(1) {
    case (v, msg) =>
      msg.committableOffset.commitScaladsl().map(_ => v)
  }

  def deserializationFlow[T](deserializer: Flow[Array[Byte], Option[T], _],
    committer: Flow[(Option[T], KafkaMessage), Option[T], _]) =
    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val toBody = b.add(Flow[KafkaMessage].map(_.record.value))
      val deser = b.add(deserializer)

      val bcast = b.add(Broadcast[KafkaMessage](2))
      val zip = b.add(Zip[Option[T], KafkaMessage]())

      val commit = b.add(committer.mapConcat(_.toList))

      bcast.out(0) ~> toBody ~> deser ~> zip.in0
      bcast.out(1)                    ~> zip.in1; zip.out ~> commit

      FlowShape(bcast.in, commit.out)
    }

  def entireGraph[T](kafkaSource: Source[KafkaMessage, Consumer.Control],
    deserializer: Flow[Array[Byte], Option[T], _],
    committer: Flow[(Option[T], KafkaMessage), Option[T], _],
    processor: Flow[T, _, _]) =
    kafkaSource
      .via(deserializationFlow(deserializer, committer))
      .via(processor)
      .to(Sink.ignore)
}
