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
package org.bdgenomics.adam.converters

import htsjdk.samtools.ValidationStringency
import htsjdk.variant.vcf._
import org.scalatest.FunSuite
import org.slf4j.LoggerFactory

class CleanHeaderLinesSuite extends FunSuite {

  private val log = LoggerFactory.getLogger(getClass)

  private def clean(lines: Seq[VCFHeaderLine], stringency: ValidationStringency = ValidationStringency.LENIENT) =
    VariantContextConverter.cleanAndMixInSupportedLines(lines, stringency, log)

  private def ids(lines: Seq[VCFHeaderLine]): Seq[String] = lines.collect {
    case l: VCFCompoundHeaderLine => l.getID
  }

  test("keep lines ADAM does not define, once, and mix in the supported lines") {
    val source = new VCFHeaderLine("source", "test")
    val custom = new VCFInfoHeaderLine("CUSTOM", 1, VCFHeaderLineType.Integer, "a custom field")
    val cleaned = clean(Seq(source, custom, custom))
    assert(cleaned.count(_ == source) === 1)
    assert(cleaned.count(_ == custom) === 1)
    assert(DefaultHeaderLines.allHeaderLines.forall(cleaned.contains))
  }

  test("replace a supported line of the same type with ADAM's") {
    val dp = new VCFFormatHeaderLine("DP", 1, VCFHeaderLineType.Integer, "a description of our own")
    val cleaned = clean(Seq(dp))
    assert(cleaned === DefaultHeaderLines.allHeaderLines)
  }

  test("rename a supported line whose type differs, keeping its count") {
    for (stringency <- Seq(ValidationStringency.LENIENT, ValidationStringency.SILENT)) {
      val info = new VCFInfoHeaderLine("AD", VCFHeaderLineCount.R, VCFHeaderLineType.String, "AD as strings")
      val format = new VCFFormatHeaderLine("DP", 1, VCFHeaderLineType.Float, "DP as floats")
      val cleaned = clean(Seq(info, format), stringency)
      val badInfo = cleaned.collectFirst { case l: VCFInfoHeaderLine if l.getID == "BAD_AD" => l }.get
      assert(badInfo.getCountType === VCFHeaderLineCount.R)
      assert(badInfo.getType === VCFHeaderLineType.String)
      val badFormat = cleaned.collectFirst { case l: VCFFormatHeaderLine if l.getID == "BAD_DP" => l }.get
      assert(badFormat.getCount === 1)
      assert(badFormat.getType === VCFHeaderLineType.Float)
      assert(ids(cleaned).count(_ == "DP") === 1, "ADAM's own DP is still there")
    }
  }

  test("rename keeps a fixed count on INFO lines and a per-allele count on FORMAT lines") {
    val info = new VCFInfoHeaderLine("AD", 2, VCFHeaderLineType.String, "AD with two strings")
    val format = new VCFFormatHeaderLine("AD", VCFHeaderLineCount.R, VCFHeaderLineType.Float, "AD as floats")
    val cleaned = clean(Seq(info, format))
    val badInfo = cleaned.collectFirst { case l: VCFInfoHeaderLine if l.getID == "BAD_AD" => l }.get
    assert(badInfo.getCountType === VCFHeaderLineCount.INTEGER)
    assert(badInfo.getCount === 2)
    val badFormat = cleaned.collectFirst { case l: VCFFormatHeaderLine if l.getID == "BAD_AD" => l }.get
    assert(badFormat.getCountType === VCFHeaderLineCount.R)
    assert(badFormat.getType === VCFHeaderLineType.Float)
  }

  test("reject a supported line whose type differs under STRICT stringency") {
    val format = new VCFFormatHeaderLine("DP", 1, VCFHeaderLineType.Float, "DP as floats")
    val ex = intercept[IllegalArgumentException](clean(Seq(format), ValidationStringency.STRICT))
    assert(ex.getMessage.contains("does not match supported line"))
  }
}
