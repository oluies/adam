#!/bin/bash
# Runs one DuckDB stage over an ADAM alignment directory.
#   run.sh sort|markdup <input dir> <output dir>
#   run.sh validate_sort|validate_markdup <ADAM output dir> <DuckDB output dir>
# A stage's output keeps the input's sidecar files, so ADAM can load it.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
stage=$1
a=$(cd "$2" && pwd)

case $stage in
  sort | markdup)
    mkdir -p "$3"
    b=$(cd "$3" && pwd)
    rm -f "$b"/part-*.parquet "$b"/_duckdb_*.parquet
    cp "$a"/_references.avro "$a"/_readGroups.avro "$a"/_processingSteps.avro "$b"/
    duckdb -no-agent -bail -batch \
      -cmd "SET VARIABLE input = '$a'; SET VARIABLE output = '$b';" \
      < "$here/$stage.sql"
    ;;
  validate_sort | validate_markdup)
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
