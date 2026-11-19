package taxxo4s

import sttp.tapir.PublicEndpoint

sealed trait TaxxoRequest[R] {
  type I
  def endpoint: PublicEndpoint[I, Unit, R, Any]
  def input: I
}

object TaxxoRequest {
  def apply[I0, R](e: PublicEndpoint[I0, Unit, R, Any], in: I0): TaxxoRequest[R] =
    new TaxxoRequest[R] {
      type I = I0
      val endpoint = e
      val input = in
    }
}
