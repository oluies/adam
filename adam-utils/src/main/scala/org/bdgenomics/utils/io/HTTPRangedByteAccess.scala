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

import java.io.InputStream
import java.net.URI
import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.{ HttpGet, HttpHead }
import org.apache.http.impl.client.{ HttpClients, StandardHttpRequestRetryHandler }

/**
 * HTTPRangedByteAccess supports Ranged GET queries against HTTP resources.
 *
 * @param uri The URL of the resource, which should support ranged queries
 * @param retries Number of times to retry a failed connection before giving up
 */
class HTTPRangedByteAccess(uri: URI, retries: Int = 3) extends ByteAccess {
  import HTTPRangedByteAccess._

  private def getClient: HttpClient = {
    HttpClients.custom()
      .setRetryHandler(new StandardHttpRequestRetryHandler(retries, true))
      .build()
  }

  private def readLength(): Long = {
    val response = getClient.execute(new HttpHead(uri))
    checkAcceptsRanges(uri, header(response, "Accept-Ranges"))
    // the Content-Length of the whole resource, not of a range
    contentLength(uri, header(response, "Content-Length"))
  }

  private lazy val _length = readLength()

  override def length(): Long = _length

  /**
   * In this initial implementation, we throw an error when we get a partial response from the
   * server whose content is less than the bytes we originally requested.
   *
   * @param offset The offset into the resource at which we want to start reading bytes
   * @param length The number of bytes to be read
   * @return An InputStream from a ranged HTTP GET, which should contain just the requested bytes
   */
  override def readByteStream(offset: Long, length: Int): InputStream = {
    val get = new HttpGet(uri)

    // the end of a byte range is inclusive
    get.setHeader("Range", "bytes=%d-%d".format(offset, offset + length - 1))

    val response = getClient.execute(get)

    // We want a 206 response to a ranged request, not your normal 200!
    require(response.getStatusLine.getStatusCode == 206,
      "Range request on \"%s\", expected status code 206 but received %d (%s)"
        .format(uri.toString, response.getStatusLine.getStatusCode, response.getStatusLine.getReasonPhrase))

    checkContentRange(header(response, "Content-Range"), offset, length)

    response.getEntity.getContent
  }
}

private[io] object HTTPRangedByteAccess {

  /*
   Regex to read the Content-Range response header, as described in:
   http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.16
    */
  private val byteRangeResponseRegex = "bytes\\s((?:\\*|\\d+-\\d+))/((?:\\*|\\d+))".r

  /** The value of a response header; header names are case-insensitive. */
  private def header(response: HttpResponse, name: String): Option[String] = {
    Option(response.getFirstHeader(name)).map(_.getValue)
  }

  /**
   * "bytes" is the only range unit HTTP/1.1 defines, and other units may be
   * ignored. A missing Accept-Ranges header does not mean ranges are refused;
   * only "none" does.
   * See http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.5
   */
  private[io] def checkAcceptsRanges(uri: URI, acceptRanges: Option[String]): Unit = {
    if (acceptRanges.contains("none")) {
      throw new IllegalStateException(
        "Server for \"%s\" doesn't accept range requests (\"Accept-Ranges: none\" header in HEAD request response)"
          .format(uri.toString))
    }
  }

  private[io] def contentLength(uri: URI, contentLength: Option[String]): Long = {
    contentLength.map(_.toLong).getOrElse(
      throw new IllegalStateException("Unknown length of content for \"%s\" (No \"Content-Length\" header)"
        .format(uri.toString)))
  }

  /**
   * Checks that a Content-Range header is present, has the expected format,
   * and, unless the range is "*", starts at offset and covers length bytes.
   */
  private[io] def checkContentRange(contentRange: Option[String], offset: Long, length: Int): Unit = {
    val value = contentRange.getOrElse(
      throw new IllegalStateException("Ranged GET didn't return a Content-Range header"))
    val range = byteRangeResponseRegex.findFirstMatchIn(value).map(_.group(1)).getOrElse(
      throw new IllegalStateException("Content-Range header value \"%s\" didn't match the expected format".format(value)))
    if (range != "*") {
      checkRange(value, range, offset, length)
    }
  }

  private def checkRange(value: String, range: String, offset: Long, length: Int): Unit = {
    val Array(start, end) = range.split("-").map(_.toLong)
    if (start != offset) {
      throw new IllegalArgumentException(
        "Content-Range response start %d (from header \"%s\") doesn't match offset %d"
          .format(start, value, offset))
    }
    if (end - start + 1 != length) {
      throw new IllegalArgumentException(
        "Content-Range response length %d (from header \"%s\") doesn't match length %d"
          .format(end - start + 1, value, length))
    }
  }
}
