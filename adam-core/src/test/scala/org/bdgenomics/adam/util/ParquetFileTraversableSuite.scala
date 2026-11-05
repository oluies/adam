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
package org.bdgenomics.adam.util

import java.nio.file.Files
import org.bdgenomics.adam.ds.ADAMContext._
import org.bdgenomics.formats.avro.Alignment

class ParquetFileTraversableSuite extends ADAMFunSuite {

  /** Saves small.sam as Parquet in two part files and returns the path and the read names. */
  private def saveReads(): (String, Seq[String]) = {
    val path = tmpLocation()
    val reads = sc.loadAlignments(testFile("small.sam"))
    reads.transform((rdd: org.apache.spark.rdd.RDD[Alignment]) => rdd.repartition(2)).saveAsParquet(path)
    (path, reads.rdd.map(_.getReadName).collect().toSeq)
  }

  sparkTest("foreach, iterator and headOption read every part file") {
    val (path, readNames) = saveReads()
    val records = new ParquetFileTraversable[Alignment](sc, path)

    var count = 0
    records.foreach(_ => count += 1)
    assert(count === readNames.size)
    assert(records.iterator.size === readNames.size)
    assert(records.map(_.getReadName).toSeq.sorted === readNames.sorted)
    assert(records.headOption.exists(r => readNames.contains(r.getReadName)))
  }

  sparkTest("headOption and foreach can be repeated") {
    val (path, readNames) = saveReads()
    val records = new ParquetFileTraversable[Alignment](sc, path)

    (1 to 3).foreach(_ => assert(records.headOption.isDefined))
    (1 to 3).foreach(_ => assert(records.size === readNames.size))
  }

  sparkTest("an empty directory has no records") {
    val emptyDir = Files.createTempDirectory("empty").toString
    val records = new ParquetFileTraversable[Alignment](sc, emptyDir)

    assert(records.headOption === None)
    assert(records.isEmpty)
  }

  sparkTest("a missing path is rejected") {
    intercept[IllegalArgumentException] {
      new ParquetFileTraversable[Alignment](sc, tmpLocation(".missing"))
    }
  }
}
