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

import org.apache.spark.rdd.RDD
import org.bdgenomics.formats.avro.Alignment

private[read] object FlagStatMetrics {
  val emptyFailedQuality = new FlagStatMetrics(0, DuplicateMetrics.empty, DuplicateMetrics.empty, 0, 0, 0, 0, 0, 0, 0, 0, 0, true)
  val emptyPassedQuality = new FlagStatMetrics(0, DuplicateMetrics.empty, DuplicateMetrics.empty, 0, 0, 0, 0, 0, 0, 0, 0, 0, false)
}

private[read] object DuplicateMetrics {
  val empty = new DuplicateMetrics(0, 0, 0, 0)

  def apply(record: Alignment): (DuplicateMetrics, DuplicateMetrics) = {
    import FlagStat.b2i

    def isPrimary(record: Alignment): Boolean = {
      record.getDuplicateRead && record.getPrimaryAlignment
    }
    def isSecondary(record: Alignment): Boolean = {
      record.getDuplicateRead && !record.getPrimaryAlignment
    }

    def duplicateMetrics(f: (Alignment) => Boolean) = {
      new DuplicateMetrics(
        b2i(f(record)),
        b2i(f(record) && record.getReadMapped && record.getMateMapped),
        b2i(f(record) && record.getReadMapped && !record.getMateMapped),
        b2i(f(record) && (record.getReferenceName != record.getMateReferenceName))
      )
    }
    (duplicateMetrics(isPrimary), duplicateMetrics(isSecondary))
  }
}

private[adam] case class DuplicateMetrics(total: Long, bothMapped: Long, onlyReadMapped: Long, crossChromosome: Long) {
  def +(that: DuplicateMetrics): DuplicateMetrics = {
    new DuplicateMetrics(
      total + that.total,
      bothMapped + that.bothMapped,
      onlyReadMapped + that.onlyReadMapped,
      crossChromosome + that.crossChromosome
    )
  }
}

private[adam] case class FlagStatMetrics(total: Long, duplicatesPrimary: DuplicateMetrics, duplicatesSecondary: DuplicateMetrics,
                                         mapped: Long, pairedInSequencing: Long,
                                         read1: Long, read2: Long, properlyPaired: Long, withSelfAndMateMapped: Long,
                                         singleton: Long, withMateMappedToDiffChromosome: Long,
                                         withMateMappedToDiffChromosomeMapQ5: Long, failedQuality: Boolean) {
  def +(that: FlagStatMetrics): FlagStatMetrics = {
    assert(failedQuality == that.failedQuality, "Can't reduce passedVendorQuality with different failedQuality values")
    new FlagStatMetrics(
      total + that.total,
      duplicatesPrimary + that.duplicatesPrimary,
      duplicatesSecondary + that.duplicatesSecondary,
      mapped + that.mapped,
      pairedInSequencing + that.pairedInSequencing,
      read1 + that.read1,
      read2 + that.read2,
      properlyPaired + that.properlyPaired,
      withSelfAndMateMapped + that.withSelfAndMateMapped,
      singleton + that.singleton,
      withMateMappedToDiffChromosome + that.withMateMappedToDiffChromosome,
      withMateMappedToDiffChromosomeMapQ5 + that.withMateMappedToDiffChromosomeMapQ5,
      failedQuality
    )
  }
}

private[read] object FlagStat {

  def b2i(boolean: Boolean) = if (boolean) 1 else 0
  def b(boolean: java.lang.Boolean) = Option(boolean).exists(x => x)
  def i(int: java.lang.Integer): Int = Option(int).map(Integer2int).getOrElse(-1)

  def apply(rdd: RDD[Alignment]) = {
    rdd.map(metrics).aggregate((FlagStatMetrics.emptyFailedQuality, FlagStatMetrics.emptyPassedQuality))(
      seqOp = {
        (a, b) =>
          if (b.failedQuality) {
            (a._1 + b, a._2)
          } else {
            (a._1, a._2 + b)
          }
      },
      combOp = {
        (a, b) =>
          (a._1 + b._1, a._2 + b._2)
      }
    )
  }

  /** The metrics one record contributes. */
  private[read] def metrics(p: Alignment): FlagStatMetrics = {
    val record = new RecordFlags(p)
    val (primaryDuplicates, secondaryDuplicates) = DuplicateMetrics(p)
    new FlagStatMetrics(
      1,
      primaryDuplicates, secondaryDuplicates,
      b2i(b(p.getReadMapped)),
      record.pairedInSequencing,
      record.read1,
      record.read2,
      record.properlyPaired,
      record.withSelfAndMateMapped,
      record.singleton,
      record.withMateMappedToDiffChromosome,
      record.withMateMappedToDiffChromosomeMapQ5,
      p.getFailedVendorQualityChecks
    )
  }

  /**
   * The pair-related flags of one record, as 0 or 1. Pair metrics only count
   * primary, non-supplementary records; each test is evaluated in the same
   * order as samtools flagstat's conditions read, left to right.
   */
  private class RecordFlags(p: Alignment) {
    private lazy val counted = b(!p.getSupplementaryAlignment) && p.getPrimaryAlignment
    private def paired = b(p.getReadPaired)
    private def mateMappedToDiffChromosome =
      p.getReadPaired && p.getReadMapped && p.getMateMapped && (p.getReferenceName != p.getMateReferenceName)

    def pairedInSequencing: Int = b2i(paired && counted)
    def read1: Int = b2i(paired && p.getReadInFragment == 0 && counted)
    def read2: Int = b2i(paired && p.getReadInFragment == 1 && counted)
    def properlyPaired: Int = b2i(paired && b(p.getProperPair) && counted)
    def withSelfAndMateMapped: Int = b2i(paired && b(p.getReadMapped) && b(p.getMateMapped) && counted)
    def singleton: Int = b2i(paired && b(p.getReadMapped) && b(!p.getMateMapped) && counted)
    def withMateMappedToDiffChromosome: Int = b2i(b(mateMappedToDiffChromosome) && counted)
    def withMateMappedToDiffChromosomeMapQ5: Int =
      b2i(b(mateMappedToDiffChromosome && i(p.getMappingQuality) >= 5) && counted)
  }
}
