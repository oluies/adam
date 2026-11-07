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

import org.scalatest.FunSuite

class ASCIITableSuite extends FunSuite {

  test("render a table with left- and right-aligned columns and a centered header") {
    val table = new ASCIITable(
      Array(ASCIITableHeader("name", TextAlignment.Left), ASCIITableHeader("count")),
      Array(Array("a", "1"), Array("bbbbbb", "100")))
    assert(table.toString ===
      """+--------+-------+
        ||  name  | count |
        |+--------+-------+
        || a      |     1 |
        || bbbbbb |   100 |
        |+--------+-------+
        |""".stripMargin)
  }

  test("reject rows that do not match the header") {
    intercept[IllegalArgumentException] {
      new ASCIITable(Array(ASCIITableHeader("only")), Array(Array("a", "b")))
    }
  }
}
