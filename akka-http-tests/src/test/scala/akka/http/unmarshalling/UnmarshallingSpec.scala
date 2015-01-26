/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import akka.http.testkit.ScalatestUtils
import akka.util.ByteString

import scala.concurrent.duration._
import scala.concurrent.{ Future, Await }
import org.scalatest.matchers.Matcher
import org.scalatest.{ BeforeAndAfterAll, FreeSpec, Matchers }
import akka.actor.ActorSystem
import akka.stream.FlowMaterializer
import akka.stream.scaladsl._
import akka.http.model._
import akka.http.util._
import headers._
import MediaTypes._
import FastFuture._

class UnmarshallingSpec extends FreeSpec with Matchers with BeforeAndAfterAll with ScalatestUtils {
  implicit val system = ActorSystem(getClass.getSimpleName)
  implicit val materializer = FlowMaterializer()
  import system.dispatcher

  "The PredefinedFromEntityUnmarshallers." - {
    "stringUnmarshaller should unmarshal `text/plain` content in UTF-8 to Strings" in {
      Unmarshal(HttpEntity("Hällö")).to[String] should evaluateTo("Hällö")
    }
    "charArrayUnmarshaller should unmarshal `text/plain` content in UTF-8 to char arrays" in {
      Unmarshal(HttpEntity("árvíztűrő ütvefúrógép")).to[Array[Char]] should evaluateTo("árvíztűrő ütvefúrógép".toCharArray)
    }
  }

  "The MultipartUnmarshallers." - {

    "multipartGeneralUnmarshaller should correctly unmarshal 'multipart/*' content with" - {
      "an empty entity" in {
        Unmarshal(HttpEntity(`multipart/mixed` withBoundary "XYZABC", ByteString.empty)).to[Multipart.General] should haveParts()
      }
      "an entity without initial boundary" in {
        Unmarshal(HttpEntity(`multipart/mixed` withBoundary "XYZABC",
          """this is
            |just preamble text""".stripMarginWithNewline("\r\n"))).to[Multipart.General] should haveParts()
      }
      "an empty part" in {
        Unmarshal(HttpEntity(`multipart/mixed` withBoundary "XYZABC",
          """--XYZABC
            |--XYZABC--""".stripMarginWithNewline("\r\n"))).to[Multipart.General] should haveParts(
          Multipart.General.BodyPart.Strict(HttpEntity.empty(ContentTypes.`text/plain(UTF-8)`)))
      }
      "two empty parts" in {
        Unmarshal(HttpEntity(`multipart/mixed` withBoundary "XYZABC",
          """--XYZABC
            |--XYZABC
            |--XYZABC--""".stripMarginWithNewline("\r\n"))).to[Multipart.General] should haveParts(
          Multipart.General.BodyPart.Strict(HttpEntity.empty(ContentTypes.`text/plain(UTF-8)`)),
          Multipart.General.BodyPart.Strict(HttpEntity.empty(ContentTypes.`text/plain(UTF-8)`)))
      }
      "a part without entity and missing header separation CRLF" in {
        Unmarshal(HttpEntity(`multipart/mixed` withBoundary "XYZABC",
          """--XYZABC
            |Content-type: text/xml
            |Age: 12
            |--XYZABC--""".stripMarginWithNewline("\r\n"))).to[Multipart.General] should haveParts(
          Multipart.General.BodyPart.Strict(HttpEntity.empty(MediaTypes.`text/xml`), List(Age(12))))
      }
      "one non-empty part" in {
        Unmarshal(HttpEntity(`multipart/form-data` withBoundary "-",
          """---
              |Content-type: text/plain; charset=UTF8
              |content-disposition: form-data; name="email"
              |
              |test@there.com
              |-----""".stripMarginWithNewline("\r\n"))).to[Multipart.General] should haveParts(
          Multipart.General.BodyPart.Strict(
            HttpEntity(ContentTypes.`text/plain(UTF-8)`, "test@there.com"),
            List(`Content-Disposition`(ContentDispositionTypes.`form-data`, Map("name" -> "email")))))
      }
      "two different parts" in {
        Unmarshal(HttpEntity(`multipart/mixed` withBoundary "12345",
          """--12345
              |
              |first part, with a trailing newline
              |
              |--12345
              |Content-Type: application/octet-stream
              |Content-Transfer-Encoding: binary
              |
              |filecontent
              |--12345--""".stripMarginWithNewline("\r\n"))).to[Multipart.General] should haveParts(
          Multipart.General.BodyPart.Strict(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "first part, with a trailing newline\r\n")),
          Multipart.General.BodyPart.Strict(
            HttpEntity(`application/octet-stream`, "filecontent"),
            List(RawHeader("Content-Transfer-Encoding", "binary"))))
      }
      "illegal headers" in (
        Unmarshal(HttpEntity(`multipart/form-data` withBoundary "XYZABC",
          """--XYZABC
            |Date: unknown
            |content-disposition: form-data; name=email
            |
            |test@there.com
            |--XYZABC--""".stripMarginWithNewline("\r\n"))).to[Multipart.General] should haveParts(
          Multipart.General.BodyPart.Strict(
            HttpEntity(ContentTypes.`text/plain(UTF-8)`, "test@there.com"),
            List(`Content-Disposition`(ContentDispositionTypes.`form-data`, Map("name" -> "email")),
              RawHeader("date", "unknown")))))
    }

    "multipartGeneralUnmarshaller should reject illegal multipart content with" - {
      "a stray boundary" in {
        Await.result(Unmarshal(HttpEntity(`multipart/form-data` withBoundary "ABC",
          """--ABC
            |Content-type: text/plain; charset=UTF8
            |--ABCContent-type: application/json
            |content-disposition: form-data; name="email"
            |-----""".stripMarginWithNewline("\r\n")))
          .to[Multipart.General].failed, 1.second).getMessage shouldEqual "Illegal multipart boundary in message content"
      }
      "duplicate Content-Type header" in {
        Await.result(Unmarshal(HttpEntity(`multipart/form-data` withBoundary "-",
          """---
            |Content-type: text/plain; charset=UTF8
            |Content-type: application/json
            |content-disposition: form-data; name="email"
            |
            |test@there.com
            |-----""".stripMarginWithNewline("\r\n")))
          .to[Multipart.General].failed, 1.second).getMessage shouldEqual
          "multipart part must not contain more than one Content-Type header"
      }
    }

    "multipartByteRangesUnmarshaller should correctly unmarshal multipart/byteranges content with two different parts" in {
      Unmarshal(HttpEntity(`multipart/byteranges` withBoundary "12345",
        """--12345
          |Content-Range: bytes 0-2/26
          |Content-Type: text/plain
          |
          |ABC
          |--12345
          |Content-Range: bytes 23-25/26
          |Content-Type: text/plain
          |
          |XYZ
          |--12345--""".stripMarginWithNewline("\r\n"))).to[Multipart.ByteRanges] should haveParts(
        Multipart.ByteRanges.BodyPart.Strict(ContentRange(0, 2, 26), HttpEntity(ContentTypes.`text/plain`, "ABC")),
        Multipart.ByteRanges.BodyPart.Strict(ContentRange(23, 25, 26), HttpEntity(ContentTypes.`text/plain`, "XYZ")))
    }

    "multipartFormDataUnmarshaller should correctly unmarshal 'multipart/form-data' content" - {
      "with one element" in {
        Unmarshal(HttpEntity(`multipart/form-data` withBoundary "XYZABC",
          """--XYZABC
            |content-disposition: form-data; name=email
            |
            |test@there.com
            |--XYZABC--""".stripMarginWithNewline("\r\n"))).to[Multipart.FormData] should haveParts(
          Multipart.FormData.BodyPart.Strict("email", HttpEntity(ContentTypes.`application/octet-stream`, "test@there.com")))
      }
      "with a file" in {
        Unmarshal {
          HttpEntity.Default(
            contentType = `multipart/form-data` withBoundary "XYZABC",
            contentLength = 1, // not verified during unmarshalling
            data = Source {
              List(
                ByteString {
                  """--XYZABC
                    |Content-Disposition: form-data; name="email"
                    |
                    |test@there.com
                    |--XYZABC
                    |Content-Dispo""".stripMarginWithNewline("\r\n")
                },
                ByteString {
                  """sition: form-data; name="userfile"; filename="test.dat"
                    |Content-Type: application/pdf
                    |Content-Transfer-Encoding: binary
                    |
                    |filecontent
                    |--XYZABC--""".stripMarginWithNewline("\r\n")
                })
            })
        }.to[Multipart.FormData].flatMap(_.toStrict(1.second)) should haveParts(
          Multipart.FormData.BodyPart.Strict("email", HttpEntity(ContentTypes.`application/octet-stream`, "test@there.com")),
          Multipart.FormData.BodyPart.Strict("userfile", HttpEntity(MediaTypes.`application/pdf`, "filecontent"),
            Map("filename" -> "test.dat"), List(RawHeader("Content-Transfer-Encoding", "binary"))))
      }
      // TODO: reactivate after multipart/form-data unmarshalling integrity verification is implemented
      //
      //      "reject illegal multipart content" in {
      //        val Left(MalformedContent(msg, _)) = HttpEntity(`multipart/form-data` withBoundary "XYZABC", "--noboundary--").as[MultipartFormData]
      //        msg shouldEqual "Missing start boundary"
      //      }
      //      "reject illegal form-data content" in {
      //        val Left(MalformedContent(msg, _)) = HttpEntity(`multipart/form-data` withBoundary "XYZABC",
      //          """|--XYZABC
      //            |content-disposition: form-data; named="email"
      //            |
      //            |test@there.com
      //            |--XYZABC--""".stripMargin).as[MultipartFormData]
      //        msg shouldEqual "Illegal multipart/form-data content: unnamed body part (no Content-Disposition header or no 'name' parameter)"
      //      }
    }
  }

  override def afterAll() = system.shutdown()

  def haveParts[T <: Multipart](parts: Multipart.BodyPart*): Matcher[Future[T]] =
    equal(parts).matcher[Seq[Multipart.BodyPart]] compose { x ⇒
      Await.result(x
        .fast.flatMap(x ⇒ x.parts.grouped(100).runWith(Sink.head))
        .fast.recover { case _: NoSuchElementException ⇒ Nil }, 1.second)
    }
}