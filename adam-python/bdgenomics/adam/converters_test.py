#
# Licensed to Big Data Genomics (BDG) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The BDG licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import os
import re
import unittest

from bdgenomics.adam.converters import _CONVERTER_SUFFIXES, converter_suffix

_SCALA_CONVERTERS = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "..", "..", "adam-apis", "src", "main", "scala",
    "org", "bdgenomics", "adam", "api", "java", "GenomicDatasetConverters.scala")


class AlignmentDataset(object):
    pass


class VariantContextDataset(object):
    pass


class ConvertersTest(unittest.TestCase):

    def test_suffix_for_a_dataset_class(self):
        self.assertEqual(converter_suffix(AlignmentDataset), "AlignmentsDatasetConverter")

    def test_unknown_class_is_rejected(self):
        with self.assertRaises(ValueError):
            converter_suffix(VariantContextDataset)
        with self.assertRaises(ValueError):
            converter_suffix("not a class")

    def test_every_suffix_names_a_jvm_converter(self):
        with open(_SCALA_CONVERTERS) as scala:
            source = scala.read()
        jvm_suffixes = set(re.findall(r"final class \w+?To(\w+DatasetConverter)\b", source))
        for name, suffix in _CONVERTER_SUFFIXES.items():
            self.assertIn(suffix, jvm_suffixes, "%s -> %s" % (name, suffix))

    def test_every_jvm_converter_target_has_a_python_class(self):
        with open(_SCALA_CONVERTERS) as scala:
            source = scala.read()
        jvm_suffixes = set(re.findall(r"final class \w+?To(\w+DatasetConverter)\b", source))
        self.assertEqual(jvm_suffixes, set(_CONVERTER_SUFFIXES.values()))


if __name__ == "__main__":
    unittest.main()
