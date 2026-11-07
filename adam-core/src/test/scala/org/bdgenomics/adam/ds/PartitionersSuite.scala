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
package org.bdgenomics.adam.ds

import org.bdgenomics.adam.models.{ ReferencePosition, ReferenceRegion, SequenceDictionary, SequenceRecord }
import org.scalatest.FunSuite

class PartitionersSuite extends FunSuite {

  private val sd = SequenceDictionary(SequenceRecord("chr1", 1000L), SequenceRecord("chr2", 500L))

  test("ReferencePartitioner puts each reference in its own partition") {
    val partitioner = ReferencePartitioner(sd)
    assert(partitioner.numPartitions === 2)
    assert(partitioner.getPartition(ReferencePosition("chr1", 10L)) === 0)
    assert(partitioner.getPartition(ReferenceRegion("chr2", 10L, 20L)) === 1)
  }

  test("ReferencePartitioner rejects other keys and unknown references") {
    val partitioner = ReferencePartitioner(sd)
    intercept[IllegalArgumentException](partitioner.getPartition("chr1"))
    intercept[AssertionError](partitioner.getPartition(ReferencePosition("chrX", 1L)))
  }

  test("GenomicRegionPartitioner bins regions by position across references") {
    // chr1: 1000 / 100 = 10 bins, chr2 follows from bin 10
    val partitioner = GenomicRegionPartitioner(100L, Map("chr1" -> 1000L, "chr2" -> 500L))
    assert(partitioner.getPartition(ReferenceRegion("chr1", 0L, 10L)) === 0)
    assert(partitioner.getPartition(ReferenceRegion("chr1", 250L, 260L)) === 2)
    assert(partitioner.getPartition(ReferenceRegion("chr2", 150L, 160L)) === 11)
  }

  test("GenomicRegionPartitioner accepts (region, value) keys") {
    val partitioner = GenomicRegionPartitioner(100L, Map("chr1" -> 1000L))
    assert(partitioner.getPartition((ReferenceRegion("chr1", 250L, 260L), "value")) === 2)
  }

  test("GenomicRegionPartitioner rejects other keys and unknown references") {
    val partitioner = GenomicRegionPartitioner(100L, Map("chr1" -> 1000L))
    intercept[IllegalArgumentException](partitioner.getPartition("chr1"))
    intercept[IllegalArgumentException](partitioner.getPartition(ReferenceRegion("chrX", 0L, 1L)))
  }
}
