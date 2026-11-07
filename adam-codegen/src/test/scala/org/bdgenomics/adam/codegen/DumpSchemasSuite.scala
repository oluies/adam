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
package org.bdgenomics.adam.codegen

import java.nio.file.Files
import org.apache.avro.{ Schema, SchemaBuilder }
import org.scalatest.FunSuite
import scala.io.Source

class DumpSchemasSuite extends FunSuite {

  private val product = new DumpSchemasToProduct()

  private val enumSchema = SchemaBuilder.enumeration("Strand").namespace("demo").symbols("FORWARD", "REVERSE")
  private val recordSchema = SchemaBuilder.record("Dbxref").namespace("demo").fields().endRecord()

  private val primitives = Seq(
    (Schema.Type.DOUBLE, "Double", "java.lang.Double", "d"),
    (Schema.Type.FLOAT, "Float", "java.lang.Float", "f"),
    (Schema.Type.INT, "Int", "java.lang.Integer", "i"),
    (Schema.Type.LONG, "Long", "java.lang.Long", "l"),
    (Schema.Type.BOOLEAN, "Boolean", "java.lang.Boolean", "b")
  )

  test("primitives map to their Scala type") {
    for ((schemaType, scalaType, _, _) <- primitives) {
      assert(product.getType(Schema.create(schemaType)) === scalaType)
    }
  }

  test("strings and enums are Scala strings; records keep their name") {
    assert(product.getType(Schema.create(Schema.Type.STRING)) === "String")
    assert(product.getType(enumSchema) === "String")
    assert(product.getType(recordSchema) === "Dbxref")
  }

  test("unsupported types are rejected") {
    for (schemaType <- Seq(Schema.Type.BYTES, Schema.Type.NULL)) {
      val ex = intercept[IllegalStateException](product.getType(Schema.create(schemaType)))
      assert(ex.getMessage === s"Unsupported type $schemaType.")
      intercept[IllegalStateException](product.conversion(Schema.create(schemaType), "map"))
      intercept[IllegalStateException](product.getConversion(Schema.create(schemaType), "map"))
    }
  }

  test("conversion to Avro boxes primitives and converts enums and records") {
    for ((schemaType, _, javaType, p) <- primitives) {
      assert(product.conversion(Schema.create(schemaType), "map") === s".map($p => $p: $javaType)")
    }
    assert(product.conversion(Schema.create(Schema.Type.STRING), "map") === "")
    assert(product.conversion(enumSchema, "mapValues") === ".mapValues(e => demo.Strand.valueOf(e))")
    assert(product.conversion(recordSchema, "map") === ".map(r => r.toAvro)")
  }

  test("conversion from Avro unboxes primitives and converts enums and records") {
    for ((schemaType, scalaType, _, p) <- primitives) {
      assert(product.getConversion(Schema.create(schemaType), "map") === s".map($p => $p: $scalaType)")
    }
    assert(product.getConversion(Schema.create(Schema.Type.STRING), "map") === "")
    assert(product.getConversion(enumSchema, "mapValues") === ".mapValues(e => e.toString)")
    assert(product.getConversion(recordSchema, "map") === ".map(r => Dbxref.fromAvro(r))")
  }

  private def generate(generator: Array[String] => Unit, pkg: String, classes: String*): String = {
    val out = Files.createTempDirectory("codegen").resolve("Generated.scala")
    generator(Array(pkg) ++ classes ++ Array(out.toString))
    val source = Source.fromFile(out.toFile)
    try source.mkString finally source.close()
  }

  test("generate a product for a bdg-formats record") {
    val code = generate(new DumpSchemasToProduct()(_), "org.bdgenomics.adam.sql",
      "org.bdgenomics.formats.avro.Genotype")
    assert(code.contains("package org.bdgenomics.adam.sql"))
    assert(code.contains("import scala.jdk.CollectionConverters._"))
    assert(code.contains("case class Genotype ("))
    assert(code.contains("record.getGenotypeLikelihoods().asScala.toSeq.map(d => d: Double)"))
    assert(code.contains("record.getStrandBiasComponents().asScala.toSeq.map(i => i: Int)"))
    assert(code.contains("record.setStrandBiasComponents(strandBiasComponents.map(i => i: java.lang.Integer).asJava)"))
    assert(code.contains("record.setStrandBiasComponents(new java.util.LinkedList())"))
  }

  test("generate products for map, enum and nested record fields") {
    val code = generate(new DumpSchemasToProduct()(_), "org.bdgenomics.adam.sql",
      "org.bdgenomics.formats.avro.Feature", "org.bdgenomics.formats.avro.Dbxref")
    assert(code.contains("case class Feature ("))
    assert(code.contains("case class Dbxref ("))
    assert(code.contains("record.getAttributes().asScala.view.toMap)"))
    assert(code.contains("record.setAttributes(attributes.view.toMap.asJava)"))
    assert(code.contains("record.setAttributes(new java.util.HashMap())"))
    assert(code.contains("Option(record.getStrand).map(field => field.toString)"))
    assert(code.contains("strand.foreach(field => record.setStrand(org.bdgenomics.formats.avro.Strand.valueOf(field)))"))
  }

  test("generate projection enums") {
    val code = generate(new DumpSchemasToProjectionEnums()(_), "org.bdgenomics.adam.projections",
      "org.bdgenomics.formats.avro.Dbxref")
    assert(code.contains("package org.bdgenomics.adam.projections"))
    assert(code.contains("object DbxrefField extends FieldEnumeration(org.bdgenomics.formats.avro.Dbxref.SCHEMA$)"))
    assert(code.contains("val db, accession = SchemaValue"))
  }
}
