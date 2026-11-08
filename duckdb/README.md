# ADAM preprocessing on DuckDB

SQL ports of ADAM's alignment preprocessing stages, for DuckDB 2.0. They read
and write ADAM's alignment Parquet directories, so ADAM can load their output
and the stages can mix with ADAM's own.

| Stage | Script | Ports |
| --- | --- | --- |
| Sort | `sort.sql` | `transformAlignments -sort_by_reference_position` |
| Duplicate marking | `markdup.sql` | `transformAlignments -mark_duplicate_reads` |
| Base quality recalibration | `bqsr.sql` | `transformAlignments -recalibrate_base_qualities [-known_snps] [-min_acceptable_quality]` |

```bash
adam-submit -- transformAlignments in.sam reads.adam   # SAM/BAM to Parquet, once
duckdb/run.sh markdup reads.adam marked.adam
duckdb/run.sh bqsr marked.adam recalibrated.adam known_snps.vcf   # VCF optional
duckdb/run.sh sort recalibrated.adam sorted.adam
```

Not ported: INDEL realignment (keep ADAM's, between duplicate marking and
recalibration), BQSR's `-sampling_fraction`, and CIGAR `P` operators.

## Matching ADAM

`test_fixtures.sh` runs ADAM and DuckDB over six fixtures and fails unless
the outputs match:

- **Sort:** both outputs in sort-key order with the same rows. Spark's sort is
  not stable, so reads with equal keys may differ in order.
- **Duplicate marking:** the same `duplicateRead` on every read. ADAM keeps
  one of several equally scored buckets in shuffle order, so `markdup.sql`
  lists tied buckets in `_duckdb_ties.parquet`, and a mismatch there passes
  only when both outputs keep the same number of reads in the tie group.
- **Recalibration:** the same quality on every base, and the same
  `originalQualityScores`. Exact so far, though the log-space arithmetic
  sums doubles in a different order than ADAM, so a value on a rounding
  boundary could differ by one.

`fixtures/make_edge_cases.awk` builds a fixture with the cases the ADAM test
files lack: secondary and supplementary alignments, fragments beside pairs,
reverse-strand fragments and pairs, soft clips, score ties, and phred 0
bases.

Deliberately broken copies of `markdup.sql` (6) and `bqsr.sql` (8) check
that the fixtures catch mistakes; all are caught but one. Counting phred 0
bases in recalibration cannot change the output at the default minimum
quality: those bases keep their quality, and the per read group delta they
shift cancels out of ADAM's formula.

| Data | Reads | Result | ADAM | DuckDB |
| --- | --- | --- | --- | --- |
| HG00096 chr22, duplicate marking | 300,000 | 10,489 duplicates, identical | 6.6 s | 1.0 s |
| HG00096 chr22, recalibration | 300,000 | 129,300 reads recalibrated, identical | 11.6 s | 7.0 s |

ADAM's times include Spark start-up, which dominates at this size.

Needs DuckDB 2.0 (lambdas are written `lambda x: ...`) with the `avro`
extension, which reads ADAM's `_readGroups.avro`.
