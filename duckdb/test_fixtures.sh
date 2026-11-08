#!/bin/bash
# Runs ADAM and DuckDB sort, duplicate marking and base quality recalibration
# over each fixture, and fails unless DuckDB's output matches ADAM's.
#   test_fixtures.sh [work dir]
# Needs the ADAM assembly built (./mill adam-assembly.assembly) and Spark
# found by bin/find-spark.sh (SPARK_HOME).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(dirname "$here")
work=${1:-$(mktemp -d)}
resources=$repo/adam-core/src/test/resources
known_snps=$resources/bqsr1.vcf
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
  adam transformAlignments "$dir/plain.adam" "$dir/adam-bqsr.adam" -recalibrate_base_qualities -known_snps "$known_snps"
  "$here/run.sh" markdup "$dir/plain.adam" "$dir/duck-markdup.adam"
  "$here/run.sh" sort "$dir/plain.adam" "$dir/duck-sort.adam"
  "$here/run.sh" bqsr "$dir/plain.adam" "$dir/duck-bqsr.adam" "$known_snps"

  markdup=$("$here/run.sh" validate_markdup "$dir/adam-markdup.adam" "$dir/duck-markdup.adam")
  sort=$("$here/run.sh" validate_sort "$dir/adam-sort.adam" "$dir/duck-sort.adam")
  bqsr=$("$here/run.sh" validate_bqsr "$dir/adam-bqsr.adam" "$dir/duck-bqsr.adam")

  reads=$(check "$markdup" reads)
  expected=$(check "$markdup" "duplicates in expected")
  actual=$(check "$markdup" "duplicates in actual")
  ties=$(( $(check "$markdup" "mismatches explained by ties") + $(check "$markdup" "mismatches explained by mate choice") ))
  bad=$(( $(check "$markdup" "mismatches not explained") + $(check "$markdup" "reads only in one output") \
        + $(check "$markdup" "duplicate keys in expected") ))
  recalibrated=$(check "$bqsr" "reads recalibrated by ADAM")
  bqsr_bad=$(( $(check "$bqsr" "reads only in one output") + $(check "$bqsr" "original qualities differ") \
             + $(check "$bqsr" "bases with different qualities") ))
  unsorted=$(awk -F, '$1 == "expected" || $1 == "actual" { s += $3 } $1 ~ /^only in/ { s += $2 } END { print s }' <<<"$sort")

  status=ok
  if (( bad > 0 || unsorted > 0 || bqsr_bad > 0 )); then status=FAIL; failed=1; fi
  printf '%-28s %-4s reads=%-5s dups=%s/%-4s ties=%-3s markdup-bad=%s sort-bad=%s recalibrated=%-4s bqsr-bad=%s\n' \
    "$name" "$status" "$reads" "$expected" "$actual" "$ties" "$bad" "$unsorted" "$recalibrated" "$bqsr_bad"
done
exit $failed
