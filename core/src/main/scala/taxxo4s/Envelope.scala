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
      dataIdx = fieldIdx[R, "Data", Option[?]](required = false),
      statusIdx = fieldIdx[R, "Status", Option[Int]](required = true),
      messageIdx = fieldIdx[R, "Message", Option[String]](required = true),
      errorCodeIdx = fieldIdx[R, "ErrorCode", Option[Int]](required = true),
      additionalDataIdx = fieldIdx[R, "AdditionalData", Option[String]](required = true)
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

  private inline def fieldIdx[R, L <: String, T](using m: Mirror.ProductOf[R])(inline required: Boolean): Int =
    indexOf[m.MirroredElemLabels, m.MirroredElemTypes, L, T](0, required)

  private inline def indexOf[Labels <: Tuple, Types <: Tuple, L <: String, T](inline acc: Int, inline required: Boolean): Int =
    inline erasedValue[(Labels, Types)] match {
      case _: (L *: _, T *: _)         => acc
      case _: (L *: _, _ *: _)         => error("Not a Taxxo response envelope: field " + constValue[L] + " has an unexpected type")
      case _: (_ *: ls, _ *: ts)       => indexOf[ls, ts, L, T](acc + 1, required)
      case _: (EmptyTuple, EmptyTuple) =>
        inline if (required) error("Not a Taxxo response envelope: missing field " + constValue[L]) else -1
    }
}
