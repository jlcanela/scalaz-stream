package scalaz.stream

import java.io.{BufferedOutputStream,BufferedInputStream,FileInputStream,File,FileOutputStream,InputStream,OutputStream}

import scalaz.concurrent.Task
import Process._

/**
 * Module of `Process` functions and combinators for file and network I/O.
 */
trait io {

  // NB: methods are in alphabetical order

  /**
   * Like resource, but the `release` action may emit a final value,
   * useful for flushing any internal buffers. NB: In the event of an
   * error, this final value is ignored.
   */
  def bufferedResource[R,O](acquire: Task[R])(
                            flushAndRelease: R => Task[O])(
                            step: R => Task[O]): Process[Task,O] = {
    def go(step: Task[O], onExit: Process[Task,O], onFailure: Process[Task,O]): Process[Task,O] =
      await[Task,O,O](step) (
        o => emit(o) ++ go(step, onExit, onFailure) // Emit the value and repeat
      , onExit                                      // Release resource when exhausted
      , onExit)                                     // or in event of error
    await(acquire)(r => {
      val onExit = suspend(wrap(flushAndRelease(r)))
      val onFailure = onExit.drain
      go(step(r), onExit, onFailure)
    }, halt, halt)
  }

  /**
   * Implementation of resource for channels where resource needs to be
   * flushed at the end of processing.
   */
  def bufferedChannel[R,I,O](acquire: Task[R])(
                             flush: R => Task[O])(
                             release: R => Task[Unit])(
                             step: R => Task[I => Task[O]]): Channel[Task,Option[I],O] = {
    resource[R,Option[I] => Task[O]](acquire)(release) {
      r =>
        val s = step(r)
        Task.now {
          case Some(i) => s flatMap (f => f(i))
          case None => flush(r)
        }
    }
  }

  /**
   * Create a `Channel[Task,Int,Bytes]` from an `InputStream` by
   * repeatedly requesting the given number of bytes. The last chunk
   * may be less than the requested size.
   *
   * This implementation requires an array allocation for each read.
   * To recycle the input buffer, use `unsafeChunkR`.
   *
   * This implementation closes the `InputStream` when finished
   * or in the event of an error.
   */
  def chunkR(is: => InputStream): Channel[Task, Int, Array[Byte]] =
    unsafeChunkR(is).map(f => (n: Int) => {
      val buf = new Array[Byte](n)
      f(buf).map(_.toArray)
    })

  /**
   * Create a `Sink` from an `OutputStream`, which will be closed
   * when this `Process` is halted.
   */
  def chunkW(os: => OutputStream): Process[Task, Array[Byte] => Task[Unit]] =
    resource(Task.delay(os))(os => Task.delay(os.close))(
      os => Task.now((bytes: Array[Byte]) => Task.delay(os.write(bytes))))

  /** Create a `Sink` from a file name and optional buffer size in bytes. */
  def fileChunkW(f: String, bufferSize: Int = 4096): Process[Task, Array[Byte] => Task[Unit]] =
    chunkW(new BufferedOutputStream(new FileOutputStream(f), bufferSize))

  /** Create a `Source` from a file name and optional buffer size in bytes. */
  def fileChunkR(f: String, bufferSize: Int = 4096): Channel[Task, Int, Array[Byte]] =
    chunkR(new BufferedInputStream(new FileInputStream(f), bufferSize))

  /**
   * Convenience helper to produce Bytes from the Array[Byte]
   */
  def fromByteArray : Process1[Array[Byte],Bytes] =
    processes.lift(Bytes(_))

  /**
   * Create a `Process[Task,String]` from the lines of a file, using
   * the `resource` combinator to ensure the file is closed
   * when processing the stream of lines is finished.
   */
  def linesR(filename: String): Process[Task,String] =
    resource(Task.delay(scala.io.Source.fromFile(filename)))(
             src => Task.delay(src.close)) { src =>
      lazy val lines = src.getLines // A stateful iterator
      Task.delay { if (lines.hasNext) lines.next else throw End }
    }

  /**
   * Generic combinator for producing a `Process[Task,O]` from some
   * effectful `O` source. The source is tied to some resource,
   * `R` (like a file handle) that we want to ensure is released.
   * See `lines` below for an example use.
   */
  def resource[R,O](acquire: Task[R])(
                    release: R => Task[Unit])(
                    step: R => Task[O]): Process[Task,O] = {
    def go(step: Task[O], onExit: Process[Task,O]): Process[Task,O] =
      await[Task,O,O](step) (
        o => emit(o) ++ go(step, onExit) // Emit the value and repeat
      , onExit                           // Release resource when exhausted
      , onExit)                          // or in event of error
    await(acquire)(r => {
      val onExit = Process.suspend(wrap(release(r)).drain)
      go(step(r), onExit)
    }, halt, halt)
  }

  /**
   * Convenience helper to get Array[Byte] out of Bytes
   */
  def toByteArray: Process1[Bytes,Array[Byte]] =
    processes.lift(_.toArray)

  /**
   * Create a `Channel[Task,Array[Byte],Bytes]` from an `InputStream` by
   * repeatedly filling the input buffer. The last chunk may be less
   * than the requested size.
   *
   * Because this implementation returns a read-only view of the given
   * buffer, it is safe to recyle the same buffer for consecutive reads
   * as long as whatever consumes this `Process` never stores the `Bytes`
   * returned or pipes it to a combinator (like `buffer`) that does.
   * Use `chunkR` for a safe version of this combinator.
   *
   * This implementation closes the `InputStream` when finished
   * or in the event of an error.
   */
  def unsafeChunkR(is: => InputStream): Channel[Task,Array[Byte],Bytes] = {
    resource(Task.delay(is))(
             src => Task.delay(src.close)) { src =>
      Task.now { (buf: Array[Byte]) => Task.delay {
        val m = src.read(buf)
        if (m == -1) throw End
        else new Bytes(buf, m)
      }}
    }
  }
}

object io extends io with gzip
