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

import java.io.{ ByteArrayInputStream, InputStream }
import org.scalatest.FunSuite

class ByteAccessSuite extends FunSuite {

  /** Serves bytes from an array, at most chunkSize bytes per read, and records whether the stream was closed. */
  private class ChunkedByteAccess(bytes: Array[Byte], chunkSize: Int) extends ByteAccess {
    var closed = false

    def length(): Long = bytes.length

    def readByteStream(offset: Long, length: Int): InputStream = {
      new ByteArrayInputStream(bytes, offset.toInt, bytes.length - offset.toInt) {
        override def read(b: Array[Byte], off: Int, len: Int): Int = super.read(b, off, math.min(len, chunkSize))
        override def close(): Unit = closed = true
      }
    }
  }

  private val bytes = (0 until 10).map(_.toByte).toArray

  test("readFully assembles a range from several short reads") {
    val access = new ChunkedByteAccess(bytes, chunkSize = 1)
    assert(access.readFully(2, 5).toSeq === bytes.slice(2, 7).toSeq)
    assert(access.closed)
  }

  test("readFully returns the available bytes when the range runs past the end") {
    val access = new ChunkedByteAccess(bytes, chunkSize = 3)
    assert(access.readFully(6, 10).toSeq === bytes.drop(6).toSeq)
    assert(access.closed)
  }

  test("readFully of zero bytes returns an empty array") {
    assert(new ChunkedByteAccess(bytes, chunkSize = 3).readFully(4, 0).isEmpty)
  }

  test("readFully rejects a negative offset or length") {
    val access = new ChunkedByteAccess(bytes, chunkSize = 3)
    intercept[AssertionError](access.readFully(-1, 1))
    intercept[AssertionError](access.readFully(0, -1))
  }
}
