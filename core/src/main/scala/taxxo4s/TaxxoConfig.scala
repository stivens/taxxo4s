package taxxo4s

import sttp.model.Uri
import sttp.model.Uri.UriContext

final case class TaxxoConfig(
    apiKey: String,
    baseUri: Uri = uri"https://platforma2.taxxo.pl",
    culture: Option[String] = None
) {
  override def toString: String = s"TaxxoConfig(apiKey = <redacted>, baseUri = $baseUri, culture = $culture)"
}
