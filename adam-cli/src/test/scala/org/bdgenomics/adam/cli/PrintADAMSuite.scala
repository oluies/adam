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
import org.bdgenomics.adam.util.ADAMFunSuite
import scala.io.Source

class PrintADAMSuite extends ADAMFunSuite {

  private def toAdam(resource: String, name: String): (String, Long) = {
    val adamPath = tmpFile(name)
    TransformAlignments(Array("-disable_pg", copyResource(resource), adamPath)).run(sc)
    (adamPath, sc.loadAlignments(adamPath).rdd.count)
  }

  private def readLines(path: String): Seq[String] = {
    val source = Source.fromFile(path)
    try source.getLines().toList finally source.close()
  }

  private def countOccurrences(text: String, token: String): Int = {
    text.sliding(token.length).count(_ == token)
  }

  sparkTest("print records as text to a file") {
    val (adamPath, records) = toAdam("unordered.sam", "unordered.adam")
    val outputPath = tmpFile("print.txt")

    PrintADAM(Array(adamPath, "-o", outputPath)).run(sc)

    assert(readLines(outputPath).count(_.nonEmpty) === records)
  }

  sparkTest("print records as JSON to a file") {
    val (adamPath, records) = toAdam("unordered.sam", "unordered.adam")
    val outputPath = tmpFile("print.json")

    PrintADAM(Array(adamPath, "-pretty", "-o", outputPath)).run(sc)

    val json = readLines(outputPath).mkString("\n")
    assert(countOccurrences(json, "\"readName\"") === records)
  }

  sparkTest("print every input file to the same output file") {
    val (firstPath, firstRecords) = toAdam("unordered.sam", "first.adam")
    val (secondPath, secondRecords) = toAdam("small.sam", "second.adam")
    val outputPath = tmpFile("print.txt")

    PrintADAM(Array(firstPath, secondPath, "-o", outputPath)).run(sc)

    assert(readLines(outputPath).count(_.nonEmpty) === firstRecords + secondRecords)
  }

  sparkTest("refuse to overwrite an existing output file") {
    val (adamPath, _) = toAdam("unordered.sam", "unordered.adam")
    val outputPath = copyResource("unordered.sam")

    intercept[org.apache.hadoop.mapred.FileAlreadyExistsException] {
      PrintADAM(Array(adamPath, "-o", outputPath)).run(sc)
    }
  }
}
