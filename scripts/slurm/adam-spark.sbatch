#!/usr/bin/env bash
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
# Start a Spark standalone cluster on the nodes of a Slurm job, run one ADAM
# command on it, and stop the cluster.
#
#   sbatch adam-spark.sbatch <adam-args>     # run ADAM, then stop
#   sbatch adam-spark.sbatch                 # keep the cluster up for adam-shell
#
# Needs SPARK_HOME (Spark 4, on shared disk), ADAM_HOME (an ADAM checkout
# built with ./mill adam-assembly.assembly, or a distribution) and Java 17 or
# later on every node. Change the #SBATCH lines below, or override them on the
# sbatch command line, to fit your cluster.
#
#SBATCH --job-name=adam-spark
#SBATCH --nodes=4
#SBATCH --ntasks-per-node=1
#SBATCH --cpus-per-task=32
#SBATCH --mem=192G
#SBATCH --exclusive
#SBATCH --time=04:00:00
#SBATCH --output=adam-spark-%j.out

set -euo pipefail

: "${SPARK_HOME:?set SPARK_HOME to a Spark 4 distribution on shared disk}"
: "${ADAM_HOME:?set ADAM_HOME to ADAM on shared disk}"

# Tunables, all optional.
LOCAL_SCRATCH=${LOCAL_SCRATCH:-${TMPDIR:-/tmp}} # node-local disk for shuffle and spill
DRIVER_MEMORY_GB=${DRIVER_MEMORY_GB:-8}         # driver heap, on the first node
EXECUTOR_CORES=${EXECUTOR_CORES:-5}             # cores per executor
MASTER_HOST=${MASTER_HOST:-$(hostname)}         # set to a fast interface's name if needed
MASTER_PORT=${MASTER_PORT:-7077}
MASTER_WEBUI_PORT=${MASTER_WEBUI_PORT:-8080}

nodes=${SLURM_JOB_NUM_NODES:?run under sbatch}
cpus=${SLURM_CPUS_PER_TASK:?run with --cpus-per-task}
node_mem_mb=${SLURM_MEM_PER_NODE:-0}
if (( node_mem_mb <= 0 )); then
  echo "Run with --mem, e.g. --mem=192G, so Spark's memory can be sized" >&2
  exit 2
fi

# Size each node's worker at 80% of the job's memory on the node, less the
# driver's heap. Executors use memory beyond their heap that the worker does
# not count, and the rest is left for that and the OS.
worker_mem_mb=$(( node_mem_mb * 80 / 100 - DRIVER_MEMORY_GB * 1024 ))
if (( EXECUTOR_CORES > cpus )); then EXECUTOR_CORES=$cpus; fi
executors_per_node=$(( cpus / EXECUTOR_CORES ))
executor_mem_mb=$(( worker_mem_mb / executors_per_node ))
if (( executor_mem_mb < 1024 )); then
  echo "Only ${executor_mem_mb} MB per executor; give the job more --mem" >&2
  exit 2
fi

job_dir=spark-${SLURM_JOB_ID}
master_url=spark://${MASTER_HOST}:${MASTER_PORT}
export SPARK_LOG_DIR=${SPARK_LOG_DIR:-${SLURM_SUBMIT_DIR:-$PWD}/${job_dir}/logs}
mkdir -p "$SPARK_LOG_DIR" "$LOCAL_SCRATCH/$job_dir"

master_pid=
workers_pid=
cleanup() {
  local status=$?
  trap - EXIT
  set +e
  # srun passes the TERM on to the workers.
  if [[ -n $workers_pid ]]; then kill "$workers_pid" 2>/dev/null; fi
  if [[ -n $master_pid ]]; then kill "$master_pid" 2>/dev/null; fi
  wait 2>/dev/null
  srun --overlap --nodes="$nodes" --ntasks-per-node=1 --cpus-per-task=1 \
    rm -rf "$LOCAL_SCRATCH/$job_dir"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 143' TERM INT # scancel and the time limit send TERM

# The master runs in this batch step, on the job's first node.
"$SPARK_HOME/bin/spark-class" org.apache.spark.deploy.master.Master \
  --host "$MASTER_HOST" --port "$MASTER_PORT" --webui-port "$MASTER_WEBUI_PORT" \
  > "$SPARK_LOG_DIR/master.out" 2>&1 &
master_pid=$!

for _ in $(seq 60); do
  (exec 3<> "/dev/tcp/$MASTER_HOST/$MASTER_PORT") 2>/dev/null && break
  sleep 1
done

# One worker per node, in one job step. Each keeps its shuffle and spill
# files, and its executors' work directories, on node-local scratch.
export LOCAL_SCRATCH job_dir cpus worker_mem_mb master_url
# shellcheck disable=SC2016 # expanded on each node, not here
srun --overlap --nodes="$nodes" --ntasks-per-node=1 --cpus-per-task="$cpus" \
  bash -c '
    dir=$LOCAL_SCRATCH/$job_dir
    mkdir -p "$dir/work"
    export SPARK_LOCAL_DIRS=$dir
    exec "$SPARK_HOME/bin/spark-class" org.apache.spark.deploy.worker.Worker \
      --cores "$cpus" --memory "${worker_mem_mb}m" --work-dir "$dir/work" \
      "$master_url" > "$SPARK_LOG_DIR/worker-$(hostname).out" 2>&1
  ' &
workers_pid=$!

alive=0
for _ in $(seq 120); do
  alive=$(curl -fsS "http://$MASTER_HOST:$MASTER_WEBUI_PORT/json/" 2>/dev/null \
    | grep -o '"aliveworkers" *: *[0-9]*' | grep -o '[0-9]*$' || echo 0)
  (( alive >= nodes )) && break
  sleep 1
done
if (( alive < nodes )); then
  echo "Only $alive of $nodes Spark workers registered; see $SPARK_LOG_DIR" >&2
  exit 1
fi

echo "Spark master: $master_url"
echo "Spark web UI: http://$MASTER_HOST:$MASTER_WEBUI_PORT"
echo "Spark logs:   $SPARK_LOG_DIR"
echo "$nodes workers x $cpus cores, $executors_per_node executors per node" \
  "of $EXECUTOR_CORES cores and ${executor_mem_mb} MB"

if (( $# == 0 )); then
  echo "No ADAM command given; the cluster stays up until the job ends or is cancelled."
  wait "$workers_pid"
  exit 0
fi

"$ADAM_HOME/bin/adam-submit" \
  --master "$master_url" \
  --driver-memory "${DRIVER_MEMORY_GB}g" \
  --executor-cores "$EXECUTOR_CORES" \
  --executor-memory "${executor_mem_mb}m" \
  --conf "spark.local.dir=$LOCAL_SCRATCH/$job_dir" \
  -- "$@"
