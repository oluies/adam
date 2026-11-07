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
package org.bdgenomics.utils.cli

import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.kohsuke.args4j.{ Argument, Option => Args4jOption }
import org.scalatest.FunSuite

class TestArgs extends Args4jBase with ParquetArgs {
  @Argument(required = true, metaVar = "INPUT", index = 0)
  var input: String = _

  @Args4jOption(required = false, name = "-count")
  var count: Int = 1
}

class Args4jSuite extends FunSuite {

  test("parse arguments and options") {
    val args = Args4j[TestArgs](Array("in.adam", "-count", "3"))
    assert(args.input === "in.adam")
    assert(args.count === 3)
    assert(!args.doPrintUsage)
  }

  test("Parquet options have defaults and can be set") {
    val defaults = Args4j[TestArgs](Array("in.adam"))
    assert(defaults.compressionCodec === CompressionCodecName.GZIP)
    val snappy = Args4j[TestArgs](Array("in.adam", "-parquet_compression_codec", "SNAPPY", "-parquet_block_size", "10"))
    assert(snappy.compressionCodec === CompressionCodecName.SNAPPY)
    assert(snappy.blockSize === 10)
  }

  test("parse errors can be ignored") {
    val args = Args4j[TestArgs](Array("-bogus"), ignoreCmdLineExceptions = true)
    assert(args.input === null)
  }
}
