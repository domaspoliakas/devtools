package dpol

import cats.effect.*
import fs2.*
import scala.concurrent.duration.*
import scala.scalanative.posix

object NonBlockingStdIn: 

  val enableNonBlocking = 
    IO(posix.fcntl.fcntl(0, posix.fcntl.F_SETFL, posix.fcntl.O_NONBLOCK).toByte)

  def apply: Stream[IO, Byte] = 
    def go(in: Stream[IO, Byte]): Pull[IO, Byte, Unit] =
      in.pull.uncons.attempt.flatMap {
        case Right(None) => Pull.done
        case Right(Some((hd, t))) => 
          Pull.output(hd) >> go(in)
        case Left(i: java.io.IOException) =>  
          Pull.eval(IO.sleep(100.millis)) >> go(in)
        case Left(otherErr) =>
          Pull.raiseError(otherErr)
      }

    Stream.eval(enableNonBlocking) >>
      go(io.stdin[IO](512)).stream

