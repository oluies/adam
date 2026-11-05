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

import org.bdgenomics.formats.avro.Feature
import org.scalatest.FunSuite
import scala.jdk.CollectionConverters._

class FeaturesSuite extends FunSuite {

  private val attributes = Seq(
    "ID" -> "feature1",
    "Name" -> "name1",
    "gene_id" -> "gene1",
    "Alias" -> "alias1",
    "Alias" -> "alias2",
    "Parent" -> "parent1",
    "Note" -> "note1",
    "Dbxref" -> "db:accession",
    "Ontology_term" -> "SO:0000704",
    "zeta" -> "1",
    "alpha" -> "2",
    "zeta" -> "3"
  )

  private def assign(): Feature = {
    Features.assignAttributes(attributes, Feature.newBuilder().setReferenceName("1").setStart(0L).setEnd(1L)).build()
  }

  test("reserved keys are assigned to feature fields") {
    val feature = assign()
    assert(feature.getFeatureId === "feature1")
    assert(feature.getName === "name1")
    assert(feature.getGeneId === "gene1")
    assert(feature.getParentIds.asScala === Seq("parent1"))
    assert(feature.getNotes.asScala === Seq("note1"))
    assert(feature.getDbxrefs.asScala.map(Features.asString) === Seq("db:accession"))
    assert(feature.getOntologyTerms.size === 1)
  }

  test("list fields keep their input order") {
    assert(assign().getAliases.asScala === Seq("alias1", "alias2"))
  }

  test("remaining attributes keep their input order and the last duplicate wins") {
    val remaining = assign().getAttributes.asScala.toSeq
    assert(remaining === Seq("zeta" -> "3", "alpha" -> "2"))
  }

  test("gatherAttributes returns the assigned values") {
    val gathered = Features.gatherAttributes(assign())
    assert(gathered.contains("gene_id" -> "gene1"))
    assert(gathered.filter(_._1 == "Alias").map(_._2) === Seq("alias1", "alias2"))
    assert(gathered.contains("Parent" -> "parent1"))
    assert(gathered.contains("Dbxref" -> "db:accession"))
    assert(gathered.contains("zeta" -> "3"))
    assert(gathered.contains("alpha" -> "2"))
  }
}
