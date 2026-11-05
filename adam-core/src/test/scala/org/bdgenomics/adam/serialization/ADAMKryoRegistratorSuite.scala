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
package org.bdgenomics.adam.serialization

import org.apache.spark.SparkConf
import org.apache.spark.serializer.{ KryoSerializer, SerializerInstance }
import org.bdgenomics.formats.avro.Alignment
import org.scalatest.FunSuite
import scala.collection.immutable.ArraySeq
import scala.reflect.ClassTag

class ADAMKryoRegistratorSuite extends FunSuite {

  private def serializer(): SerializerInstance = {
    val conf = new SparkConf(false)
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .set("spark.kryo.registrator", classOf[ADAMKryoRegistrator].getName)
      .set("spark.kryo.referenceTracking", "true")
      .set("spark.kryo.registrationRequired", "true")
    new KryoSerializer(conf).newInstance()
  }

  private def roundTrip[T: ClassTag](ser: SerializerInstance, value: T): T = {
    ser.deserialize[T](ser.serialize(value))
  }

  private def alignment(sequenceLength: Int): Alignment = {
    Alignment.newBuilder()
      .setReadName("read")
      .setSequence("A" * sequenceLength)
      .setQualityScores("I" * sequenceLength)
      .build()
  }

  test("round trip an Avro record") {
    val ser = serializer()
    val record = alignment(100)
    assert(roundTrip(ser, record) === record)
  }

  test("serialized size of a record does not grow after a larger record") {
    val ser = serializer()
    val small = alignment(10)
    val sizeBefore = ser.serialize(small).limit()

    // a large record grows the serializer's reused output buffer
    assert(roundTrip(ser, alignment(100000)) === alignment(100000))

    assert(ser.serialize(small).limit() === sizeBefore)
    assert(roundTrip(ser, small) === small)
  }

  test("round trip Scala 2.13 immutable collections") {
    val ser = serializer()
    val values: Seq[AnyRef] = Seq(
      Nil,
      List(1, 2, 3),
      Vector(),
      Vector(1),
      Vector.range(0, 40),
      Vector.range(0, 2000),
      Vector.range(0, 50000),
      ArraySeq(1, 2, 3),
      ArraySeq("a", "b"),
      Map(),
      Map("a" -> 1),
      Map("a" -> 1, "b" -> 2),
      Map("a" -> 1, "b" -> 2, "c" -> 3),
      Map("a" -> 1, "b" -> 2, "c" -> 3, "d" -> 4),
      (0 until 10).map(i => i.toString -> i).toMap
    )
    values.foreach(value => assert(roundTrip(ser, value) === value, value.getClass.getName))

    val seqs: Array[Seq[String]] = Array(Seq("a"), Seq("b"))
    assert(roundTrip(ser, seqs).toSeq === seqs.toSeq)
  }
}
