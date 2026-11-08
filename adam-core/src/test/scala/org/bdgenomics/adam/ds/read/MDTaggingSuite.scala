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
package org.bdgenomics.adam.ds.read

import htsjdk.samtools.ValidationStringency
import org.apache.spark.rdd.RDD
import org.bdgenomics.adam.util.{ ADAMFunSuite, ReferenceMap }
import org.bdgenomics.formats.avro.{ Alignment, Reference, Slice }

class MDTaggingSuite extends ADAMFunSuite {
  val chr1 =
    Reference
      .newBuilder()
      .setName("chr1")
      .setLength(100L)
      .build()
  val chr2 =
    Reference
      .newBuilder()
      .setName("chr2")
      .setLength(100L)
      .build()

  def makeFrags(frags: (Reference, Int, String)*): RDD[Slice] =
    sc.parallelize(
      for {
        (reference, start, seq) <- frags
      } yield (
        Slice.newBuilder()
        .setTotalLength(reference.getLength)
        .setName(reference.getName)
        .setStart(start.toLong)
        .setEnd(start.toLong + seq.length)
        .setSequence(seq).build()
      )
    )

  def makeReads(reads: ((Reference, Int, Int, String, String), String)*): (Map[Int, String], RDD[Alignment]) = {
    val (map, rs) =
      (for {
        (((contig, start, end, seq, cigar), mdTag), id) <- reads.zipWithIndex
      } yield (
        id -> mdTag,
        Alignment
        .newBuilder
        .setReferenceName(contig.getName)
        .setStart(start.toLong)
        .setEnd(end.toLong)
        .setSequence(seq)
        .setCigar(cigar)
        .setReadMapped(true)
        .setReadName(id.toString)
        .build()
      )).unzip

    (map.toMap, sc.parallelize(rs))
  }

  def testReads(fs: (Reference, Int, String)*)(rs: ((Reference, Int, Int, String, String), String)*)(accumulators: (Long, Long, Long, Long)): Unit = {
    val (expectedReadMap, reads) = makeReads(rs: _*)

    def check(mdTagging: MDTagging): Unit = {
      val actualReadMap = mdTagging.taggedReads.collect.map(r => r.getReadName.toInt -> r.getMismatchingPositions).toMap

      assert(actualReadMap == expectedReadMap)
      assert(
        (
          mdTagging.mdTagsAdded.value,
          mdTagging.mdTagsExtant.value,
          mdTagging.numUnmappedReads.value,
          mdTagging.incorrectMDTags.value
        ) === accumulators
      )
    }

    check(MDTagging(reads, ReferenceMap(makeFrags(fs: _*))))
  }

  sparkTest("test adding MDTags over boundary") {
    testReads(
      (chr1, 0, "TTTTTTTTTT"),
      (chr1, 10, "A")
    )(
        (chr1, 9, 11, "TA", "2M") → "2",
        (chr1, 9, 11, "TG", "2M") → "1A0",
        (chr1, 9, 11, "GA", "2M") → "0T1",
        (chr1, 9, 11, "TAA", "2M1I") → "2",
        (chr1, 9, 11, "A", "1D1M") → "0^T1",
        (chr1, 9, 11, "G", "1D1M") → "0^T0A0"
      )(
          (6, 0, 0, 0)
        )
  }

  sparkTest("test adding MDTags; reads span full contig") {
    testReads(
      (chr1, 0, "AAAAAAAAAA"),
      (chr1, 10, "CCCCCCCCCC"),
      (chr1, 20, "GGGGGGGG")
    )(
        (chr1, 0, 28, "AAAAAAAAAACCCCCCCCCCGGGGGGGG", "28M") → "28",
        (chr1, 0, 28, "TAAAAAAAAACCCCCCCCCCGGGGGGGG", "28M") → "0A27",
        (chr1, 0, 28, "AAAAAAAAAACCCCCCCCCCGGGGGGGT", "28M") → "27G0",
        (chr1, 0, 28, "AAAAAAAAATTCCCCCCCCCGGGGGGGG", "28M") → "9A0C17",
        (chr1, 0, 28, "AAAAAAAAAACCCCCCCCCTTGGGGGGG", "28M") → "19C0G7"
      )((5, 0, 0, 0))
  }

  sparkTest("test adding MDTags; reads start inside first fragment") {
    testReads(
      (chr1, 0, "AAAAAAAAAA"),
      (chr1, 10, "CCCCCCCCCC"),
      (chr1, 20, "GGGGGGGG")
    )(
        (chr1, 1, 28, "AAAAAAAAACCCCCCCCCCGGGGGGGG", "27M") → "27",
        (chr1, 1, 28, "TAAAAAAAACCCCCCCCCCGGGGGGGG", "27M") → "0A26",
        (chr1, 1, 28, "AAAAAAAAACCCCCCCCCCGGGGGGGT", "27M") → "26G0",
        (chr1, 1, 28, "AAAAAAAATTCCCCCCCCCGGGGGGGG", "27M") → "8A0C17",
        (chr1, 1, 28, "AAAAAAAAACCCCCCCCCTTGGGGGGG", "27M") → "18C0G7"
      )(5, 0, 0, 0)
  }

  sparkTest("test adding MDTags; reads end inside last fragment") {
    testReads(
      (chr1, 0, "AAAAAAAAAA"),
      (chr1, 10, "CCCCCCCCCC"),
      (chr1, 20, "GGGGGGGG")
    )(
        (chr1, 0, 27, "AAAAAAAAAACCCCCCCCCCGGGGGGG", "27M") → "27",
        (chr1, 0, 27, "TAAAAAAAAACCCCCCCCCCGGGGGGG", "27M") → "0A26",
        (chr1, 0, 27, "AAAAAAAAAACCCCCCCCCCGGGGGGT", "27M") → "26G0",
        (chr1, 0, 27, "AAAAAAAAATTCCCCCCCCCGGGGGGG", "27M") → "9A0C16",
        (chr1, 0, 27, "AAAAAAAAAACCCCCCCCCTTGGGGGG", "27M") → "19C0G6"
      )(5, 0, 0, 0)
  }

  sparkTest("test adding MDTags; reads start inside first fragment and end inside last fragment") {
    testReads(
      (chr1, 0, "AAAAAAAAAA"),
      (chr1, 10, "CCCCCCCCCC"),
      (chr1, 20, "GGGGGGGG")
    )(
        (chr1, 1, 27, "AAAAAAAAACCCCCCCCCCGGGGGGG", "26M") → "26",
        (chr1, 1, 27, "TAAAAAAAACCCCCCCCCCGGGGGGG", "26M") → "0A25",
        (chr1, 1, 27, "AAAAAAAAACCCCCCCCCCGGGGGGT", "26M") → "25G0",
        (chr1, 1, 27, "AAAAAAAATTCCCCCCCCCGGGGGGG", "26M") → "8A0C16",
        (chr1, 1, 27, "AAAAAAAAACCCCCCCCCTTGGGGGG", "26M") → "18C0G6"
      )(5, 0, 0, 0)
  }

  sparkTest("test adding MDTags; reads start and end in middle fragements") {
    testReads(
      (chr1, 0, "TTTTTTTTTT"),
      (chr1, 10, "AAAAAAAAAA"),
      (chr1, 20, "CCCCCCCCCC"),
      (chr1, 30, "GGGGGGGGGG"),
      (chr1, 40, "TTTTTTTTTT")
    )(
        (chr1, 15, 35, "AAAAACCCCCCCCCCGGGGG", "20M") → "20",
        (chr1, 15, 35, "TAAAACCCCCCCCCCGGGGG", "20M") → "0A19",
        (chr1, 15, 35, "AAAAACCCCCCCCCCGGGGT", "20M") → "19G0",
        (chr1, 15, 35, "AAAATTCCCCCCCCCGGGGG", "20M") → "4A0C14",
        (chr1, 15, 35, "AAAAACCCCCCCCCTTGGGG", "20M") → "14C0G4"
      )(5, 0, 0, 0)
  }

  sparkTest("try realigning a read on a missing contig, stringency == STRICT") {
    val read = (chr2, 15, 35, "AAAAACCCCCCCCCCGGGGG", "20M") → "20"
    val tagger = MDTagging(makeReads(read)._2,
      ReferenceMap(makeFrags((chr1, 0, "TTTTTTTTTT"))))
    intercept[Exception] {
      tagger.taggedReads.collect
    }
  }

  sparkTest("try realigning a read on a missing contig, stringency == LENIENT") {
    val read = (chr2, 15, 35, "AAAAACCCCCCCCCCGGGGG", "20M") → "20"
    val tagger = MDTagging(makeReads(read)._2,
      ReferenceMap(makeFrags((chr1, 0, "TTTTTTTTTT"))),
      validationStringency = ValidationStringency.LENIENT)
    assert(tagger.taggedReads.collect.size === 1)
  }

  /** One read on chr1 at 0-4 with sequence ACGT, against reference ACGT, carrying the given MD tag. */
  private def taggedRead(mdTag: String): RDD[Alignment] = {
    sc.parallelize(Seq(Alignment.newBuilder
      .setReferenceName("chr1")
      .setStart(0L)
      .setEnd(4L)
      .setSequence("ACGT")
      .setCigar("4M")
      .setReadMapped(true)
      .setReadName("0")
      .setMismatchingPositions(mdTag)
      .build()))
  }

  private def tagging(mdTag: String, overwrite: Boolean, stringency: ValidationStringency): MDTagging = {
    MDTagging(taggedRead(mdTag), ReferenceMap(makeFrags((chr1, 0, "ACGT"))),
      overwriteExistingTags = overwrite, validationStringency = stringency)
  }

  sparkTest("keep a correct extant MD tag") {
    val mdTagging = tagging("4", overwrite = false, ValidationStringency.STRICT)
    assert(mdTagging.taggedReads.collect.map(_.getMismatchingPositions).toSeq === Seq("4"))
    assert(mdTagging.mdTagsExtant.value === 1)
    assert(mdTagging.incorrectMDTags.value === 0)
  }

  sparkTest("overwrite an incorrect extant MD tag when asked") {
    val mdTagging = tagging("0A3", overwrite = true, ValidationStringency.STRICT)
    assert(mdTagging.taggedReads.collect.map(_.getMismatchingPositions).toSeq === Seq("4"))
    assert(mdTagging.incorrectMDTags.value === 1)
  }

  sparkTest("keep an incorrect extant MD tag under LENIENT and SILENT stringency") {
    for (stringency <- Seq(ValidationStringency.LENIENT, ValidationStringency.SILENT)) {
      val mdTagging = tagging("0A3", overwrite = false, stringency)
      assert(mdTagging.taggedReads.collect.map(_.getMismatchingPositions).toSeq === Seq("0A3"), stringency)
      assert(mdTagging.incorrectMDTags.value === 1, stringency)
    }
  }

  sparkTest("reject an incorrect extant MD tag under STRICT stringency") {
    val ex = intercept[org.apache.spark.SparkException] {
      tagging("0A3", overwrite = false, ValidationStringency.STRICT).taggedReads.collect
    }
    assert(ex.getCause.isInstanceOf[IncorrectMDTagException] || ex.getMessage.contains("IncorrectMDTagException"))
  }
}
