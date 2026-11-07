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

class TransformAlignmentsSuite extends ADAMFunSuite {
  sparkTest("unordered sam to unordered sam") {
    val inputPath = copyResource("unordered.sam")
    val actualPath = tmpFile("unordered.sam")
    val expectedPath = inputPath
    TransformAlignments(Array("-single", "-disable_pg", inputPath, actualPath)).run(sc)
    checkFiles(expectedPath, actualPath)
  }

  sparkTest("unordered sam to ordered sam") {
    val inputPath = copyResource("unordered.sam")
    val actualPath = tmpFile("ordered.sam")
    val expectedPath = copyResource("ordered.sam")
    TransformAlignments(Array("-single", "-disable_pg", "-sort_by_reference_position", inputPath, actualPath)).run(sc)
    checkFiles(expectedPath, actualPath)
  }

  sparkTest("unordered sam, to adam, to sam") {
    val inputPath = copyResource("unordered.sam")
    val intermediateAdamPath = tmpFile("unordered.adam")
    val actualPath = tmpFile("unordered.sam")
    val expectedPath = inputPath
    TransformAlignments(Array("-disable_pg", inputPath, intermediateAdamPath)).run(sc)
    TransformAlignments(Array("-single", "-disable_pg", intermediateAdamPath, actualPath)).run(sc)
    checkFiles(expectedPath, actualPath)
  }

  sparkTest("unordered sam, to adam, to ordered sam") {
    val inputPath = copyResource("unordered.sam")
    val intermediateAdamPath = tmpFile("unordered.adam")
    val actualPath = tmpFile("ordered.sam")
    val expectedPath = copyResource("ordered.sam")
    TransformAlignments(Array("-disable_pg", inputPath, intermediateAdamPath)).run(sc)
    TransformAlignments(Array("-single", "-disable_pg", "-sort_by_reference_position", intermediateAdamPath, actualPath)).run(sc)
    checkFiles(expectedPath, actualPath)
  }

  sparkTest("put quality scores into bins") {
    val inputPath = copyResource("bqsr1.sam")
    val finalPath = tmpFile("binned.adam")
    TransformAlignments(Array(inputPath, finalPath, "-bin_quality_scores", "0,20,10;20,40,30;40,60,50")).run(sc)
    val qualityScoreCounts = sc.loadAlignments(finalPath)
      .rdd
      .flatMap(_.getQualityScores)
      .map(s => s.toInt - 33)
      .countByValue

    assert(qualityScoreCounts(30) === 92899)
    assert(qualityScoreCounts(10) === 7101)
  }

  sparkTest("run region predicate") {
    // alas, copy resource does not work here...
    val inputPath = testFile("sorted.bam")
    val outputPath1 = tmpFile("predicate.1.adam")
    val outputPath2 = tmpFile("predicate.2.adam")
    val outputPath3 = tmpFile("predicate.3.adam")
    TransformAlignments(Array(inputPath, outputPath1,
      "-region_predicate", "1:0-200,chr2:0-1000",
      "-force_load_bam")).run(sc)
    TransformAlignments(Array(outputPath1, outputPath2,
      "-region_predicate", "chr2:0-1000",
      "-force_load_parquet")).run(sc)
    TransformAlignments(Array(outputPath2, outputPath3,
      "-region_predicate", "chr2:0-400")).run(sc)

    assert(sc.loadAlignments(outputPath1).rdd.count === 3)
    assert(sc.loadAlignments(outputPath2).rdd.count === 2)
    assert(sc.loadAlignments(outputPath3).rdd.count === 1)
  }

  // ---- argument checks ----------------------------------------------------

  private def rejects(flags: String*): Unit = {
    val ex = intercept[IllegalArgumentException] {
      TransformAlignments(Array(copyResource("unordered.sam"), tmpFile("rejected.adam")) ++ flags).run(sc)
    }
    assert(ex.getMessage.nonEmpty)
  }

  sparkTest("reject -aligned_read_predicate with a non-Parquet force load flag") {
    rejects("-aligned_read_predicate", "-force_load_bam")
  }

  sparkTest("reject -limit_projection with a non-Parquet force load flag") {
    rejects("-limit_projection", "-force_load_fastq")
  }

  sparkTest("reject -aligned_read_predicate with a non-Parquet input path") {
    rejects("-aligned_read_predicate")
  }

  sparkTest("reject -limit_projection with a non-Parquet input path") {
    rejects("-limit_projection")
  }

  sparkTest("reject -aligned_read_predicate together with -region_predicate") {
    val adamPath = tmpFile("unordered.adam")
    TransformAlignments(Array("-disable_pg", copyResource("unordered.sam"), adamPath)).run(sc)
    intercept[IllegalArgumentException] {
      TransformAlignments(Array(adamPath, tmpFile("out.adam"),
        "-aligned_read_predicate", "-region_predicate", "1:0-100")).run(sc)
    }
  }

  sparkTest("reject more than one sort flag") {
    rejects("-sort_by_read_name", "-sort_by_reference_position")
  }

  test("input paths with a sequence file extension are not Parquet") {
    val ta = TransformAlignments(Array("in", "out"))
    for (ext <- Seq(".sam", ".bam", ".ifq", ".fq", ".fastq", ".fa", ".fasta")) {
      assert(ta.isNonParquet("reads" + ext), ext)
    }
    assert(!ta.isNonParquet("reads.adam"))
    assert(!ta.isNonParquet("reads"))
  }

  test("only the BAM and FASTQ force load flags force a non-Parquet load") {
    assert(!TransformAlignments(Array("in", "out")).forceNonParquet())
    assert(!TransformAlignments(Array("in", "out", "-force_load_parquet")).forceNonParquet())
    for (flag <- Seq("-force_load_bam", "-force_load_fastq", "-force_load_ifastq")) {
      assert(TransformAlignments(Array("in", "out", flag)).forceNonParquet(), flag)
    }
  }

  // ---- loading ------------------------------------------------------------

  sparkTest("load paired FASTQ with -force_load_fastq") {
    val outputPath = tmpFile("paired.adam")
    TransformAlignments(Array(copyResource("bqsr1-r1.fq"), outputPath,
      "-force_load_fastq", "-paired_fastq", copyResource("bqsr1-r2.fq"))).run(sc)
    val expected = sc.loadAlignments(copyResource("bqsr1-r1.fq")).rdd.count +
      sc.loadAlignments(copyResource("bqsr1-r2.fq")).rdd.count
    assert(sc.loadAlignments(outputPath).rdd.count === expected)
  }

  sparkTest("load interleaved FASTQ with -force_load_ifastq") {
    val inputPath = copyResource("interleaved_fastq_sample1.ifq")
    val outputPath = tmpFile("interleaved.adam")
    TransformAlignments(Array(inputPath, outputPath, "-force_load_ifastq")).run(sc)
    assert(sc.loadAlignments(outputPath).rdd.count === sc.loadInterleavedFastq(inputPath).rdd.count)
  }

  sparkTest("keep only mapped reads with -aligned_read_predicate") {
    val adamPath = tmpFile("unordered.adam")
    TransformAlignments(Array("-disable_pg", copyResource("unordered.sam"), adamPath)).run(sc)
    val outputPath = tmpFile("mapped.adam")
    TransformAlignments(Array(adamPath, outputPath, "-aligned_read_predicate")).run(sc)
    val mapped = sc.loadAlignments(adamPath).rdd.filter(_.getReadMapped).count
    assert(mapped > 0)
    assert(sc.loadAlignments(outputPath).rdd.count === mapped)
  }

  sparkTest("drop attributes and original qualities with -limit_projection") {
    val adamPath = tmpFile("unordered.adam")
    TransformAlignments(Array("-disable_pg", copyResource("unordered.sam"), adamPath)).run(sc)
    val outputPath = tmpFile("projected.adam")
    TransformAlignments(Array(adamPath, outputPath, "-limit_projection")).run(sc)
    val reads = sc.loadAlignments(outputPath).rdd.collect()
    assert(reads.nonEmpty)
    assert(reads.forall(r => r.getAttributes == null && r.getOriginalQualityScores == null))
  }

  // ---- processing step, concat and output ---------------------------------

  sparkTest("record a processing step unless -disable_pg") {
    val withPg = tmpFile("with_pg.adam")
    val withoutPg = tmpFile("without_pg.adam")
    TransformAlignments(Array(copyResource("unordered.sam"), withPg)).run(sc)
    TransformAlignments(Array("-disable_pg", copyResource("unordered.sam"), withoutPg)).run(sc)

    def adamSteps(path: String) = sc.loadAlignments(path).processingSteps
      .filter(_.getProgramName == "org.bdgenomics.adam.cli.TransformAlignments")
    assert(adamSteps(withPg).size === 1)
    assert(adamSteps(withPg).head.getId.startsWith("ADAM_"))
    assert(adamSteps(withoutPg).isEmpty)
  }

  sparkTest("concatenate a second file with -concat") {
    val first = copyResource("unordered.sam")
    val second = copyResource("small.sam")
    val outputPath = tmpFile("concat.adam")
    TransformAlignments(Array("-disable_pg", first, outputPath, "-concat", second)).run(sc)
    val expected = sc.loadAlignments(first).rdd.count + sc.loadAlignments(second).rdd.count
    assert(sc.loadAlignments(outputPath).rdd.count === expected)
  }

  sparkTest("concatenate a second BAM with -force_load_bam -concat") {
    val bam = testFile("sorted.bam")
    val outputPath = tmpFile("concat_bam.adam")
    TransformAlignments(Array("-disable_pg", bam, outputPath, "-force_load_bam", "-concat", bam)).run(sc)
    assert(sc.loadAlignments(outputPath).rdd.count === 2 * sc.loadBam(bam).rdd.count)
  }

  sparkTest("save as partitioned Parquet with -partition_by_start_pos") {
    val outputPath = tmpFile("partitioned.adam")
    TransformAlignments(Array("-disable_pg", testFile("sorted.bam"), outputPath,
      "-partition_by_start_pos", "-partition_bin_size", "1000")).run(sc)
    val reloaded = sc.loadPartitionedParquetAlignments(outputPath)
    assert(reloaded.rdd.count === sc.loadBam(testFile("sorted.bam")).rdd.count)
  }

  sparkTest("coalesce to fewer and more partitions with -coalesce") {
    for (partitions <- Seq("1", "8")) {
      val outputPath = tmpFile(s"coalesce_$partitions.adam")
      TransformAlignments(Array("-disable_pg", copyResource("unordered.sam"), outputPath,
        "-coalesce", partitions)).run(sc)
      assert(sc.loadAlignments(outputPath).rdd.count === sc.loadAlignments(copyResource("unordered.sam")).rdd.count)
    }
  }

  sparkTest("realign indels with -realign_indels") {
    val inputPath = testFile("artificial.sam")
    val outputPath = tmpFile("realigned.adam")
    TransformAlignments(Array("-disable_pg", inputPath, outputPath, "-realign_indels")).run(sc)
    assert(sc.loadAlignments(outputPath).rdd.count === sc.loadAlignments(inputPath).rdd.count)
  }
}
