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

import grizzled.slf4j.Logging
import htsjdk.samtools.{
  SAMFileHeader,
  SAMProgramRecord,
  SAMReadGroupRecord,
  SAMRecord,
  SAMUtils
}
import org.bdgenomics.adam.models.{
  Alphabet,
  Attribute,
  ReadGroupDictionary,
  SequenceDictionary
}
import org.bdgenomics.adam.rich.RichAlignment
import org.bdgenomics.adam.util.AttributeUtils
import org.bdgenomics.formats.avro.{
  Alignment,
  Fragment,
  ProcessingStep
}
import scala.jdk.CollectionConverters._

/**
 * Conversions between Alignments and other formats.
 */
class AlignmentConverter extends Serializable with Logging {

  /**
   * Returns true if a tag should not be kept in the attributes field.
   *
   * The SAM/BAM format supports attributes, which is a key/value pair map. In
   * ADAM, we have promoted some of these fields to "primary" fields, so that we
   * can more efficiently access them. These include the MD tag, which describes
   * substitutions against the reference; the OQ tag, which describes the
   * original read base quality scores; and the OP and OC tags, which describe the
   * original read alignment position and CIGAR.
   *
   * @param attrTag Tag name to check.
   * @return Returns true if the tag should be skipped.
   */
  private[converters] def skipTag(attrTag: String): Boolean = attrTag match {
    case "OQ" => true
    case "OP" => true
    case "OC" => true
    case "MD" => true
    case _    => false
  }

  /**
   * Converts a SAM record into an Avro Alignment.
   *
   * @param samRecord Record to convert.
   * @return Returns the original record converted into Avro.
   */
  def convert(samRecord: SAMRecord): Alignment = {
    try {
      val cigar: String = samRecord.getCigarString
      val (startTrim, endTrim) = hardClipTrims(cigar)

      val builder: Alignment.Builder = Alignment.newBuilder
        .setReadName(samRecord.getReadName)
        .setSequence(samRecord.getReadString)
        .setCigar(cigar)
        .setBasesTrimmedFromStart(startTrim)
        .setBasesTrimmedFromEnd(endTrim)
        .setOriginalQualityScores(SAMUtils.phredToFastq(samRecord.getOriginalBaseQualities))

      // if the quality scores string is "*", then we null it in the record
      // or, in other words, we only set the quality scores if it is not "*"
      val qualityScores = samRecord.getBaseQualityString
      if (qualityScores != "*") {
        builder.setQualityScores(qualityScores)
      }

      // Only set the reference information if the read is aligned, matching the mate reference
      // This prevents looking up a -1 in the sequence dictionary
      if (samRecord.getReferenceIndex != SAMRecord.NO_ALIGNMENT_REFERENCE_INDEX) {
        setAlignment(builder, samRecord)
      }

      // set mapping flags
      // oddly enough, it appears that reads can show up with mapping
      // info (mapq, cigar, position)
      // even if the read unmapped flag is set...

      // While the meaning of the ReadMapped, ReadNegativeStand,
      // PrimaryAlignmentFlag and SupplementaryAlignmentFlag
      // are unclear when the read is not mapped or reference is not defined,
      // it is nonetheless favorable to set these flags in the ADAM file
      // in same way as they appear in the input BAM inorder to match exactly
      // the statistics output by other programs, specifically Samtools Flagstat

      builder.setReadMapped(!samRecord.getReadUnmappedFlag)
      builder.setReadNegativeStrand(samRecord.getReadNegativeStrandFlag)
      builder.setPrimaryAlignment(!samRecord.getNotPrimaryAlignmentFlag)
      builder.setSupplementaryAlignment(samRecord.getSupplementaryAlignmentFlag)

      setMate(builder, samRecord)
      setFlags(builder, samRecord)
      setTagsAndReadGroup(builder, samRecord)

      builder.build
    } catch {
      case t: Throwable => {
        error("Conversion of read: " + samRecord + " failed.")
        throw t
      }
    }
  }

  /** Bases hard clipped from the start and the end of the read, from its CIGAR. */
  private def hardClipTrims(cigar: String): (Int, Int) = {
    val startTrim = if (cigar == "*") {
      0
    } else {
      val count = cigar.takeWhile(_.isDigit).toInt
      val operator = cigar.dropWhile(_.isDigit).head
      if (operator == 'H') count else 0
    }
    val endTrim = if (cigar.endsWith("H")) {
      // must reverse string as takeWhile is not implemented in reverse direction
      cigar.dropRight(1).reverse.takeWhile(_.isDigit).reverse.toInt
    } else {
      0
    }
    (startTrim, endTrim)
  }

  /** Reference, positions, original alignment and mapping quality of an aligned read. */
  private def setAlignment(builder: Alignment.Builder, samRecord: SAMRecord): Unit = {
    builder.setReferenceName(samRecord.getReferenceName)

    // set read alignment flag
    val start: Int = samRecord.getAlignmentStart
    assert(start != 0, "Start cannot equal 0 if reference is set.")
    builder.setStart(start - 1L)

    // set OP and OC flags, if applicable
    if (samRecord.getAttribute("OP") != null) {
      builder.setOriginalStart(samRecord.getIntegerAttribute("OP").toLong - 1)
      builder.setOriginalCigar(samRecord.getStringAttribute("OC"))
    }

    val end = start.toLong - 1 + samRecord.getCigar.getReferenceLength
    builder.setEnd(end)

    val mapq: Int = samRecord.getMappingQuality
    if (mapq != SAMRecord.UNKNOWN_MAPPING_QUALITY) {
      builder.setMappingQuality(mapq)
    }
  }

  /** Reference and position of the mate/next segment. */
  private def setMate(builder: Alignment.Builder, samRecord: SAMRecord): Unit = {
    if (samRecord.getMateReferenceIndex != SAMRecord.NO_ALIGNMENT_REFERENCE_INDEX) {
      builder.setMateReferenceName(samRecord.getMateReferenceName)

      val mateStart = samRecord.getMateAlignmentStart
      if (mateStart > 0) {
        // We subtract one here to be 0-based offset
        builder.setMateAlignmentStart(mateStart - 1L)
      }
    }
  }

  /**
   * The flags that are set. The Avro scheme defines all flags as defaulting
   * to 'false', so only the true ones are set.
   */
  private def setFlags(builder: Alignment.Builder, samRecord: SAMRecord): Unit = {
    if (samRecord.getFlags != 0) {
      if (samRecord.getReadPairedFlag) {
        setPairFlags(builder, samRecord)
      }
      if (samRecord.getDuplicateReadFlag) {
        builder.setDuplicateRead(true)
      }
      if (samRecord.getReadFailsVendorQualityCheckFlag) {
        builder.setFailedVendorQualityChecks(true)
      }
    }
  }

  private def setPairFlags(builder: Alignment.Builder, samRecord: SAMRecord): Unit = {
    builder.setReadPaired(true)
    if (samRecord.getMateNegativeStrandFlag) {
      builder.setMateNegativeStrand(true)
    }
    if (!samRecord.getMateUnmappedFlag) {
      builder.setMateMapped(true)
    }
    if (samRecord.getProperPairFlag) {
      builder.setProperPair(true)
    }
    if (samRecord.getFirstOfPairFlag) {
      builder.setReadInFragment(0)
    }
    if (samRecord.getSecondOfPairFlag) {
      builder.setReadInFragment(1)
    }
  }

  /** Insert size, MD tag, other tags (except the ones ADAM stores as fields) and read group. */
  private def setTagsAndReadGroup(builder: Alignment.Builder, samRecord: SAMRecord): Unit = {
    val tlen = samRecord.getInferredInsertSize
    if (tlen != 0) {
      builder.setInsertSize(tlen.toLong)
    }

    var tags = List[Attribute]()
    if (samRecord.getAttributes != null) {
      samRecord.getAttributes.asScala.foreach {
        attr =>
          if (attr.tag == "MD") {
            builder.setMismatchingPositions(attr.value.toString)
          } else if (!skipTag(attr.tag)) {
            tags ::= AttributeUtils.convertSAMTagAndValue(attr)
          }
      }
    }
    if (tags.nonEmpty) {
      builder.setAttributes(tags.mkString("\t"))
    }

    val recordGroup: SAMReadGroupRecord = samRecord.getReadGroup
    if (recordGroup != null) {
      builder.setReadGroupId(recordGroup.getReadGroupId)
        .setReadGroupSampleId(recordGroup.getSample)
    }
  }

  /**
   * Prepare a single record for conversion to FASTQ and similar formats by
   * splitting into a tuple of (name, sequence, qualityScores).
   *
   * If the base quality scores are unknown (qualityScores is null or equals "*"),
   * the quality scores will be a repeated string of 'B's that is equal to the read
   * length.
   *
   * @param adamRecord Read to prepare for conversion to FASTQ and similar formats.
   * @param maybeAddSuffix If true, check if a "/%d" suffix is attached to the
   *   read. If there is no suffix, a slash and the number of the read in the
   *   sequenced fragment is appended to the readname. Default is false.
   * @param writeOriginalQualityScores If true and the original base quality
   *   scores field is set (SAM "OQ" tag), outputs the original quality scores. Else,
   *   output the qualityScores field. Defaults to false.
   * @return Returns tuple of (name, sequence, qualityScores).
   */
  private def prepareFastq(
    adamRecord: Alignment,
    maybeAddSuffix: Boolean,
    writeOriginalQualityScores: Boolean): (String, String, String) = {

    val readNameSuffix =
      if (maybeAddSuffix &&
        !AlignmentConverter.readNameHasPairedSuffix(adamRecord) &&
        adamRecord.getReadPaired) {
        "/%d".format(adamRecord.getReadInFragment + 1)
      } else {
        ""
      }

    //"B" is used to represent "unknown quality score"
    //https://en.wikipedia.org/wiki/FASTQ_format#cite_note-7
    //FastQ format quality score string must be the same length as the sequence string
    //https://en.wikipedia.org/wiki/FASTQ_format#Format
    val seqLength =
      if (adamRecord.getSequence == null)
        0
      else
        adamRecord.getSequence.length
    val qualityScores =
      if (writeOriginalQualityScores && adamRecord.getOriginalQualityScores != null)
        if (adamRecord.getOriginalQualityScores == "*")
          "B" * seqLength
        else
          adamRecord.getOriginalQualityScores
      else if (adamRecord.getQualityScores == null)
        "B" * seqLength
      else
        adamRecord.getQualityScores

    (
      adamRecord.getReadName + readNameSuffix,
      if (adamRecord.getReadNegativeStrand)
        Alphabet.dna.reverseComplement(adamRecord.getSequence)
      else
        adamRecord.getSequence,
      if (adamRecord.getReadNegativeStrand)
        qualityScores.reverse
      else
        qualityScores
    )
  }

  /**
   * Converts a single record to FASTQ format.
   *
   * FASTQ format is:
   * {{{
   * @readName
   * sequence
   * +<optional readname>
   * ASCII quality scores
   * }}}
   *
   * If the base quality scores are unknown (qualityScores is null or equals "*"),
   * the quality scores will be a repeated string of 'B's that is equal to the read
   * length.
   *
   * @param adamRecord Read to convert to FASTQ.
   * @param maybeAddSuffix If true, check if a "/%d" suffix is attached to the
   *   read. If there is no suffix, a slash and the number of the read in the
   *   sequenced fragment is appended to the readname. Default is false.
   * @param writeOriginalQualityScores If true and the original base quality
   *   score field is set (SAM "OQ" tag), outputs the original quality scores. Else,
   *   output the qualityScores field. Defaults to false.
   * @return Returns this read in string form.
   */
  def convertToFastq(
    adamRecord: Alignment,
    maybeAddSuffix: Boolean = false,
    writeOriginalQualityScores: Boolean = false): String = {

    val (name, sequence, qualityScores) =
      prepareFastq(adamRecord, maybeAddSuffix, writeOriginalQualityScores)

    "@%s\n%s\n+\n%s".format(name, sequence, qualityScores)
  }

  /**
   * Converts a single record to Bowtie tab6 format.
   *
   * In Bowtie tab6 format, each alignment or pair is on a single line.
   * An unpaired alignment line is [name]\t[seq]\t[qualityScores]\n.
   * For paired-end alignments, the second end can have a different name
   * from the first: [name1]\t[seq1]\t[qualityScores1]\t[name2]\t[seq2]\t[qualityScores2]\n.
   *
   * If the base quality scores are unknown (qualityScores is null or equals "*"),
   * the quality scores will be a repeated string of 'B's that is equal to the read
   * length.
   *
   * @param adamRecord Read to convert to FASTQ.
   * @param maybeAddSuffix If true, check if a "/%d" suffix is attached to the
   *   read. If there is no suffix, a slash and the number of the read in the
   *   sequenced fragment is appended to the readname. Default is false.
   * @param writeOriginalQualityScores If true and the original base quality
   *   scores field is set (SAM "OQ" tag), outputs the original quality scores. Else,
   *   output the qualityScores field. Defaults to false.
   * @return Returns this read in string form.
   */
  def convertToTab6(
    adamRecord: Alignment,
    maybeAddSuffix: Boolean = false,
    writeOriginalQualityScores: Boolean = false): String = {

    val (name, sequence, qualityScores) =
      prepareFastq(adamRecord, maybeAddSuffix, writeOriginalQualityScores)

    "%s\t%s\t%s".format(name, sequence, qualityScores)
  }

  /**
   * Converts a single record to Bowtie tab5 format.
   *
   * In Bowtie tab5 format, each alignment or pair is on a single line.
   * An unpaired alignment line is [name]\t[seq]\t[qualityScores]\n.
   * A paired-end read line is [name]\t[seq1]\t[qualityScores1]\t[seq2]\t[qualityScores2]\n.
   *
   * The index suffix will be trimmed from the read name if present.
   *
   * If the base quality scores are unknown (qualityScores is null or equals "*"),
   * the quality scores will be a repeated string of 'B's that is equal to the read
   * length.
   *
   * @param adamRecord Read to convert to FASTQ.
   * @param writeOriginalQualityScores If true and the original base quality
   *   scores field is set (SAM "OQ" tag), outputs the original quality scores. Else,
   *   output the qualityScores field. Defaults to false.
   * @return Returns this read in string form.
   */
  def convertToTab5(
    adamRecord: Alignment,
    writeOriginalQualityScores: Boolean = false): String = {

    val (name, sequence, qualityScores) =
      prepareFastq(adamRecord, maybeAddSuffix = false, writeOriginalQualityScores)

    "%s\t%s\t%s".format(trimSuffix(name), sequence, qualityScores)
  }

  /**
   * Converts a single record representing the second read of a pair to Bowtie
   * tab5 format.
   *
   * In Bowtie tab5 format, each alignment or pair is on a single line.
   * An unpaired alignment line is [name]\t[seq]\t[qualityScores]\n.
   * A paired-end read line is [name]\t[seq1]\t[qualityScores1]\t[seq2]\t[qualityScores2]\n.
   *
   * If the base quality scores are unknown (qualityScores is null or equals "*"),
   * the quality scores will be a repeated string of 'B's that is equal to the read
   * length.
   *
   * @param adamRecord Read to convert to FASTQ.
   * @param writeOriginalQualityScores If true and the original base quality
   *   scores field is set (SAM "OQ" tag), outputs the original quality scores. Else,
   *   output the qualityScores field. Defaults to false.
   * @return Returns this read in string form.
   */
  def convertSecondReadToTab5(
    adamRecord: Alignment,
    writeOriginalQualityScores: Boolean = false): String = {

    val (name, sequence, qualityScores) =
      prepareFastq(adamRecord, maybeAddSuffix = false, writeOriginalQualityScores)

    // name of second read is ignored
    "%s\t%s".format(sequence, qualityScores)
  }

  /**
   * Trim the index suffix from the read name if present.
   *
   * @param name Read name to trim.
   * @return The read name after trimming the index suffix if present.
   */
  private def trimSuffix(name: String): String = {
    name.replace("/[0-9]+^", "")
  }

  /**
   * Converts a single ADAM record into a SAM record.
   *
   * @param adamRecord ADAM formatted alignment to convert.
   * @param header SAM file header to attach to the record.
   * @param rgd Dictionary describing the read groups that are in the RDD that
   *   this read is from.
   * @return Returns the record converted to htsjdk format. Can be used for output to SAM/BAM.
   */
  def convert(adamRecord: Alignment,
              header: SAMFileHeader,
              rgd: ReadGroupDictionary): SAMRecord = {

    // attach header
    val builder: SAMRecord = new SAMRecord(header)

    // set canonically necessary fields
    builder.setReadName(adamRecord.getReadName)
    builder.setReadString(adamRecord.getSequence)
    builder.setBaseQualityString(Option(adamRecord.getQualityScores).getOrElse("*"))

    setReadGroup(builder, adamRecord, rgd)
    setMate(builder, adamRecord)
    setPairFlags(builder, adamRecord)
    Option(adamRecord.getDuplicateRead)
      .foreach(v => builder.setDuplicateReadFlag(v.booleanValue))
    setMapping(builder, adamRecord)
    setOriginalsAndTags(builder, adamRecord)

    // return sam record
    builder
  }

  /** The read group, and its library and platform unit. */
  private def setReadGroup(builder: SAMRecord, adamRecord: Alignment, rgd: ReadGroupDictionary): Unit = {
    Option(adamRecord.getReadGroupId)
      .foreach(v => {
        builder.setAttribute("RG", v)
        val rg = rgd(v)
        rg.library.foreach(v => builder.setAttribute("LB", v))
        rg.platformUnit.foreach(v => builder.setAttribute("PU", v))
      })
  }

  /** The mate's reference and alignment position, and the template length. */
  private def setMate(builder: SAMRecord, adamRecord: Alignment): Unit = {
    Option(adamRecord.getMateReferenceName)
      .foreach(builder.setMateReferenceName)
    Option(adamRecord.getMateAlignmentStart)
      .foreach(s => builder.setMateAlignmentStart(s.toInt + 1))
    Option(adamRecord.getInsertSize)
      .foreach(s => builder.setInferredInsertSize(s.toInt))
  }

  /** The paired flag and, for paired reads only, the mate and pair flags. */
  private def setPairFlags(builder: SAMRecord, adamRecord: Alignment): Unit = {
    Option(adamRecord.getReadPaired).foreach(p => {
      builder.setReadPairedFlag(p.booleanValue)
      if (p) {
        Option(adamRecord.getMateNegativeStrand)
          .foreach(v => builder.setMateNegativeStrandFlag(v.booleanValue))
        Option(adamRecord.getMateMapped)
          .foreach(v => builder.setMateUnmappedFlag(!v.booleanValue))
        Option(adamRecord.getProperPair)
          .foreach(v => builder.setProperPairFlag(v.booleanValue))
        builder.setFirstOfPairFlag(adamRecord.getReadInFragment == 0)
        builder.setSecondOfPairFlag(adamRecord.getReadInFragment == 1)
      }
    })
  }

  /** The mapped flag, strand and, for mapped reads only, the alignment fields. */
  private def setMapping(builder: SAMRecord, adamRecord: Alignment): Unit = {
    Option(adamRecord.getReadMapped)
      .foreach(m => {
        builder.setReadUnmappedFlag(!m.booleanValue)

        // Sometimes aligners like BWA-MEM mark a read as negative even if it's not mapped
        Option(adamRecord.getReadNegativeStrand)
          .foreach(v => builder.setReadNegativeStrandFlag(v.booleanValue))

        if (m) {
          setAlignment(builder, adamRecord)
        } else {
          // mapping quality must be 0 if read is unmapped
          builder.setMappingQuality(0)
        }
      })
  }

  private def setAlignment(builder: SAMRecord, adamRecord: Alignment): Unit = {
    // if we are aligned, we must have a reference
    require(adamRecord.getReferenceName != null, "Cannot have null reference if aligned.")
    builder.setReferenceName(adamRecord.getReferenceName)

    Option(adamRecord.getCigar).foreach(builder.setCigarString)
    Option(adamRecord.getOriginalCigar).foreach(v => builder.setAttribute("OC", v))
    Option(adamRecord.getPrimaryAlignment)
      .foreach(v => builder.setNotPrimaryAlignmentFlag(!v.booleanValue))
    Option(adamRecord.getSupplementaryAlignment)
      .foreach(v => builder.setSupplementaryAlignmentFlag(v.booleanValue))
    Option(adamRecord.getStart)
      .foreach(s => builder.setAlignmentStart(s.toInt + 1))
    Option(adamRecord.getOriginalStart)
      .foreach(s => builder.setAttribute("OP", s.toInt + 1))
    Option(adamRecord.getMappingQuality).foreach(v => builder.setMappingQuality(v))
  }

  /** QC failure, MD tag, original qualities, cigar and start, and all other tags. */
  private def setOriginalsAndTags(builder: SAMRecord, adamRecord: Alignment): Unit = {
    Option(adamRecord.getFailedVendorQualityChecks)
      .foreach(v => builder.setReadFailsVendorQualityCheckFlag(v.booleanValue))
    Option(adamRecord.getMismatchingPositions)
      .foreach(builder.setAttribute("MD", _))
    Option(adamRecord.getOriginalQualityScores)
      .map(s => s.getBytes.map(v => (v - 33).toByte)) // not ascii, but short int
      .foreach(builder.setOriginalBaseQualities)
    Option(adamRecord.getOriginalCigar)
      .foreach(builder.setAttribute("OC", _))
    Option(adamRecord.getOriginalStart)
      .foreach(i => builder.setAttribute("OP", i + 1))

    if (adamRecord.getAttributes != null) {
      RichAlignment(adamRecord).tags.foreach(a => builder.setAttribute(a.tag, a.value))
    }
  }

  /**
   * Creates a SAM formatted header. This can be used with SAM or BAM files.
   *
   * @param sd Reference sequence dictionary to use for conversion.
   * @param rgd Dictionary containing read groups.
   * @return Converted SAM formatted record.
   */
  def createSAMHeader(sd: SequenceDictionary,
                      rgd: ReadGroupDictionary): SAMFileHeader = {
    val samSequenceDictionary = sd.toSAMSequenceDictionary
    val samHeader = new SAMFileHeader
    samHeader.setSequenceDictionary(samSequenceDictionary)
    rgd.readGroups.foreach(group => samHeader.addReadGroup(group.toSAMReadGroupRecord()))

    samHeader
  }

  /**
   * Converts a fragment to a set of reads.
   *
   * @param fragment Fragment to convert.
   * @return The collection of alignments described by the fragment. If the fragment
   *         doesn't contain any alignments, this method will return one unaligned
   *         Alignment per sequence in the Fragment.
   */
  def convertFragment(fragment: Fragment): Iterable[Alignment] = {
    fragment.getAlignments.asScala
  }
}

/**
 * Singleton object to assist with converting Alignments.
 *
 * Singleton object exists due to cross reference from
 * org.bdgenomics.adam.rdd.read.AlignmentDatasetFunctions.
 */
object AlignmentConverter extends Serializable {

  /**
   * Return the references in the specified SAM file header.
   *
   * @param header SAM file header
   * @return the references in the specified SAM file header
   */
  def references(header: SAMFileHeader): SequenceDictionary = {
    SequenceDictionary(header)
  }

  /**
   * Return the read groups in the specified SAM file header.
   *
   * @param header SAM file header
   * @return the read groups in the specified SAM file header
   */
  def readGroups(header: SAMFileHeader): ReadGroupDictionary = {
    ReadGroupDictionary.fromSAMHeader(header)
  }

  /**
   * Return the processing steps in the specified SAM file header.
   *
   * @param header SAM file header
   * @return the processing steps in the specified SAM file header
   */
  def processingSteps(header: SAMFileHeader): Seq[ProcessingStep] = {
    val programRecords = header.getProgramRecords().asScala
    programRecords.map(convertSAMProgramRecord).toSeq
  }

  private def convertSAMProgramRecord(record: SAMProgramRecord): ProcessingStep = {
    val builder = ProcessingStep.newBuilder.setId(record.getId)
    Option(record.getPreviousProgramGroupId).foreach(builder.setPreviousId)
    Option(record.getProgramVersion).foreach(builder.setVersion)
    Option(record.getProgramName).foreach(builder.setProgramName)
    Option(record.getCommandLine).foreach(builder.setCommandLine)
    builder.build
  }

  /**
   * Checks to see if a read name has a index suffix.
   *
   * Read names frequently end in a "/%d" suffix, where the digit at the end
   * signifies the number of this read in the sequenced fragment. E.g., for an
   * Illumina paired-end protocol, the first read in the pair will have a "/1"
   * suffix, while the second read in the pair will have a "/2" suffix.
   *
   * @param adamRecord Record to check.
   * @return True if the read ends in a read number suffix.
   */
  def readNameHasPairedSuffix(adamRecord: Alignment): Boolean = {
    adamRecord.getReadName.length() > 2 &&
      adamRecord.getReadName.charAt(adamRecord.getReadName.length() - 2) == '/' &&
      (adamRecord.getReadName.charAt(adamRecord.getReadName.length() - 1) == '1' ||
        adamRecord.getReadName.charAt(adamRecord.getReadName.length() - 1) == '2')
  }
}
