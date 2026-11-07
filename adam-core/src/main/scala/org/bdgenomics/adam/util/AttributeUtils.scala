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

import htsjdk.samtools.SAMRecord.SAMTagAndValue
import htsjdk.samtools.TagValueAndUnsignedArrayFlag
import org.bdgenomics.adam.models.{ TagType, Attribute }

/**
 * AttributeUtils is a utility object for parsing optional fields from a BAM file, or
 * the attributes column from an ADAM file.
 */
object AttributeUtils {

  val attrRegex = """([^:]{2,4}):([AifZHB]):(.*)""".r
  val arrayRegex = """([cCiIsSf]{1},)(.*)""".r

  /**
   * Converts an htsjdk SAM Tag/Value into an ADAM attribute.
   *
   * @param attr htsjdk tag/value pair containing a tag value with a known type.
   * @return Returns an Attribute instance.
   */
  def convertSAMTagAndValue(attr: SAMTagAndValue): Attribute = {
    attr.value match {
      case unsigned: TagValueAndUnsignedArrayFlag => unsignedArrayAttribute(attr.tag, unsigned.value)
      case value                                  => signedAttribute(attr.tag, value)
    }
  }

  private def unsignedArrayAttribute(tag: String, value: Any): Attribute = {
    value match {
      case x: Array[Int]   => Attribute(tag, TagType.NumericUnsignedIntSequence, x)
      case x: Array[Byte]  => Attribute(tag, TagType.NumericUnsignedByteSequence, x)
      case x: Array[Short] => Attribute(tag, TagType.NumericUnsignedShortSequence, x)
    }
  }

  private def signedAttribute(tag: String, value: Any): Attribute = {
    value match {
      case x: java.lang.Integer   => Attribute(tag, TagType.Integer, x)
      case x: java.lang.Character => Attribute(tag, TagType.Character, x)
      case x: java.lang.Float     => Attribute(tag, TagType.Float, x)
      case x: java.lang.String    => Attribute(tag, TagType.String, x)
      case x: Array[Int]          => Attribute(tag, TagType.NumericIntSequence, x)
      case x: Array[Byte]         => Attribute(tag, TagType.NumericByteSequence, x)
      case x: Array[Short]        => Attribute(tag, TagType.NumericShortSequence, x)
      case x: Array[Float]        => Attribute(tag, TagType.NumericFloatSequence, x)
      // attr.value for type 'H' is indistinguishable from 'B:c', so both will be saved as NumericByteSequence.
    }
  }

  /**
   * Parses a tab-separated string of attributes (tag:type:value) into a Seq of Attribute values.
   *
   * @param tagStrings The String to be parsed
   * @return The parsed Attributes
   */
  def parseAttributes(tagStrings: String): Seq[Attribute] =
    tagStrings.split("\t").filter(_.length > 0).map(parseAttribute)

  /**
   * Extract the Attribute value from the corresponding String fragment stored in the
   * Read.attributes field.
   *
   * @param encoded A three-part ':'-separated string, containing [attrTuple]:[type]:[value]
   * @return An Attribute value which has the three, extracted and correctly-typed parts of the encoded argument.
   * @throws IllegalArgumentException if the encoded string doesn't conform to the required regular
   *         expression ([A-Z]{2}:[AifZHB]:[^\t^]+)
   */
  def parseAttribute(encoded: String): Attribute = {
    attrRegex.findFirstMatchIn(encoded) match {
      case Some(m) => createAttribute((m.group(1), m.group(2), m.group(3)))
      case None =>
        throw new IllegalArgumentException(
          "attribute string \"%s\" doesn't match format attrTuple:type:value".format(encoded)
        )
    }
  }

  // these letters should be (per the SAM file format spec) the only legal
  // values of the tagTypeString, plus the array subtype for "B".
  private val tagTypesByString: Map[String, TagType.Value] = Map(
    "A" -> TagType.Character,
    "i" -> TagType.Integer,
    "f" -> TagType.Float,
    "Z" -> TagType.String,
    "H" -> TagType.ByteSequence,
    "B:c," -> TagType.NumericByteSequence,
    "B:i," -> TagType.NumericIntSequence,
    "B:s," -> TagType.NumericShortSequence,
    "B:C," -> TagType.NumericUnsignedByteSequence,
    "B:I," -> TagType.NumericUnsignedIntSequence,
    "B:S," -> TagType.NumericUnsignedShortSequence,
    "B:f," -> TagType.NumericFloatSequence
  )

  private def createAttribute(attrTuple: (String, String, String)): Attribute = {
    val tagName = attrTuple._1
    val (fullTagString, valueStr) = splitTagTypeAndValue(attrTuple)

    // partial lookup, as the previous partial match: unknown types throw a MatchError
    val tagType = tagTypesByString.getOrElse(fullTagString,
      throw new MatchError(fullTagString))

    Attribute(tagName, tagType, typedStringToValue(tagType, valueStr))
  }

  /**
   * @return The full tag type string (e.g. "i" or "B:c,") and the value
   *   string. Array ("B") tags carry their array format in the value.
   */
  private def splitTagTypeAndValue(attrTuple: (String, String, String)): (String, String) = {
    val tagTypeString = attrTuple._2

    if (tagTypeString == "B") {
      arrayRegex.findFirstMatchIn(attrTuple._3) match {
        case Some(m) => {
          ("%s:%s".format(tagTypeString, m.group(1)), m.group(2))
        }
        case None => {
          throw new IllegalArgumentException(
            "Array tags must define array format. For tag %s.".format(attrTuple))
        }
      }
    } else {
      (tagTypeString, attrTuple._3)
    }
  }

  private def typedStringToValue(tagType: TagType.Value, valueStr: String): Any = {
    tagType match {
      case TagType.Character => valueStr(0)
      case TagType.Integer => Integer.valueOf(valueStr)
      case TagType.Float => java.lang.Float.valueOf(valueStr)
      case TagType.String => valueStr
      case TagType.ByteSequence => valueStr.map(c => java.lang.Byte.valueOf("" + c))
      case TagType.NumericByteSequence | TagType.NumericUnsignedByteSequence => valueStr.split(",").map(_.toByte)
      case TagType.NumericShortSequence | TagType.NumericUnsignedShortSequence => valueStr.split(",").map(_.toShort)
      case TagType.NumericIntSequence | TagType.NumericUnsignedIntSequence => valueStr.split(",").map(_.toInt)
      case TagType.NumericFloatSequence => valueStr.split(",").map(_.toFloat)
    }
  }
}
