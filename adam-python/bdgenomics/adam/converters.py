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

"""
Names of the JVM converters between genomic dataset types.

GenomicDatasetConverters.scala names each converter <From>To<To>DatasetConverter
with plural type names (AlignmentsToFragmentsDatasetConverter). This module
holds the <To>DatasetConverter half for each Python dataset class.
"""

_CONVERTER_SUFFIXES = {
    "AlignmentDataset": "AlignmentsDatasetConverter",
    "CoverageDataset": "CoverageDatasetConverter",
    "FeatureDataset": "FeaturesDatasetConverter",
    "FragmentDataset": "FragmentsDatasetConverter",
    "GenotypeDataset": "GenotypesDatasetConverter",
    "ReadDataset": "ReadsDatasetConverter",
    "SequenceDataset": "SequencesDatasetConverter",
    "SliceDataset": "SlicesDatasetConverter",
    "VariantDataset": "VariantsDatasetConverter",
}


def converter_suffix(dest_class):
    """
    The JVM converter name suffix for converting into dest_class.

    :param dest_class: The Python genomic dataset class to convert into.
    :return: The suffix, e.g. "FragmentsDatasetConverter".
    :raises ValueError: If no converter into dest_class exists.
    """
    name = getattr(dest_class, "__name__", None)
    if name not in _CONVERTER_SUFFIXES:
        raise ValueError("No conversion method known for %s." % dest_class)
    return _CONVERTER_SUFFIXES[name]
