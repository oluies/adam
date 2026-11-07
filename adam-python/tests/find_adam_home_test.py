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
import subprocess
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from bdgenomics.adam import find_adam_home as fah

_SCRIPT = Path(fah.__file__)


def _make_home(root: Path, layout: str) -> Path:
    """An ADAM_HOME: bin/adam-submit plus a jars/ dir (pip) or the Mill assembly (source checkout)."""
    (root / "bin").mkdir(parents=True)
    (root / "bin" / "adam-submit").write_text("")
    if layout == "pip":
        (root / "jars").mkdir()
    else:
        jar = root / "out/adam-assembly/assembly.dest/out.jar"
        jar.parent.mkdir(parents=True)
        jar.write_text("")
    return root


class FindAdamHomeTest(unittest.TestCase):
    def test_a_pip_install_and_a_source_checkout_are_adam_homes(self):
        with TemporaryDirectory() as tmp:
            self.assertTrue(
                fah._is_adam_home(str(_make_home(Path(tmp) / "pip", "pip")))
            )
            self.assertTrue(
                fah._is_adam_home(str(_make_home(Path(tmp) / "src", "mill")))
            )

    def test_a_directory_without_the_launcher_or_the_jar_is_not(self):
        with TemporaryDirectory() as tmp:
            self.assertFalse(fah._is_adam_home(tmp))
            (Path(tmp) / "bin").mkdir()
            (Path(tmp) / "bin" / "adam-submit").write_text("")
            self.assertFalse(fah._is_adam_home(tmp))

    def test_candidates_are_next_to_the_module_and_in_the_package(self):
        candidates = fah._candidate_paths(
            "/x/bdgenomics/adam/find_adam_home.py", "/site/bdgenomics/__init__.py"
        )
        self.assertEqual(
            candidates,
            [os.path.abspath("/x/bdgenomics/adam/.."), "/site/bdgenomics/adam"],
        )
        self.assertEqual(len(fah._candidate_paths("/x/a/b.py", None)), 1)

    def test_the_environment_wins(self):
        self.assertEqual(
            fah._find_adam_home({"ADAM_HOME": "/env/adam"}, ["/nowhere"]), "/env/adam"
        )

    def test_the_first_candidate_that_is_an_adam_home(self):
        with TemporaryDirectory() as tmp:
            home = _make_home(Path(tmp) / "home", "pip")
            self.assertEqual(
                fah._find_adam_home({}, [str(Path(tmp) / "missing"), str(home)]),
                str(home),
            )
            self.assertIsNone(fah._find_adam_home({}, [str(Path(tmp) / "missing")]))

    def test_the_command_line_prints_adam_home(self):
        env = dict(os.environ, ADAM_HOME="/env/adam")
        done = subprocess.run(
            [sys.executable, str(_SCRIPT)],
            capture_output=True,
            text=True,
            check=True,
            env=env,
        )
        self.assertEqual(done.stdout.strip(), "/env/adam")

    def test_the_command_line_fails_without_an_adam_home(self):
        env = {k: v for k, v in os.environ.items() if k != "ADAM_HOME"}
        with TemporaryDirectory() as tmp:
            script = Path(tmp) / "bdgenomics" / "adam" / "find_adam_home.py"
            script.parent.mkdir(parents=True)
            script.write_text(_SCRIPT.read_text())
            done = subprocess.run(
                [sys.executable, str(script)],
                capture_output=True,
                text=True,
                check=False,
                env=env,
                cwd=tmp,
            )
        self.assertEqual(done.returncode, 1)
        self.assertIn("Could not find valid ADAM_HOME", done.stderr)


if __name__ == "__main__":
    unittest.main()
