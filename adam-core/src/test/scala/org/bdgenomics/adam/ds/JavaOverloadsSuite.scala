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

import java.util.Arrays.asList
import org.bdgenomics.adam.ds.ADAMContext._
import org.bdgenomics.adam.models.ReferenceRegion
import org.bdgenomics.adam.util.ADAMFunSuite

/**
 * The Java-specific overloads taking java.util collections delegate to the
 * Scala versions; check they return the same results.
 */
class JavaOverloadsSuite extends ADAMFunSuite {

  sparkTest("filter genotypes to samples from a Java list") {
    val genotypes = sc.loadGenotypes(testFile("small.vcf"))
    assert(genotypes.filterToSamples(asList("NA12892", "NA12891")).rdd.count() === 12)
  }

  sparkTest("filter alignments to read groups from a Java list") {
    val alignments = sc.loadAlignments(testFile("NA12878.sam"))
    assert(alignments.filterToReadGroups(asList("20FUK.1", "20FUK.2")).rdd.count() === 62)
  }

  sparkTest("filter features to feature types and parents from Java lists") {
    val features = sc.loadFeatures(testFile("dvl1.200.gff3"))
    assert(features.filterToFeatureTypes(asList("gene", "transcript")).rdd.count() === 94)
    assert(features.filterToParents(asList("ENSG00000107404", "ENSG00000162576")).rdd.count() === 18)
  }

  sparkTest("union with a Java list of datasets") {
    val reads1 = sc.loadAlignments(testFile("bqsr1.sam"))
    val reads2 = sc.loadAlignments(testFile("small.sam"))
    val union = reads1.union(asList(reads2))
    assert(union.rdd.count === reads1.rdd.count + reads2.rdd.count)
  }

  sparkTest("filter by overlapping regions from a Java iterable") {
    val reads = sc.loadAlignments(testFile("small.sam"))
    val regions = Seq(ReferenceRegion("1", 0L, 30000000L), ReferenceRegion("1", 100000000L, 200000000L))
    val viaScala = reads.filterByOverlappingRegions(regions).rdd.map(_.getReadName).collect().toSet
    val viaJava = reads.filterByOverlappingRegions(asList(regions: _*): java.lang.Iterable[ReferenceRegion])
      .rdd.map(_.getReadName).collect().toSet
    assert(viaScala.nonEmpty)
    assert(viaJava === viaScala)
  }
}
