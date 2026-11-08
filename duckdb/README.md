# ADAM preprocessing on DuckDB

SQL ports of ADAM's alignment preprocessing stages, for DuckDB 2.0. They read
and write ADAM's alignment Parquet directories, so ADAM can load their output
and the stages can mix with ADAM's own.

| Stage | Script | Ports |
| --- | --- | --- |
| Sort | `sort.sql` | `transformAlignments -sort_by_reference_position` |
| Duplicate marking | `markdup.sql` | `transformAlignments -mark_duplicate_reads` |

```bash
adam-submit -- transformAlignments in.sam reads.adam   # SAM/BAM to Parquet, once
duckdb/run.sh markdup reads.adam marked.adam
duckdb/run.sh sort marked.adam sorted.adam
```

## Matching ADAM

`test_fixtures.sh` runs ADAM and DuckDB over test fixtures and fails unless
the outputs match:

- **Sort:** both outputs in sort-key order with the same rows. Spark's sort is
  not stable, so reads with equal keys may differ in order.
- **Duplicate marking:** the same `duplicateRead` on every read. ADAM keeps
  one of several equally scored buckets in shuffle order, so `markdup.sql`
  lists tied buckets in `_duckdb_ties.parquet`, and a mismatch there passes
  only when both outputs keep the same number of reads in the tie group.

`fixtures/make_edge_cases.awk` builds a fixture with the cases the ADAM test
files lack: secondary and supplementary alignments, fragments beside pairs,
reverse-strand fragments and pairs, soft clips, and score ties.

Needs DuckDB 2.0 (lambdas are written `lambda x: ...`) with the `avro`
extension, which reads ADAM's `_readGroups.avro`.
