#!/bin/bash
# Runs one DuckDB stage over an ADAM alignment directory.
#   run.sh sort|markdup <input dir> <output dir>
#   run.sh bqsr <input dir> <output dir> [known SNPs VCF] [min acceptable quality]
#   run.sh validate_sort|validate_markdup|validate_bqsr <ADAM output dir> <DuckDB output dir>
# A stage's output keeps the input's sidecar files, so ADAM can load it.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
stage=$1
a=$(cd "$2" && pwd)

case $stage in
  sort | markdup | bqsr)
    mkdir -p "$3"
    b=$(cd "$3" && pwd)
    known_snps=/dev/null
    if [[ -n ${4:-} ]]; then known_snps=$(cd "$(dirname "$4")" && pwd)/$(basename "$4"); fi
    rm -f "$b"/part-*.parquet "$b"/_duckdb_*.parquet
    cp "$a"/_references.avro "$a"/_readGroups.avro "$a"/_processingSteps.avro "$b"/
    duckdb -no-agent -bail -batch \
      -cmd "SET VARIABLE input = '$a'; SET VARIABLE output = '$b';
            SET VARIABLE known_snps = '$known_snps'; SET VARIABLE min_acceptable_quality = ${5:-5};" \
      < "$here/$stage.sql"
    ;;
  validate_sort | validate_markdup | validate_bqsr)
    b=$(cd "$3" && pwd)
    duckdb -csv -bail \
      -cmd "SET VARIABLE expected = '$a'; SET VARIABLE actual = '$b';" \
      < "$here/$stage.sql"
    ;;
  *)
    echo "unknown stage: $stage" >&2
    exit 2
    ;;
esac
