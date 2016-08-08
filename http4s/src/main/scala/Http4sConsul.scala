package helm
package http4s

import journal.Logger

import org.http4s._
import org.http4s.client._
import org.http4s.argonaut.jsonOf
import org.http4s.headers.Authorization
import org.http4s.Status.{Ok, NotFound}
import scalaz.~>
import scalaz.concurrent.Task
import scalaz.stream.Process
import scalaz.std.option._
import scalaz.syntax.std.option._
import scalaz.syntax.functor._
import scalaz.syntax.traverse._

import scodec.bits.ByteVector

final class Http4sConsulClient(baseUri: Uri,
                               client: Client,
                               accessToken: Option[String] = None,
                               credentials: Option[(String,String)] = None) extends (HelmOp ~> Task) {

  private implicit val responseDecoder: EntityDecoder[KvResponses] = jsonOf[KvResponses]
  private implicit val keysDecoder: EntityDecoder[List[String]] = jsonOf[List[String]]

  private val log = Logger[this.type]

  def apply[A](op: HelmOp[A]): Task[A] = op match {
    case HelmOp.Get(key) => get(key)
    case HelmOp.Set(key, value) => set(key, value)
    case HelmOp.ListKeys(prefix) => list(prefix)
    case HelmOp.Delete(key) => delete(key)
  }

  def addHeader(req: Request): Request =
    accessToken.fold(req)(tok => req.putHeaders(Header("X-Consul-Token", tok)))

  def addCreds(req: Request): Request =
    credentials.fold(req){case (un,pw) => req.putHeaders(Authorization(BasicCredentials(un,pw)))}

  def get(key: Key): Task[Option[String]] = {
    for {
      _ <- Task.delay(log.debug(s"fetching consul key $key"))
      req = Request(uri = baseUri / "v1" / "kv" / key)
      kvs <- client.expect[KvResponses](req).map(Some.apply).handleWith {
        case UnexpectedStatus(NotFound) => Task.now(None)
      }
      head <- kvs.traverse(keyValue(key, _))
    } yield {
      log.debug(s"consul value for key $key is $kvs")
      head.map(_.value)
    }
  }

  def set(key: Key, value: String): Task[Unit] =
    for {
      _ <- Task.delay(log.debug(s"setting consul key $key to $value"))
      response <- client.expect[String](
        addCreds(addHeader(
          Request(Method.PUT,
            uri = baseUri / "v1" / "kv" / key,
            body = Process.emit(ByteVector.view(value.getBytes("UTF-8")))))))
    } yield log.debug(s"setting consul key $key resulted in response $response")

  def list(prefix: Key): Task[Set[Key]] = {
    val req = addCreds(addHeader(Request(uri = (baseUri / "v1" / "kv" / prefix).withQueryParam(QueryParam.fromKey("keys")))))

    for {
      _ <- Task.delay(log.debug(s"listing key consul with the prefix: $prefix"))
      response <- client.expect[List[String]](req)
    } yield {
      log.debug(s"listing of keys: " + response)
      response.toSet
    }
  }

  def delete(key: Key): Task[Unit] = {
    val req = addCreds(addHeader(Request(Method.DELETE, uri = (baseUri / "v1" / "kv" / key))))

    for {
      _ <- Task.delay(log.debug(s"deleting $key from the consul KV store"))
      response <- client.expect[String](req)
    } yield log.debug(s"response from delete: " + response)
  }
}
