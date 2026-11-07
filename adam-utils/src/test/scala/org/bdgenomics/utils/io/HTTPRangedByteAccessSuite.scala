/**
 * Licensed to Big Data Genomics (BDG) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The BDG licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bdgenomics.utils.io

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import java.net.{ InetSocketAddress, URI }
import org.scalatest.{ BeforeAndAfterAll, FunSuite }

class HTTPRangedByteAccessSuite extends FunSuite with BeforeAndAfterAll {

  private val bytes: Array[Byte] = (0 until 32).map(_.toByte).toArray
  private val rangePattern = "bytes=(\\d+)-(\\d+)".r

  /**
   * Each path serves the same bytes but misbehaves in one way, so every
   * check in HTTPRangedByteAccess has a server that trips it.
   */
  private def respond(exchange: HttpExchange): Unit = {
    val path = exchange.getRequestURI.getPath
    val headers = exchange.getResponseHeaders
    if (exchange.getRequestMethod == "HEAD") {
      path match {
        case "/no-ranges"   => headers.add("Accept-Ranges", "none")
        case "/odd-ranges"  => headers.add("Accept-Ranges", "lines")
        case "/no-length"   =>
        case _              => headers.add("Accept-Ranges", "bytes")
      }
      if (path != "/no-length") headers.add("Content-Length", bytes.length.toString)
      exchange.sendResponseHeaders(200, -1)
    } else {
      val (start, end) = exchange.getRequestHeaders.getFirst("Range") match {
        case rangePattern(s, e) => (s.toInt, e.toInt)
      }
      val body = bytes.slice(start, end + 1)
      val contentRange = path match {
        case "/no-content-range" => None
        case "/bad-content-range" => Some("lines 1-2")
        case "/star-range"       => Some("bytes */%d".format(bytes.length))
        case "/wrong-start"      => Some("bytes %d-%d/%d".format(start + 1, end + 1, bytes.length))
        case "/wrong-length"     => Some("bytes %d-%d/%d".format(start, end + 1, bytes.length))
        case _                   => Some("bytes %d-%d/%d".format(start, end, bytes.length))
      }
      contentRange.foreach(headers.add("Content-Range", _))
      exchange.sendResponseHeaders(if (path == "/not-partial") 200 else 206, body.length)
      exchange.getResponseBody.write(body)
    }
    exchange.close()
  }

  private var server: HttpServer = _

  override def beforeAll(): Unit = {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", respond(_))
    server.start()
  }

  override def afterAll(): Unit = server.stop(0)

  private def access(path: String) =
    new HTTPRangedByteAccess(new URI(s"http://127.0.0.1:${server.getAddress.getPort}$path"), retries = 0)

  test("length comes from the Content-Length of a HEAD request") {
    assert(access("/ok").length() === bytes.length)
  }

  test("an unknown Accept-Ranges unit is ignored") {
    assert(access("/odd-ranges").length() === bytes.length)
  }

  test("Accept-Ranges: none is rejected") {
    val ex = intercept[IllegalStateException](access("/no-ranges").length())
    assert(ex.getMessage.contains("doesn't accept range requests"))
  }

  test("a missing Content-Length is rejected") {
    val ex = intercept[IllegalStateException](access("/no-length").length())
    assert(ex.getMessage.contains("No \"Content-Length\" header"))
  }

  test("readFully returns the requested range") {
    assert(access("/ok").readFully(3, 5).toSeq === bytes.slice(3, 8).toSeq)
  }

  test("an unknown range in the Content-Range is accepted") {
    assert(access("/star-range").readFully(3, 5).toSeq === bytes.slice(3, 8).toSeq)
  }

  test("a response that is not 206 Partial Content is rejected") {
    val ex = intercept[IllegalArgumentException](access("/not-partial").readFully(0, 4))
    assert(ex.getMessage.contains("expected status code 206 but received 200"))
  }

  test("a missing Content-Range header is rejected") {
    val ex = intercept[IllegalStateException](access("/no-content-range").readFully(0, 4))
    assert(ex.getMessage.contains("didn't return a Content-Range header"))
  }

  test("a malformed Content-Range header is rejected") {
    val ex = intercept[IllegalStateException](access("/bad-content-range").readFully(0, 4))
    assert(ex.getMessage.contains("didn't match the expected format"))
  }

  test("a Content-Range that starts elsewhere is rejected") {
    val ex = intercept[IllegalArgumentException](access("/wrong-start").readFully(2, 4))
    assert(ex.getMessage.contains("doesn't match offset 2"))
  }

  test("a Content-Range of another length is rejected") {
    val ex = intercept[IllegalArgumentException](access("/wrong-length").readFully(2, 4))
    assert(ex.getMessage.contains("doesn't match length 4"))
  }
}
