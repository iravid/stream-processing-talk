package streams

object StdlibStream extends App {
  val take2OfList = List(1, 2, 3, 4).take(2)
  println(take2OfList)

  val take3OfStream = Stream.from(1).take(3)
  println(take3OfStream)

  val forcedStream = Stream.from(1).take(5).force
  println(forcedStream)
}

object Fibs extends App {
  lazy val fibs: Stream[Int] = 0 #:: 1 #:: fibs
    .zip(fibs.tail)
    .map { case (fst, snd) => fst + snd }

  println(fibs.take(5))

  println(fibs.take(10).force)
}
