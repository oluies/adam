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
Bundles the ADAM assembly jar and launcher scripts into the package.

Project metadata is in pyproject.toml. This file only stages the jar
(out/adam-assembly/assembly.dest/out.jar, from ./mill adam-assembly.assembly)
and ../bin into deps/ for the duration of the build, and supplies the version.
"""

import os
import runpy
import sys
from pathlib import Path

from setuptools import find_packages, setup

# a PEP 517 build does not put this directory on sys.path, so read version.py by path
adam_version = runpy.run_path(str(Path(__file__).parent / "version.py"))["version"]

ADAM_HOME = Path("..").resolve()
TEMP_PATH = Path("deps")
JARS_TARGET = TEMP_PATH / "jars"
SCRIPTS_TARGET = TEMP_PATH / "bin"
ASSEMBLY_JAR = ADAM_HOME / "out" / "adam-assembly" / "assembly.dest" / "out.jar"

INCORRECT_INVOCATION = """
If you are installing bdgenomics.adam from ADAM's source, you must first build
the ADAM assembly jar:
    ./mill adam-assembly.assembly
and then build the package in the adam-python directory:
    cd adam-python
    uv build"""

in_adam = (
    ADAM_HOME / "adam-core/src/main/scala/org/bdgenomics/adam/ds/ADAMContext.scala"
).is_file()

if in_adam and not ASSEMBLY_JAR.is_file():
    sys.exit(INCORRECT_INVOCATION)
# outside the ADAM tree, deps/ must come from a source distribution
if not in_adam and not TEMP_PATH.is_dir():
    sys.exit(INCORRECT_INVOCATION)

if in_adam:
    try:
        TEMP_PATH.mkdir()
    except FileExistsError:
        sys.exit(f"Temp path for symlink to parent already exists: {TEMP_PATH}")

try:
    if in_adam:
        JARS_TARGET.mkdir()
        (JARS_TARGET / "adam.jar").symlink_to(ASSEMBLY_JAR)
        SCRIPTS_TARGET.symlink_to(ADAM_HOME / "bin")

    packages = find_packages(exclude=["*.test", "*.test.*"])
    packages.extend(["bdgenomics.adam.jars", "bdgenomics.adam.bin"])

    scripts = [str(SCRIPTS_TARGET / name) for name in os.listdir(SCRIPTS_TARGET)]
    scripts.append("bdgenomics/adam/find_adam_home.py")

    setup(
        version=adam_version,
        scripts=scripts,
        packages=packages,
        include_package_data=True,
        package_dir={
            "bdgenomics.adam.jars": str(JARS_TARGET),
            "bdgenomics.adam.bin": str(SCRIPTS_TARGET),
        },
        package_data={
            "bdgenomics.adam.jars": ["adam.jar"],
            "bdgenomics.adam.bin": ["*"],
        },
    )

finally:
    if in_adam:
        (JARS_TARGET / "adam.jar").unlink(missing_ok=True)
        if JARS_TARGET.is_dir():
            JARS_TARGET.rmdir()
        SCRIPTS_TARGET.unlink(missing_ok=True)
        TEMP_PATH.rmdir()
