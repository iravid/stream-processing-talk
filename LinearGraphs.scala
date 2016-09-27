package streams

import akka.NotUsed
import akka.actor.{ ActorSystem, Cancellable }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Flow, Keep, RunnableGraph, Sink, Source }
import com.sksamuel.elastic4s.{ ElasticClient, ElasticDsl }
import play.api.libs.json.{ Json, Writes }
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

object SimpleSource {
  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("streams")
    implicit val mat = ActorMaterializer()

    val src: RunnableGraph[NotUsed] = Source(Stream.from(0))
      .filter(_ % 2 == 0)
      .map(_ * 2)
      .grouped(50)
      .take(50)
      .to(Sink.foreach(println))

    val n: NotUsed = src.run()
  }
}

object ElasticsearchWriter {
  case class Document(s: String, i: Int)

  def elasticWriter(client: ElasticClient, indexName: String, indexType: String)(
    implicit ec: ExecutionContext) =
    Flow[Document].mapAsync(1) { doc =>
      import ElasticDsl._
      implicit val writer: Writes[Document] = ???

      val indexReq = index into indexName / indexType source
        Json.toJson(doc).toString
      client.execute(indexReq).map(_ => doc)
    }

  def elasticWriterBatch(client: ElasticClient, indexName: String, indexType: String,
    batchSize: Long)(implicit ec: ExecutionContext) =
    Flow[Document]
      .batch(batchSize, Vector(_)) { (batch, el) =>
        batch :+ el
      }
      .mapAsync(1) { batch =>
        import ElasticDsl._
        implicit val writer: Writes[Document] = ???

        val indexReqs = batch.map { doc =>
          index into indexName / indexType source Json.toJson(doc).toString
        }

        val bulkReq = bulk(indexReqs)

        client.execute(bulkReq).map(_ => batch)
      }
      .mapConcat(identity)

  def elasticWriter[I: Writes](client: ElasticClient, indexName: String, indexType: String,
    batchSize: Long)(implicit ec: ExecutionContext) =
    Flow[I]
      .batch(batchSize, Vector(_)) { (batch, el) =>
        batch :+ el
      }
      .mapAsync(1) { batch =>
        import ElasticDsl._

        val indexReqs = batch.map { doc =>
          index into indexName / indexType source Json.toJson(doc).toString
        }

        val bulkReq = bulk(indexReqs)

        client.execute(bulkReq).map(_ => batch)
      }
      .mapConcat(identity)
}

object MaterializedValues {
  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("streams")
    implicit val mat = ActorMaterializer()
    import system.dispatcher

    val src: RunnableGraph[(Cancellable, Future[Seq[String]])] =
      Source
        .tick(0.millis, 1.second, "hello")
        .map { el =>
          println(el)
          el
        }
        .toMat(Sink.seq)(Keep.both)

    val (task, seqF) = src.run()

    seqF.onSuccess {
      case hellos =>
        println("All done!")
        println(hellos)
        system.terminate
    }

    system.scheduler.scheduleOnce(5.seconds) {
      println("Cancelling task...")
      task.cancel()
    }
  }
}
