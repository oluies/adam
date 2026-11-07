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
package org.bdgenomics.adam.models

import org.scalatest.FunSuite

class OptionalReferenceOrderingSuite extends FunSuite {

  private val a = Some(ReferenceRegion("1", 10L, 20L))
  private val b = Some(ReferenceRegion("1", 30L, 40L))

  test("provided regions compare by the base ordering") {
    assert(OptionalRegionOrdering.compare(a, b) < 0)
    assert(OptionalRegionOrdering.compare(b, a) > 0)
    assert(OptionalRegionOrdering.compare(a, a) === 0)
  }

  test("regions that are not provided sort last") {
    assert(OptionalRegionOrdering.compare(None, None) === 0)
    assert(OptionalRegionOrdering.compare(a, None) < 0)
    assert(OptionalRegionOrdering.compare(None, a) > 0)
    assert(Seq(None, b, None, a).sorted(OptionalRegionOrdering) === Seq(a, b, None, None))
  }

  test("the ordering is antisymmetric") {
    val values = Seq(None, a, b)
    for (x <- values; y <- values) {
      assert(math.signum(OptionalRegionOrdering.compare(x, y)) ===
        -math.signum(OptionalRegionOrdering.compare(y, x)), s"$x vs $y")
    }
  }

  test("optional positions use the same rules") {
    val p = Some(ReferencePosition("1", 5L))
    assert(OptionalPositionOrdering.compare(p, None) < 0)
    assert(OptionalPositionOrdering.compare(None, p) > 0)
  }
}
