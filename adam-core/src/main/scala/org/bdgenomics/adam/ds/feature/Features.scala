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
package org.bdgenomics.adam.ds.feature

import org.bdgenomics.formats.avro.{ Dbxref, Feature, OntologyTerm, Strand }
import scala.jdk.CollectionConverters._
import scala.collection.mutable.{ ArrayBuffer, LinkedHashMap, ListBuffer }
import scala.math.{ max, min }

/**
 * Utility methods on features and related classes.
 */
private[feature] object Features {

  /**
   * Parse a strand from the specified string.
   *
   * @param s string to parse
   * @return a strand parsed from the specified string, or None if the stand
   *   is unspecified or incorrectly specified
   */
  def toStrand(s: String): Option[Strand] = {
    s.trim match {
      case "+" => Some(Strand.FORWARD)
      case "-" => Some(Strand.REVERSE)
      case "." => Some(Strand.INDEPENDENT)
      case "?" => Some(Strand.UNKNOWN)
      case _   => None
    }
  }

  /**
   * Convert the specified strand to its string value.
   *
   * @param strand strand to convert
   * @param emptyUnknown If true, returns an empty string if we fall through the
   *   match. Else, returns the unknown string.
   * @return the specified strand converted to its string value
   */
  def asString(strand: Strand, emptyUnknown: Boolean = false): String = {
    strand match {
      case Strand.FORWARD     => "+"
      case Strand.REVERSE     => "-"
      case Strand.INDEPENDENT => "."
      case Strand.UNKNOWN     => "?"
      case _ => {
        if (emptyUnknown) {
          ""
        } else {
          "?"
        }
      }
    }
  }

  /**
   * Parse a database cross reference from the specified string.
   *
   * @param s string to parse
   * @return a database cross reference parsed from the specified string, or None if
   *    the database cross reference is unspecified or incorrectly specified
   */
  def toDbxref(s: String): Option[Dbxref] = {
    val i = s.indexOf(':')
    if (i >= 0) {
      Some(new Dbxref(s.substring(0, i), s.substring(i + 1)))
    } else {
      None
    }
  }

  /**
   * Convert the specified database cross reference to its string value.
   *
   * @param dbxref database cross reference to convert
   * @return the specified database cross reference converted to its string value
   */
  def asString(dbxref: Dbxref): String = {
    dbxref.getDb + ":" + dbxref.getAccession
  }

  /**
   * Parse an ontology term from the specified string.
   *
   * @param s string to parse
   * @return an ontology term parsed from the specified string, or None if
   *    the ontology term is unspecified or incorrectly specified
   */
  def toOntologyTerm(s: String): Option[OntologyTerm] = {
    val i = s.indexOf(':')
    if (i >= 0) {
      Some(new OntologyTerm(s.substring(0, i), s.substring(i + 1)))
    } else {
      None
    }
  }

  /**
   * Convert the specified ontology term to its string value.
   *
   * @param ontologyTerm ontology term to convert
   * @return the specified ontology term converted to its string value
   */
  def asString(ontologyTerm: OntologyTerm): String = {
    ontologyTerm.getDb + ":" + ontologyTerm.getAccession
  }

  /**
   * Assign values for various feature fields from a sequence of attribute key value pairs.
   *
   * @param attributes sequence of attribute key value pairs
   * @param f feature builder
   * @return the specified feature builder
   */
  def assignAttributes(attributes: Seq[(String, String)], f: Feature.Builder): Feature.Builder = {
    val gathered = new Gathered()
    attributes.foreach {
      case (key, value) => reservedKeys.get(key) match {
        case Some(assign) => assign(f, gathered, value)
        // unrecognized key, save to attributes
        case None         => gathered.remaining += key -> value
      }
    }
    gathered.setOn(f)
    f
  }

  /** The list and map fields of a feature, gathered from repeated attributes. */
  private class Gathered {
    val aliases = new ListBuffer[String]()
    val notes = new ListBuffer[String]()
    val parentIds = new ListBuffer[String]()
    val dbxrefs = new ListBuffer[Dbxref]()
    val ontologyTerms = new ListBuffer[OntologyTerm]()
    // any duplicate keys are lost here, last one in wins
    val remaining = new LinkedHashMap[String, String]

    /** Sets each non-empty field on the builder. */
    def setOn(f: Feature.Builder): Unit = {
      setIfNonEmpty(aliases)(f.setAliases)
      setIfNonEmpty(notes)(f.setNotes)
      setIfNonEmpty(parentIds)(f.setParentIds)
      setIfNonEmpty(dbxrefs)(f.setDbxrefs)
      setIfNonEmpty(ontologyTerms)(f.setOntologyTerms)
      if (remaining.nonEmpty) f.setAttributes(remaining.asJava)
    }
  }

  private def setIfNonEmpty[T](values: ListBuffer[T])(set: java.util.List[T] => Any): Unit = {
    if (values.nonEmpty) set(values.asJava)
  }

  /** How each reserved attribute key is assigned to a feature. */
  private val reservedKeys: Map[String, (Feature.Builder, Gathered, String) => Unit] = Map(
    // reserved keys in GFF3 specification
    "ID" -> ((f, _, v) => f.setFeatureId(v)),
    "Name" -> ((f, _, v) => f.setName(v)),
    "Target" -> ((f, _, v) => f.setTarget(v)),
    "Gap" -> ((f, _, v) => f.setGap(v)),
    "Derives_from" -> ((f, _, v) => f.setDerivesFrom(v)),
    "Is_circular" -> ((f, _, v) => f.setCircular(v.toBoolean)),
    // sampleId information
    "sampleId" -> ((f, _, v) => f.setSampleId(v)),
    "Alias" -> ((_, g, v) => g.aliases += v),
    "Note" -> ((_, g, v) => g.notes += v),
    "Parent" -> ((_, g, v) => g.parentIds += v),
    "Dbxref" -> ((_, g, v) => toDbxref(v).foreach(g.dbxrefs += _)),
    "Ontology_term" -> ((_, g, v) => toOntologyTerm(v).foreach(g.ontologyTerms += _)),
    // commonly used keys in GTF/GFF2, e.g. via Ensembl
    "gene_id" -> ((f, _, v) => f.setGeneId(v)),
    "transcript_id" -> ((f, _, v) => f.setTranscriptId(v)),
    "exon_id" -> ((f, _, v) => f.setExonId(v)),
    "protein_id" -> ((f, _, v) => f.setProteinId(v))
  )

  /**
   * Gather values from various feature fields into a sequence of attribute key value pairs.
   *
   * @param feature feature to gather values from
   * @return values from various feature fields gathered into a sequence of attribute key value pairs
   */
  def gatherAttributes(feature: Feature): Seq[(String, String)] = {
    val attrs = new ArrayBuffer[(String, String)]

    def addBooleanTuple(b: java.lang.Boolean) = {
      attrs += Tuple2("Is_circular", b.toString)
    }

    Option(feature.getFeatureId).foreach(attrs += Tuple2("ID", _))
    Option(feature.getName).foreach(attrs += Tuple2("Name", _))
    Option(feature.getTarget).foreach(attrs += Tuple2("Target", _))
    Option(feature.getGap).foreach(attrs += Tuple2("Gap", _))
    Option(feature.getDerivesFrom).foreach(attrs += Tuple2("Derives_from", _))
    Option(feature.getCircular).foreach(addBooleanTuple)
    Option(feature.getGeneId).foreach(attrs += Tuple2("gene_id", _))
    Option(feature.getTranscriptId).foreach(attrs += Tuple2("transcript_id", _))
    Option(feature.getExonId).foreach(attrs += Tuple2("exon_id", _))
    Option(feature.getProteinId).foreach(attrs += Tuple2("protein_id", _))
    Option(feature.getSampleId).foreach(attrs += Tuple2("sampleId", _))
    for (alias <- feature.getAliases.asScala) attrs += Tuple2("Alias", alias)
    for (note <- feature.getNotes.asScala) attrs += Tuple2("Note", note)
    for (parentId <- feature.getParentIds.asScala) attrs += Tuple2("Parent", parentId)
    for (dbxref <- feature.getDbxrefs.asScala) attrs += Tuple2("Dbxref", asString(dbxref))
    for (ontologyTerm <- feature.getOntologyTerms.asScala) attrs += Tuple2("Ontology_term", asString(ontologyTerm))
    attrs ++= feature.getAttributes.asScala
    attrs.toSeq
  }

  /**
   * Build a name for the specified feature considering its attributes.  Used when
   * writing out to lossy feature formats such as BED, NarrowPeak, and IntervalList.
   *
   * @param feature feature
   * @return a name for the specified feature considering its attributes
   */
  def nameOf(feature: Feature): String = {
    Option(feature.getName).foreach(return _)
    Option(feature.getFeatureId).foreach(return _)

    feature.getFeatureType match {
      case "exon" | "SO:0000147"       => Option(feature.getExonId).foreach(return _)
      case "transcript" | "SO:0000673" => Option(feature.getTranscriptId).foreach(return _)
      case "gene" | "SO:0000704"       => Option(feature.getGeneId).foreach(return _)
      case s: String                   => Option(s).foreach(return _)
      case null                        =>
    }
    "sequence_feature"
  }

  /**
   * Format the feature score as double floating point values
   * with "." as missing value.
   *
   * @param score Feature score to format.
   * @return Return the specified feature score formatted as
   *    double floating point values with "." as missing value.
   */
  def formatScore(score: java.lang.Double): String = {
    Option(score).fold(".")(_.toString)
  }

  /**
   * Interpolate the feature score to integer values between 0 and 1000,
   * with missing value as specified.
   *
   * @param score Feature score to interpolate.
   * @param minimumScore Minimum score, interpolated to 0.
   * @param maximumScore Maximum score, interpolated to 1000.
   * @param missingValue Value to use if score is not specified.
   * @return Return the specified feature score interpolated to integer values
   *    between 0 and 1000, with missing value as specified.
   */
  def interpolateScore(score: java.lang.Double,
                       minimumScore: Double,
                       maximumScore: Double,
                       missingValue: Int): Int = {

    def constrain(v: Double, min: Double, max: Double): Double = {
      if (v < min) {
        min
      } else if (v > max) {
        max
      } else {
        v
      }
    }

    def interp(value: Double,
               sourceMin: Double,
               sourceMax: Double,
               targetMin: Double,
               targetMax: Double): Double = {

      val v = max(min(sourceMax, value), sourceMin)
      targetMin + (targetMax - targetMin) * ((v - sourceMin) / (sourceMax - sourceMin))
    }

    Option(score).fold(missingValue)(interp(_, minimumScore, maximumScore, 0, 1000).toInt)
  }
}
