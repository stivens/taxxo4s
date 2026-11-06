package taxxo4s

import sttp.monad.MonadError

final case class Lazy[A](run: () => A)

object Lazy {
  given monad: MonadError[Lazy] with {
    def unit[T](t: T): Lazy[T] = Lazy(() => t)
    def map[T, T2](fa: Lazy[T])(f: T => T2): Lazy[T2] = Lazy(() => f(fa.run()))
    def flatMap[T, T2](fa: Lazy[T])(f: T => Lazy[T2]): Lazy[T2] = Lazy(() => f(fa.run()).run())
    def error[T](t: Throwable): Lazy[T] = Lazy(() => throw t)
    protected def handleWrappedError[T](rt: Lazy[T])(h: PartialFunction[Throwable, Lazy[T]]): Lazy[T] =
      Lazy(() => try rt.run() catch h.andThen(_.run()))
    def ensure[T](f: Lazy[T], e: => Lazy[Unit]): Lazy[T] = Lazy(() => try f.run() finally e.run())
  }
}
