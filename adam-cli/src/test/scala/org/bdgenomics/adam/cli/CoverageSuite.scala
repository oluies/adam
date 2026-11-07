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
package org.bdgenomics.adam.cli

import org.bdgenomics.adam.ds.ADAMContext._
import org.bdgenomics.adam.models.{ Coverage => CoverageRecord }
import org.bdgenomics.adam.util.ADAMFunSuite
import org.bdgenomics.utils.cli.Args4j

class CoverageSuite extends ADAMFunSuite {

  sparkTest("correctly calculates coverage from small sam file") {
    val inputPath = copyResource("artificial.sam")
    val outputPath = tmpFile("coverage.adam")

    val args: Array[String] = Array(inputPath, outputPath)
    new Coverage(Args4j[CoverageArgs](args)).run(sc)
    val coverage = sc.loadCoverage(outputPath)

    val pointCoverage = coverage.flatten.rdd.filter(_.start == 30).first
    assert(pointCoverage.count == 5)
  }

  private def coverage(flags: String*): Seq[CoverageRecord] = {
    val outputPath = tmpFile("coverage.adam")
    new Coverage(Args4j[CoverageArgs](Array(copyResource("artificial.sam"), outputPath) ++ flags)).run(sc)
    sc.loadCoverage(outputPath).rdd.collect().toSeq
  }

  private def totalCount(c: Seq[CoverageRecord]): Double = c.map(r => r.count * (r.end - r.start)).sum

  sparkTest("coverage of one strand is at most the coverage of both") {
    val both = totalCount(coverage())
    val negative = totalCount(coverage("-only_negative_strands"))
    val positive = totalCount(coverage("-only_positive_strands"))
    assert(negative + positive === both)
  }

  sparkTest("collapse neighbouring records, sorted by index or lexicographically") {
    val uncollapsed = coverage()
    for (flags <- Seq(Seq("-collapse"), Seq("-collapse", "-sort_lexicographically"))) {
      val collapsed = coverage(flags: _*)
      assert(collapsed.size <= uncollapsed.size, flags.mkString(" "))
      assert(totalCount(collapsed) === totalCount(uncollapsed), flags.mkString(" "))
    }
  }

  sparkTest("reject -sort_lexicographically without -collapse") {
    intercept[IllegalArgumentException](coverage("-sort_lexicographically"))
  }

  sparkTest("reject both strand options at once") {
    intercept[IllegalArgumentException](coverage("-only_negative_strands", "-only_positive_strands"))
  }
}
