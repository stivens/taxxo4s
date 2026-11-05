package taxxo4s

import scala.compiletime.{constValue, erasedValue, error}
import scala.deriving.Mirror

import taxxo4s.generated.enums.APIResponseStatusType

trait Envelope[R] {
  type Data
  def data(r: R): Option[Data]
  def error(r: R): Option[TaxxoError.Api]
}

object Envelope {
  type Aux[R, D] = Envelope[R] { type Data = D }

  type DataOf[Labels <: Tuple, Types <: Tuple] = (Labels, Types) match {
    case ("Data" *: _, Option[t] *: _) => t
    case (_ *: ls, _ *: ts)            => DataOf[ls, ts]
    case (EmptyTuple, EmptyTuple)      => Unit
  }

  inline given derived[R <: Product](using m: Mirror.ProductOf[R]): Aux[R, DataOf[m.MirroredElemLabels, m.MirroredElemTypes]] =
    new Indexed[R, DataOf[m.MirroredElemLabels, m.MirroredElemTypes]](
      dataIdx = indexOf[m.MirroredElemLabels, "Data"](0, required = false),
      statusIdx = indexOf[m.MirroredElemLabels, "Status"](0, required = true),
      messageIdx = indexOf[m.MirroredElemLabels, "Message"](0, required = true),
      errorCodeIdx = indexOf[m.MirroredElemLabels, "ErrorCode"](0, required = true),
      additionalDataIdx = indexOf[m.MirroredElemLabels, "AdditionalData"](0, required = true)
    )

  final class Indexed[R <: Product, D](dataIdx: Int, statusIdx: Int, messageIdx: Int, errorCodeIdx: Int, additionalDataIdx: Int)
      extends Envelope[R] {
    type Data = D

    def data(r: R): Option[D] =
      if (dataIdx < 0) Some(().asInstanceOf[D]) else field[D](r, dataIdx)

    def error(r: R): Option[TaxxoError.Api] =
      Option.when(field[Int](r, statusIdx).contains(APIResponseStatusType.Error))(
        TaxxoError.Api(field(r, errorCodeIdx), field(r, messageIdx), field(r, additionalDataIdx))
      )

    private def field[A](r: R, idx: Int): Option[A] = r.productElement(idx).asInstanceOf[Option[A]]
  }

  private inline def indexOf[Labels <: Tuple, L <: String](inline acc: Int, inline required: Boolean): Int =
    inline erasedValue[Labels] match {
      case _: (L *: _)    => acc
      case _: (_ *: tail) => indexOf[tail, L](acc + 1, required)
      case _: EmptyTuple =>
        inline if (required) error("Not a Taxxo response envelope: missing field " + constValue[L]) else -1
    }
}
