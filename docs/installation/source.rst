Building ADAM from Source
=========================

ADAM builds with `Mill <https://mill-build.org>`__. The ``./mill``
launcher script in the repository downloads Mill and the JDK it needs.

.. code:: bash

    git clone https://github.com/oluies/adam.git
    cd adam
    ./mill __.compile
    ./mill __.test
    ./mill adam-assembly.assembly


Running ADAM
------------

ADAM is packaged as an
überjar (``./mill adam-assembly.assembly``) and
includes all necessary dependencies, except for Apache Hadoop and Apache
Spark.

You might want to add the following to your ``.bashrc`` to make running
ADAM easier:

.. code:: bash

    alias adam-submit="${ADAM_HOME}/bin/adam-submit"
    alias adam-shell="${ADAM_HOME}/bin/adam-shell"

``$ADAM_HOME`` should be the path to where you have checked ADAM out on
your local filesystem. The first alias should be used for running ADAM
jobs that operate locally. The latter two aliases call scripts that wrap
the ``spark-submit`` and ``spark-shell`` commands to set up ADAM. You
will need to have the Spark binaries on your system; prebuilt binaries
can be downloaded from the `Spark
website <http://spark.apache.org/downloads.html>`__.

As of ADAM version 2.0.0, Spark version 4.0.0 or later (Scala 2.13, Java 17 or later) is required.

Once this alias is in place, you can run ADAM by simply typing
``adam-submit`` at the command line.

.. code:: bash

    adam-submit

Building for Python
-------------------

ADAM can be installed using the Pip package manager, or from source.
ADAM's Python bindings need Python 3.11 or later (as PySpark 4.3 does) and
`uv <https://docs.astral.sh/uv/>`__, which installs the Python version pinned
in ``adam-python/.python-version`` and manages the virtualenv. To build and
test `ADAM's Python bindings <#python>`__, run:

.. code:: bash

    ./mill adam-assembly.assembly
    ./mill adam-python.develop
    ./mill adam-python.pytest

These Mill commands invoke the ``adam-python`` Makefile. ``develop`` creates
``adam-python/.venv`` with PySpark and the test requirements, and zips the
Python package for ``bin/pyadam``. The tests need the ADAM assembly jar on
PySpark's classpath and a JDK (17 or later) on the ``PATH``:

.. code:: bash

    ASSEMBLY_JAR="$("${ADAM_HOME}/bin/find-adam-assembly.sh")"
    export PYSPARK_SUBMIT_ARGS="--jars ${ASSEMBLY_JAR} --driver-class-path ${ASSEMBLY_JAR} pyspark-shell"

To build a wheel and source distribution, run ``uv build`` in ``adam-python``.

Building for R
--------------

ADAM supports SparkR. To build and test `ADAM's R bindings <#r>`__, run:

.. code:: bash

    ./mill adam-r.check

This Mill command invokes the ``R`` executable to build the
``bdg.adam`` package and run tests. The build requires the ``testthat``,
``devtools`` and ``roxygen`` packages

.. code:: bash

    R -e "install.packages('testthat', repos='http://cran.rstudio.com/')"
    R -e "install.packages('roxygen2', repos='http://cran.rstudio.com/')"
    R -e "install.packages('devtools', repos='http://cran.rstudio.com/')"

Installation of ``devtools`` may require ``libgit2`` as a dependency.

.. code:: bash

    apt-get install libgit2-dev

The build also requires you to have the ``SparkR`` package installed,
where ``v4.x.x`` should match your Spark version.

.. code:: bash

   R -e "devtools::install_github('apache/spark@v4.x.x', subdir='R/pkg')"

The ADAM JARs can then be provided to ``SparkR`` with the following bash
commands:

.. code:: bash

    # put adam jar on the SparkR path
    ASSEMBLY_JAR="$("${ADAM_HOME}/bin/find-adam-assembly.sh")"
    export SPARKR_SUBMIT_ARGS="--jars ${ASSEMBLY_JAR} --driver-class-path ${ASSEMBLY_JAR} sparkr-shell"

Note that the ``ASSEMBLY_JAR`` line is the same
as for the `Python build <#python-build>`__. As with the Python build,
this assumes that the `ADAM JARs have already been
built <#build-from-source>`__.
