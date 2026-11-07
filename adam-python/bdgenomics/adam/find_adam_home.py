#!/usr/bin/env python

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

# This script is copied from Apache Spark, with minor modifications.
#
# This script attempt to determine the correct setting for ADAM_HOME given
# that ADAM may have been installed on the system with pip.

import os
import sys
from importlib.util import find_spec


def _is_adam_home(path: str) -> bool:
    """True if path looks like an ADAM_HOME: bin/adam-submit and the ADAM jar."""
    return os.path.isfile(os.path.join(path, "bin/adam-submit")) and (
        os.path.isdir(os.path.join(path, "jars"))
        or os.path.isfile(os.path.join(path, "out/adam-assembly/assembly.dest/out.jar"))
    )


def _candidate_paths(module_file: str, package_origin: str | None) -> list[str]:
    """Where ADAM_HOME may be: next to this module, and inside the installed bdgenomics package."""
    paths = [os.path.join(os.path.dirname(os.path.realpath(module_file)), "..")]
    if package_origin is not None:
        paths.append(os.path.join(os.path.dirname(package_origin), "adam"))
    return [os.path.abspath(p) for p in paths]


def _find_adam_home(environ: dict[str, str], candidates: list[str]) -> str | None:
    """ADAM_HOME from the environment, else the first candidate that looks like one."""
    if "ADAM_HOME" in environ:
        return environ["ADAM_HOME"]
    return next((path for path in candidates if _is_adam_home(path)), None)


def main() -> None:
    spec = find_spec("bdgenomics")
    candidates = _candidate_paths(__file__, spec.origin if spec is not None else None)
    adam_home = _find_adam_home(dict(os.environ), candidates)
    if adam_home is None:
        print(
            f"Could not find valid ADAM_HOME while searching {candidates}",
            file=sys.stderr,
        )
        sys.exit(1)
    print(adam_home)


if __name__ == "__main__":
    main()
