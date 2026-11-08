#!/bin/bash
# Runs one DuckDB stage over an ADAM alignment directory.
#   run.sh sort|markdup <input dir> <output dir>
#   run.sh bqsr <input dir> <output dir> [known SNPs VCF] [min acceptable quality]
#   run.sh validate_sort|validate_markdup|validate_bqsr <ADAM output dir> <DuckDB output dir>
# A stage's output keeps the input's sidecar files, so ADAM can load it.
# DUCKDB_SETTINGS, if set, runs first (e.g. "SET threads = 4;").
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
stage=$1
a=$(cd "$2" && pwd)

sql() { duckdb -no-agent -bail -batch -cmd "${DUCKDB_SETTINGS:-} $1"; }

case $stage in
  sort | markdup)
    mkdir -p "$3"
    b=$(cd "$3" && pwd)
    rm -f "$b"/part-*.parquet "$b"/_duckdb_*.parquet
    cp "$a"/_references.avro "$a"/_readGroups.avro "$a"/_processingSteps.avro "$b"/
    sql "SET VARIABLE input = '$a'; SET VARIABLE output = '$b';" < "$here/$stage.sql"
    ;;
  bqsr)
    # Two passes, one part at a time, so memory is bounded by a part, as in
    # Spark: count each part's observations, sum them into the table, then
    # recalibrate each part.
    mkdir -p "$3"
    b=$(cd "$3" && pwd)
    known_snps=/dev/null
    if [[ -n ${4:-} ]]; then known_snps=$(cd "$(dirname "$4")" && pwd)/$(basename "$4"); fi
    work=$b/_duckdb_bqsr
    rm -rf "$b"/part-*.parquet "$b"/_duckdb_* && mkdir -p "$work/observed"
    cp "$a"/_references.avro "$a"/_readGroups.avro "$a"/_processingSteps.avro "$b"/
    vars="SET VARIABLE sites = '$work/sites.parquet'; SET VARIABLE recalibration_table = '$b/_duckdb_recalibration.parquet';
          SET VARIABLE min_acceptable_quality = ${5:-5};
          SET VARIABLE read_groups = '$a/_readGroups.avro';"
    sql "$vars SET VARIABLE known_snps = '$known_snps';" < "$here/bqsr_sites.sql"
    # BQSR_PARTS input parts per DuckDB run (4 by default): enough work per
    # run to use every thread, little enough to fit in memory
    parts=("$a"/part-*.parquet)
    step=${BQSR_PARTS:-4}
    for pass in observe apply; do
      for (( i = 0; i < ${#parts[@]}; i += step )); do
        batch=$(printf "'%s', " "${parts[@]:i:step}")
        name=$(basename "${parts[i]}")
        if [[ $pass == observe ]]; then out=$work/observed/$name; else out=$b/${name/.gz/}; fi
        cat "$here/bqsr_bases.sql" "$here/bqsr_$pass.sql" |
          sql "$vars SET VARIABLE input = [${batch%, }]; SET VARIABLE output = '$out';"
      done
      if [[ $pass == observe ]]; then
        sql "SET VARIABLE input = '$work/observed'; SET VARIABLE output = '$b/_duckdb_recalibration.parquet';" \
          < "$here/bqsr_table.sql"
      fi
    done
    rm -rf "$work"
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
