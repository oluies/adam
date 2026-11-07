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
import org.bdgenomics.formats.avro.Alphabet

class TransformSequencesSuite extends ADAMFunSuite {

  sparkTest("convert FASTA to Parquet sequences") {
    val outputPath = tmpFile("sequences.adam")
    TransformSequences(Array(testFile("artificial.fa"), outputPath)).run(sc)

    val sequences = sc.loadParquetSequences(outputPath).rdd.collect()
    assert(sequences.length === 1)
    assert(sequences.head.getName === "artificial")
    assert(sequences.head.getAlphabet === Alphabet.DNA)
  }

  sparkTest("create references and read other alphabets") {
    val outputPath = tmpFile("protein.adam")
    TransformSequences(Array(testFile("artificial.fa"), outputPath, "-alphabet", "PROTEIN", "-create_references")).run(sc)

    val sequences = sc.loadParquetSequences(outputPath)
    assert(sequences.rdd.first().getAlphabet === Alphabet.PROTEIN)
    assert(sequences.references.containsReferenceName("artificial"))
  }

  sparkTest("convert Parquet sequences back to FASTA") {
    val adamPath = tmpFile("sequences.adam")
    val fastaPath = tmpFile("sequences.fa")
    TransformSequences(Array(testFile("artificial.fa"), adamPath)).run(sc)
    TransformSequences(Array(adamPath, fastaPath, "-single")).run(sc)

    assert(sc.loadDnaSequences(fastaPath).rdd.first().getSequence ===
      sc.loadDnaSequences(testFile("artificial.fa")).rdd.first().getSequence)
  }
}
