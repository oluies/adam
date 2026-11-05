Building ADAM from Source
=========================

ADAM builds with `Mill <https://mill-build.org>`__. The ``./mill``
launcher script in the repository downloads Mill and the JDK it needs.

.. code:: bash

    git clone https://github.com/bigdatagenomics/adam.git
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
To build and test `ADAM's Python bindings <#python>`__, run:

.. code:: bash

    ./mill adam-python.develop
    ./mill adam-python.pytest

These Mill commands invoke the ``adam-python`` Makefile, which builds a
Python egg and runs tests. To build this module, we require either an active
`Conda <https://conda.io/>`__ or
`virtualenv <https://virtualenv.pypa.io/en/stable/>`__ environment.

`To setup and activate a Conda
environment <https://conda.io/docs/using/envs.html>`__ for Python 3.6, run:

.. code:: bash

    conda create -n adam python=3.6 anaconda
    source activate adam


`To setup and activate a virtualenv
environment <https://virtualenv.pypa.io/en/stable/userguide/#usage>`__,
run:

.. code:: bash

    virtualenv adam
    . adam/bin/activate

Additionally, to run tests, the PySpark dependencies must be on the
Python module load path and the ADAM JARs must be built and provided to
PySpark. This can be done with the following bash commands:

.. code:: bash

    # add pyspark to the python path
    PY4J_ZIP="$(ls -1 "${SPARK_HOME}/python/lib" | grep py4j)"
    export PYTHONPATH=${SPARK_HOME}/python:${SPARK_HOME}/python/lib/${PY4J_ZIP}:${PYTHONPATH}

    # put adam jar on the pyspark path
    ASSEMBLY_JAR="$("${ADAM_HOME}/bin/find-adam-assembly.sh")"
    export PYSPARK_SUBMIT_ARGS="--jars ${ASSEMBLY_JAR} --driver-class-path ${ASSEMBLY_JAR} pyspark-shell"

This assumes that the `ADAM JARs have already been
built <#build-from-source>`__. Additionally, we require
`pytest <https://docs.pytest.org/en/latest/>`__ to be installed. The
adam-python makefile can install this dependency. Once you have an
active virtualenv or Conda environment, run:

.. code:: bash

    cd adam-python
    make prepare

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
