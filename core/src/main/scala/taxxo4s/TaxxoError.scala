package taxxo4s

enum TaxxoError(message: String) extends RuntimeException(message) {
  case Api(errorCode: Option[Int], apiMessage: Option[String], additionalData: Option[String])
      extends TaxxoError(s"Taxxo API returned an error (code ${errorCode.getOrElse("-")}): ${apiMessage.getOrElse("")}")
  case Http(statusCode: Int) extends TaxxoError(s"Taxxo API returned HTTP $statusCode")
  case Decode(details: String) extends TaxxoError(s"Could not decode Taxxo API response: $details")
  case MissingData extends TaxxoError("Taxxo API reported success but returned no Data")
}
