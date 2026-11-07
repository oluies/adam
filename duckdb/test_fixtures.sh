#!/bin/bash
# Runs ADAM and DuckDB sort and duplicate marking over each fixture, and fails
# unless DuckDB's output matches ADAM's.
#   test_fixtures.sh [work dir]
# Needs the ADAM assembly built (./mill adam-assembly.assembly) and Spark
# found by bin/find-spark.sh (SPARK_HOME).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(dirname "$here")
work=${1:-$(mktemp -d)}
resources=$repo/adam-core/src/test/resources
mkdir -p "$work"
awk -f "$here/fixtures/make_edge_cases.awk" "$resources/bqsr1.sam" > "$work/markdup_edge_cases.sam"
fixtures=(
  "$work/markdup_edge_cases.sam"
  "$resources/bqsr1.sam"
  "$resources/reads12.sam"
  "$resources/unmapped.sam"
  "$resources/NA12878.1_922305.G_GC_hom.sam"
  "$resources/small.sam"
)

adam() { "$repo/bin/adam-submit" --master 'local[4]' -- "$@" 2>>"$work/adam.log" >/dev/null; }

# prints the value of a check from a validation's CSV output
check() { awk -F, -v c="$2" '$1 == c { print $2 }' <<<"$1"; }

failed=0
for sam in "${fixtures[@]}"; do
  name=$(basename "$sam" .sam)
  dir=$work/$name
  rm -rf "$dir" && mkdir -p "$dir"
  adam transformAlignments "$sam" "$dir/plain.adam"
  adam transformAlignments "$dir/plain.adam" "$dir/adam-markdup.adam" -mark_duplicate_reads
  adam transformAlignments "$dir/plain.adam" "$dir/adam-sort.adam" -sort_by_reference_position
  "$here/run.sh" markdup "$dir/plain.adam" "$dir/duck-markdup.adam"
  "$here/run.sh" sort "$dir/plain.adam" "$dir/duck-sort.adam"

  markdup=$("$here/run.sh" validate_markdup "$dir/adam-markdup.adam" "$dir/duck-markdup.adam")
  sort=$("$here/run.sh" validate_sort "$dir/adam-sort.adam" "$dir/duck-sort.adam")

  reads=$(check "$markdup" reads)
  expected=$(check "$markdup" "duplicates in expected")
  actual=$(check "$markdup" "duplicates in actual")
  ties=$(check "$markdup" "mismatches explained by ties")
  bad=$(( $(check "$markdup" "mismatches not explained") + $(check "$markdup" "reads only in one output") \
        + $(check "$markdup" "duplicate keys in expected") ))
  unsorted=$(awk -F, '$1 == "expected" || $1 == "actual" { s += $3 } $1 ~ /^only in/ { s += $2 } END { print s }' <<<"$sort")

  status=ok
  if (( bad > 0 || unsorted > 0 )); then status=FAIL; failed=1; fi
  printf '%-32s %-4s reads=%-5s dups adam=%-5s duckdb=%-5s tie-explained=%-3s markdup-bad=%s sort-bad=%s\n' \
    "$name" "$status" "$reads" "$expected" "$actual" "$ties" "$bad" "$unsorted"
done
exit $failed
