Running ADAM on Slurm
---------------------

On an HPC cluster managed by
`Slurm <https://slurm.schedmd.com>`__, ADAM can run on a temporary Spark
cluster that lives inside an ordinary batch job. The job's first node runs
the Spark master and the ADAM driver, every node runs one Spark worker, and
the cluster stops when the job ends. Nothing needs to be installed by the
system administrators, and nothing keeps running between jobs.

Spark does not need HDFS here: ADAM reads and writes the cluster's shared
filesystem (Lustre, GPFS, NFS and so on) through ordinary paths, and
Spark keeps its shuffle and spill files on each node's local disk. This
approach follows Jonathan Dursi's
`Spark in HPC clusters <https://www.dursi.ca/post/spark-in-hpc-clusters>`__,
updated for Spark 4 and Slurm.

``scripts/slurm/adam-spark.sbatch`` does all of this. The rest of this page
explains how to set it up and use it.

Setting up
~~~~~~~~~~

Put these on a filesystem that every node mounts at the same path:

-  **Spark 4**, built for Scala 2.13, for example
   ``spark-4.3.0-bin-hadoop3.tgz`` from the `Spark downloads
   page <https://spark.apache.org/downloads.html>`__, unpacked. Point
   ``SPARK_HOME`` at it.
-  **ADAM**, either a source checkout built with
   ``./mill adam-assembly.assembly`` (see :doc:`../installation/source`)
   or an unpacked distribution. Point ``ADAM_HOME`` at it.
-  **Java 17 or later** on every node, for example through
   ``module load java/17`` or ``JAVA_HOME``.

``sbatch`` passes your environment to the job by default, so setting these in
the shell you submit from is enough:

.. code:: bash

    export SPARK_HOME=/shared/software/spark-4.3.0-bin-hadoop3
    export ADAM_HOME=/shared/software/adam
    export JAVA_HOME=/shared/software/jdk-17

Running an ADAM command
~~~~~~~~~~~~~~~~~~~~~~~

Give the ADAM command and its arguments after the script, as you would to
``adam-submit`` after ``--``. Input and output paths must be on the shared
filesystem:

.. code:: bash

    sbatch $ADAM_HOME/scripts/slurm/adam-spark.sbatch \
      transformAlignments /shared/data/NA12878.bam /shared/data/NA12878.adam \
      -mark_duplicate_reads \
      -recalibrate_base_qualities -known_snps /shared/data/dbsnp.vcf \
      -sort_by_reference_position

The job:

1. starts the Spark master on the first node, in the batch step;
2. starts one Spark worker per node, all in one ``srun`` step, each using all
   the cores the job has on its node and keeping its temporary files under
   ``$LOCAL_SCRATCH/spark-<job id>`` on that node;
3. waits until every worker has registered with the master;
4. runs ``adam-submit`` on the first node, sizing executors from the job's
   cores and memory;
5. stops the workers and the master, and deletes the node-local scratch
   directories, also when ADAM fails or the job is cancelled.

The job's output file (``adam-spark-<job id>.out``) shows the master's URL,
how the executors were sized, and ADAM's output. Spark's master and worker
logs are written to ``spark-<job id>/logs`` in the directory you submitted
from. The job's exit status is ADAM's.

Sizing the job
~~~~~~~~~~~~~~

The ``#SBATCH`` lines at the top of the script ask for 4 whole nodes with 32
cores and 192 GB each for 4 hours. Edit them, or override them when
submitting:

.. code:: bash

    sbatch --nodes=8 --cpus-per-task=48 --mem=360G --time=12:00:00 \
      --partition=bigmem \
      $ADAM_HOME/scripts/slurm/adam-spark.sbatch transformAlignments ...

Keep ``--ntasks-per-node=1``: the script runs one Spark worker per node, and
that worker uses all of ``--cpus-per-task``. ``--mem`` must be given, since
Spark's memory is sized from it.

From the job's resources the script sets:

-  **Worker memory:** 80% of ``--mem`` less the driver's heap. Executors use
   memory beyond their heap that Spark's standalone worker does not count,
   and the rest is left for that and for the operating system.
-  **Executors:** ``EXECUTOR_CORES`` cores each (5 by default), as many per
   node as fit, sharing the worker's memory equally.
-  **Driver:** ``DRIVER_MEMORY_GB`` GB of heap (8 by default), on the first
   node beside that node's worker.

These environment variables change the defaults:

=======================  ==================================================  ===============================
Variable                 Meaning                                             Default
=======================  ==================================================  ===============================
``LOCAL_SCRATCH``        Node-local directory for shuffle and spill files    ``$TMPDIR``, else ``/tmp``
``EXECUTOR_CORES``       Cores per executor                                  ``5``
``DRIVER_MEMORY_GB``     Driver heap in GB                                   ``8``
``MASTER_HOST``          Host name the master listens on                     the first node's ``hostname``
``MASTER_PORT``          Master port                                         ``7077``
``MASTER_WEBUI_PORT``    Master web UI port                                  ``8080``
``SPARK_LOG_DIR``        Where master and worker logs go                     ``spark-<job id>/logs``
=======================  ==================================================  ===============================

``LOCAL_SCRATCH`` matters most. Shuffles of a whole genome's reads write
hundreds of gigabytes of temporary files; on node-local SSDs they are fast,
while on the shared filesystem they are slow and burden every other user of
it. Ask your administrators where node-local disk is mounted if ``$TMPDIR``
is not on it.

If the nodes have a fast interconnect (InfiniBand, for example) with its own
host names, set ``MASTER_HOST`` to the first node's name on that network so
Spark's traffic uses it.

The fixed ports assume one Spark cluster per node, which ``--exclusive``
ensures. To share nodes with other jobs, drop ``--exclusive`` and choose
``MASTER_PORT`` and ``MASTER_WEBUI_PORT`` per job.

Using adam-shell
~~~~~~~~~~~~~~~~

Without an ADAM command, the script starts the cluster and keeps it up until
the job's time limit or until you cancel it:

.. code:: bash

    sbatch $ADAM_HOME/scripts/slurm/adam-spark.sbatch

Once the job is running, its output file shows the master URL and the
executor size. Connect from another allocation rather than from the login
node, passing the same executor size, since Spark's default is 1 GB:

.. code:: bash

    srun --nodes=1 --cpus-per-task=4 --mem=16G --time=02:00:00 --pty bash
    $ADAM_HOME/bin/adam-shell --master spark://node001:7077 \
      --driver-memory 8g --executor-cores 5 --executor-memory 24849m

Stop the cluster with ``scancel <job id>`` when you are done.

Watching the job
~~~~~~~~~~~~~~~~

The master's web UI is at ``http://<first node>:8080``, and the running
application's at port 4040 on the same node. Compute nodes are usually not
reachable from your workstation, so tunnel through the login node:

.. code:: bash

    ssh -L 8080:node001:8080 -L 4040:node001:4040 login.cluster.example.org

and open ``http://localhost:8080``.

Running the DuckDB stages
~~~~~~~~~~~~~~~~~~~~~~~~~

The DuckDB ports of sorting, duplicate marking and base quality recalibration
(see ``duckdb/README.md``) run on one node and need no Spark cluster. Run them
in a single-node job, with the DuckDB 2.0 CLI on ``PATH`` and DuckDB's threads, memory and spill directory set
from the allocation:

.. code:: bash

    #!/usr/bin/env bash
    #SBATCH --nodes=1
    #SBATCH --cpus-per-task=32
    #SBATCH --mem=128G
    #SBATCH --time=04:00:00
    set -euo pipefail

    spill=${TMPDIR:-/tmp}/duckdb-$SLURM_JOB_ID
    mkdir -p "$spill"
    trap 'rm -rf "$spill"' EXIT
    export DUCKDB_SETTINGS="SET threads = $SLURM_CPUS_PER_TASK; SET memory_limit = '100GB'; SET temp_directory = '$spill';"

    $ADAM_HOME/duckdb/run.sh markdup /shared/data/reads.adam /shared/data/marked.adam
    $ADAM_HOME/duckdb/run.sh bqsr /shared/data/marked.adam /shared/data/recalibrated.adam /shared/data/known_snps.vcf
    $ADAM_HOME/duckdb/run.sh sort /shared/data/recalibrated.adam /shared/data/sorted.adam

The conversion from BAM to ADAM's Parquet still runs in ADAM, for example in
an ``adam-spark.sbatch`` job before this one; ``sbatch --dependency=afterok:<job id>``
chains the two.

The script
~~~~~~~~~~

.. literalinclude:: ../../scripts/slurm/adam-spark.sbatch
   :language: bash
