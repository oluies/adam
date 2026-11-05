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

import java.nio.file.{ Files, Paths }
import org.apache.hadoop.fs.Path
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.bdgenomics.adam.ds.ADAMContext._
import org.bdgenomics.adam.util.ADAMFunSuite
import scala.jdk.CollectionConverters._

class ParquetCompressionSuite extends ADAMFunSuite {

  /** The compression codecs used by the column chunks of every Parquet file under path. */
  private def codecs(path: String): Set[CompressionCodecName] = {
    val files = Files.walk(Paths.get(path)).iterator().asScala
      .filter(_.toString.endsWith(".parquet"))
      .toList
    assert(files.nonEmpty, s"no Parquet files under $path")
    files.flatMap(file => {
      val reader = ParquetFileReader.open(HadoopInputFile.fromPath(new Path(file.toString), sc.hadoopConfiguration))
      try {
        reader.getFooter.getBlocks.asScala.flatMap(_.getColumns.asScala.map(_.getCodec))
      } finally {
        reader.close()
      }
    }).toSet
  }

  for (codec <- Seq(CompressionCodecName.GZIP, CompressionCodecName.UNCOMPRESSED)) {
    sparkTest(s"partitioned Parquet is written with the requested $codec codec") {
      val path = tmpLocation()
      sc.loadAlignments(testFile("small.sam")).saveAsPartitionedParquet(path, compressionCodec = codec)
      assert(codecs(path) === Set(codec))
    }

    sparkTest(s"Dataset-backed Parquet is written with the requested $codec codec") {
      val path = tmpLocation()
      val alignments = sc.loadAlignments(testFile("small.sam"))
      alignments.transformDataset(ds => ds).saveAsParquet(path, compressionCodec = codec)
      assert(codecs(path) === Set(codec))
    }
  }
}
