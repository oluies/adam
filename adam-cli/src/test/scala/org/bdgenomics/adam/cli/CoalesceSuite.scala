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

import org.bdgenomics.adam.util.ADAMFunSuite

class CoalesceSuite extends ADAMFunSuite {

  /** True when rdd or anything it is computed from needs a shuffle. */
  private def shuffles(rdd: org.apache.spark.rdd.RDD[_]): Boolean = rdd.dependencies.exists {
    case _: org.apache.spark.ShuffleDependency[_, _, _] => true
    case dependency                                      => shuffles(dependency.rdd)
  }

  sparkTest("coalesce to fewer partitions without a shuffle") {
    val rdd = sc.parallelize(1 to 100, 4)
    val coalesced = Coalesce(rdd, 2, forceShuffle = false)
    assert(coalesced.partitions.length === 2)
    assert(!shuffles(coalesced))
    assert(coalesced.collect().sorted.toSeq === (1 to 100))
  }

  sparkTest("shuffle to reach more partitions") {
    val coalesced = Coalesce(sc.parallelize(1 to 100, 2), 8, forceShuffle = false)
    assert(coalesced.partitions.length === 8)
    assert(shuffles(coalesced))
    assert(coalesced.collect().sorted.toSeq === (1 to 100))
  }

  sparkTest("shuffle when forced, even to fewer partitions") {
    val rdd = sc.parallelize(1 to 100, 4)
    val coalesced = Coalesce(rdd, 2, forceShuffle = true)
    assert(coalesced.partitions.length === 2)
    assert(shuffles(coalesced))
    assert(coalesced.collect().sorted.toSeq === (1 to 100))
  }
}
