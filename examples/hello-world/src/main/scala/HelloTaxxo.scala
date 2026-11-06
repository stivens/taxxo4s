import sttp.client4.{DefaultSyncBackend, SyncBackend}
import taxxo4s.{TaxxoClient, TaxxoConfig}
import taxxo4s.generated.General

def greeting(apiKey: String, backend: SyncBackend): String =
  val taxxo = TaxxoClient(TaxxoConfig(apiKey), backend)
  val message = taxxo.call(General.ApiPublicContextinfoGet)(()) match
    case Right(ctx) =>
      s"Hello, ${ctx.UserName.getOrElse("Taxxo user")}! Connected to ${ctx.CustomerName.getOrElse("an unnamed customer")}."
    case Left(error) =>
      s"Taxxo call failed: ${error.getMessage}"
  taxxo.logout
  message

@main def helloTaxxo(): Unit =
  sys.env.get("TAXXO_API_KEY") match
    case None         => println("Set TAXXO_API_KEY to an API key generated in the API section of the Taxxo UI.")
    case Some(apiKey) => println(greeting(apiKey, DefaultSyncBackend()))
