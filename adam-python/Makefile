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

define help

Supported targets: prepare, develop, sdist, clean, test, and pypi. All use uv
(https://docs.astral.sh/uv/), which manages the virtualenv and Python version.

The 'prepare' and 'develop' targets create .venv with an editable install of ADAM, its runtime
requirements and its test requirements. 'develop' also zips the bdgenomics package into
target/pyfiles/, for bin/pyadam to pass to PySpark with --py-files.

The 'clean' target undoes the effect of 'develop' and removes build output.

The 'test' target runs ADAM's unit tests. They need the ADAM assembly jar on PySpark's
classpath; see docs/installation/source.rst. Set the 'tests' variable to run a particular test, e.g.

	make test tests=bdgenomics/adam/test/alignmentDataset_test.py::AlignmentDatasetTest::test_save_sorted_sam

The 'pypi' target builds and publishes the current commit of ADAM to PyPI after enforcing that
the working copy and the index are clean.

endef
export help
help:
	@printf "$$help"

# This Makefile uses bash features like printf and <()
SHELL=bash
uv=uv
# bdgenomics/adam/test needs PySpark and the ADAM jar; tests/ needs neither
tests=bdgenomics tests
adam_version:=$(shell python3 version.py)
# outside dist/, which holds only what pypi publishes
pyfiles_zip:=target/pyfiles/bdgenomics.adam-$(adam_version).zip
package_sources:=$(shell find bdgenomics -name '*.py' -not -path '*/test/*')

green=\033[0;32m
normal=\033[0m\n
red=\033[0;31m


prepare:
	$(uv) sync

develop: prepare $(pyfiles_zip)
$(pyfiles_zip): $(package_sources)
	mkdir -p $(dir $@)
	rm -f $@
	printf '%s\n' $(package_sources) | zip -q -@ $@
clean_develop:
	- rm -rf .venv target/pyfiles

sdist:
	$(uv) build --sdist
clean_sdist:
	- rm -rf dist


test: prepare
	mkdir -p target
	$(uv) run pytest -vv --junitxml target/pytest-reports/tests.xml $(tests)


pypi: check_clean_working_copy
	rm -rf dist
	$(uv) build
	$(uv) publish dist/*.whl dist/*.tar.gz
clean_pypi:
	- rm -rf build/ dist/

clean: clean_develop clean_pypi


check_clean_working_copy:
	@printf "$(green)Checking if your working copy is clean ...$(normal)"
	@git diff --exit-code > /dev/null \
		|| ( printf "$(red)Your working copy looks dirty.$(normal)" ; false )
	@git diff --cached --exit-code > /dev/null \
		|| ( printf "$(red)Your index looks dirty.$(normal)" ; false )
	@test -z "$$(git ls-files --other --exclude-standard --directory)" \
		|| ( printf "$(red)You have are untracked files:$(normal)" \
			; git ls-files --other --exclude-standard --directory \
			; false )

.PHONY: help \
		prepare \
		develop clean_develop \
		sdist clean_sdist \
		test \
		pypi clean_pypi \
		clean \
		check_clean_working_copy
