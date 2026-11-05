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

import org.apache.avro.generic.IndexedRecord
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.AvroParquetReader
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.spark.SparkContext

/**
 * Implements an iterable collection that is backed by a Parquet file.
 *
 * @param sc A SparkContext to use to get underlying Hadoop FileSystem config.
 * @param file The path to the Parquet file to load.
 */
class ParquetFileTraversable[T <: IndexedRecord](sc: SparkContext, file: Path) extends Iterable[T] {

  /**
   * @param sc A SparkContext to use to get underlying Hadoop FileSystem config.
   * @param file The path to the Parquet file to load.
   */
  def this(sc: SparkContext, file: String) = this(sc, new Path(file))

  private val fs = file.getFileSystem(sc.hadoopConfiguration)

  private val paths: List[Path] = {
    if (!fs.exists(file)) {
      throw new IllegalArgumentException("The path %s does not exist".format(file))
    }
    val status = fs.getFileStatus(file)
    var paths = List[Path]()
    if (status.isDirectory) {
      val files = fs.listStatus(file)
      files.foreach {
        file =>
          if (file.getPath.getName.contains("part")) {
            paths ::= file.getPath
          }
      }
    } else if (fs.isFile(file)) {
      paths ::= file
    } else {
      throw new IllegalArgumentException("The path '%s' is neither file nor directory".format(file))
    }
    paths
  }

  private def openReader(path: Path) =
    AvroParquetReader.builder[T](HadoopInputFile.fromPath(path, sc.hadoopConfiguration)).build()

  /**
   * Runs a for loop over each record in the file, and applies a function.
   * Each reader is closed when its file is done or the function throws.
   *
   * @param f The function to apply to each record.
   */
  override def foreach[U](f: T => U): Unit = {
    paths.foreach(path => {
      val parquetReader = openReader(path)
      try {
        var record = parquetReader.read()
        while (record != null) {
          f(record)
          record = parquetReader.read()
        }
      } finally {
        parquetReader.close()
      }
    })
  }

  /**
   * @return The first record, read with a reader that is closed before returning.
   */
  override def headOption: Option[T] = {
    paths.iterator.map(path => {
      val parquetReader = openReader(path)
      try {
        Option(parquetReader.read())
      } finally {
        parquetReader.close()
      }
    }).collectFirst { case Some(record) => record }
  }

  /**
   * Streams the records of each Parquet part file in turn. A reader is only
   * closed once its file has been read to the end, so prefer foreach or
   * headOption, which always close their readers.
   */
  override def iterator: Iterator[T] = {
    paths.iterator.flatMap(path => {
      val parquetReader = openReader(path)
      Iterator.continually(parquetReader.read())
        .takeWhile(record => {
          if (record == null) parquetReader.close()
          record != null
        })
    })
  }
}
