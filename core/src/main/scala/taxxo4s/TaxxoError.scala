package taxxo4s

import scala.util.control.NoStackTrace

import sttp.client4.SttpClientException

enum TaxxoError(message: String, cause: Throwable = null) extends RuntimeException(message, cause) with NoStackTrace {
  case Api(errorCode: Option[Int], apiMessage: Option[String], additionalData: Option[String])
      extends TaxxoError(s"Taxxo API returned an error (code ${errorCode.getOrElse("-")}): ${apiMessage.getOrElse("")}")
  case Http(statusCode: Int, body: String)
      extends TaxxoError(s"Taxxo API returned HTTP $statusCode" + (if (body.isEmpty) "" else s": ${TaxxoError.excerpt(body)}"))
  case Decode(details: String, cause: Option[Throwable])
      extends TaxxoError(s"Could not decode Taxxo API response: ${TaxxoError.excerpt(details)}", cause.orNull)
  case Transport(cause: SttpClientException) extends TaxxoError(s"Could not reach the Taxxo API: ${cause.getMessage}", cause)
  case MissingData extends TaxxoError("Taxxo API reported success but returned no Data")
}

object TaxxoError {
  // Messages end up in logs, and a response body can be megabytes.
  private val MaxExcerpt = 500

  private def excerpt(s: String): String =
    if (s.length <= MaxExcerpt) s else s"${s.take(MaxExcerpt)}… (${s.length - MaxExcerpt} more characters)"
}
