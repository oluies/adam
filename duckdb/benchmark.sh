#!/bin/bash
# Times ADAM and DuckDB on the same reads, stage by stage and chained,
# interleaving the two run by run.
#   benchmark.sh <reads.adam> <known SNPs VCF> <runs> <work dir>
# Prints one line per run: engine, step, wall seconds, CPU seconds (user +
# sys, child processes included). ADAM runs on Spark local[4] with 4 GB;
# DuckDB runs with 4 threads and 4 GB ("duckdb-4") and unconstrained
# ("duckdb"). "adam read-write" is a transformAlignments that only reads and
# writes, the fixed cost under every ADAM step. ENGINES and STEPS limit a run
# (e.g. ENGINES="duckdb" STEPS="bqsr pipeline").
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(dirname "$here")
input=$1; snps=$2; runs=$3; work=$4
mkdir -p "$work"
rm -f "$work/results.tsv"

# prints "wall cpu status" for one command; a failure is recorded, not fatal
measure() {
  local status=0
  /usr/bin/time -p -o "$work/time.txt" "$@" >/dev/null 2>>"$work/log.txt" || status=$?
  awk -v s="$status" '/^real/ { r = $2 } /^user|^sys/ { c += $2 } END { printf "%.1f %.1f %d\n", r, c, s }' "$work/time.txt"
}

record() { printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" | tee -a "$work/results.tsv"; }

timed() {
  local engine=$1 step=$2 wall cpu status; shift 2
  read -r wall cpu status < <(measure "$@")
  if (( status != 0 )); then step="$step (failed)"; fi
  record "$engine" "$step" "$wall" "$cpu"
}

# ADAM_MEMORY overrides the driver memory, 4g by default
adam() {
  local step=$1; shift
  rm -rf "$work/adam-$step.adam"
  timed adam "$step" "$repo/bin/adam-submit" --master 'local[4]' --driver-memory "${ADAM_MEMORY:-4g}" -- \
    transformAlignments "$input" "$work/adam-$step.adam" "$@"
}

duck() {
  local engine=$1 settings=$2 step=$3
  local out=$work/$engine-$step.adam
  case $step in
    markdup | sort) timed "$engine" "$step" env DUCKDB_SETTINGS="$settings" "$here/run.sh" "$step" "$input" "$out" ;;
    bqsr) timed "$engine" "$step" env DUCKDB_SETTINGS="$settings" "$here/run.sh" bqsr "$input" "$out" "$snps" ;;
    # three runs, timed one by one: macOS loses the CPU time of scripts run
    # inside another shell
    pipeline)
      local w1 c1 s1 w2 c2 s2 w3 c3 s3
      read -r w1 c1 s1 < <(measure env DUCKDB_SETTINGS="$settings" "$here/run.sh" markdup "$input" "$work/$engine-p1.adam")
      read -r w2 c2 s2 < <(measure env DUCKDB_SETTINGS="$settings" "$here/run.sh" bqsr "$work/$engine-p1.adam" "$work/$engine-p2.adam" "$snps")
      read -r w3 c3 s3 < <(measure env DUCKDB_SETTINGS="$settings" "$here/run.sh" sort "$work/$engine-p2.adam" "$out")
      if (( s1 + s2 + s3 != 0 )); then step="$step (failed)"; fi
      record "$engine" "$step" "$(echo "$w1 + $w2 + $w3" | bc)" "$(echo "$c1 + $c2 + $c3" | bc)" ;;
  esac
}

engines=" ${ENGINES:-adam duckdb-4 duckdb} "
steps=" ${STEPS:-read-write markdup bqsr sort pipeline} "
want() { [[ $engines == *" $1 "* && $steps == *" $2 "* ]]; }

for _ in $(seq "$runs"); do
  if want adam read-write; then adam read-write; fi
  if want adam markdup; then adam markdup -mark_duplicate_reads; fi
  if want adam bqsr; then adam bqsr -recalibrate_base_qualities -known_snps "$snps"; fi
  if want adam sort; then adam sort -sort_by_reference_position; fi
  if want adam pipeline; then
    adam pipeline -mark_duplicate_reads -recalibrate_base_qualities -known_snps "$snps" -sort_by_reference_position
  fi
  for step in markdup bqsr sort pipeline; do
    if want duckdb-4 "$step"; then duck duckdb-4 "SET threads = 4; SET memory_limit = '4GB';" "$step"; fi
    if want duckdb "$step"; then duck duckdb "" "$step"; fi
  done
done

duckdb -no-agent -box -c "
  SELECT step, engine, count(*) AS runs,
         median(wall) AS wall_median, min(wall) AS wall_min,
         median(cpu) AS cpu_median, min(cpu) AS cpu_min
  FROM read_csv('$work/results.tsv', delim = '\t', header = false,
                columns = {'engine': 'VARCHAR', 'step': 'VARCHAR', 'wall': 'DOUBLE', 'cpu': 'DOUBLE'})
  GROUP BY ALL ORDER BY step, engine"
