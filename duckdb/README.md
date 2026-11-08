# ADAM preprocessing on DuckDB

SQL ports of ADAM's alignment preprocessing stages, for DuckDB 2.0. They read
and write ADAM's alignment Parquet directories, so ADAM can load their output
and the stages can mix with ADAM's own.

| Stage | Script | Ports |
| --- | --- | --- |
| Sort | `sort.sql` | `transformAlignments -sort_by_reference_position` |
| Duplicate marking | `markdup.sql` | `transformAlignments -mark_duplicate_reads` |
| Base quality recalibration | `bqsr_*.sql`, driven by `run.sh` | `transformAlignments -recalibrate_base_qualities [-known_snps] [-min_acceptable_quality]` |

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
  ADAM also takes a mate's position from whichever of its primary and
  supplementary alignments the shuffle delivers first, which can move a
  bucket to another left position. Every bucket at the left positions such
  a choice can reach is listed too, and its mismatches are reported apart.
- **Recalibration:** the same quality on every base, and the same
  `originalQualityScores`. Exact so far, though the log-space arithmetic
  sums doubles in a different order than ADAM, so a value on a rounding
  boundary could differ by one.

`fixtures/make_edge_cases.awk` builds a fixture with the cases the ADAM test
files lack: secondary and supplementary alignments, fragments beside pairs,
reverse-strand fragments and pairs, soft clips, score ties, and phred 0
bases.

Deliberately broken copies of `markdup.sql` (6) and the recalibration SQL (8) check
that the fixtures catch mistakes; all are caught but one. Counting phred 0
bases in recalibration cannot change the output at the default minimum
quality: those bases keep their quality, and the per read group delta they
shift cancels out of ADAM's formula.

On HG00096 chr22 (300,000 reads) duplicate marking (10,489 duplicates)
and recalibration (129,300 reads) are identical to ADAM's; on 1,717,437
reads, see Performance.

## Performance

`benchmark.sh <reads.adam> <known SNPs VCF> <runs> <work dir>` times ADAM
and DuckDB on the same Parquet input, alternating run by run, and reports
the median and minimum of wall time and CPU time (user + sys). ADAM runs on
Spark `local[4]` with 4 GB; DuckDB runs with 4 threads and 4 GB
(`duckdb-4`) and unconstrained (`duckdb`, 18 cores here). The pipeline is
duplicate marking, recalibration and sort: one `transformAlignments` for
ADAM, three runs of `run.sh` for DuckDB.

**HG00096 chr22, 1000 Genomes phase 3 low coverage:** 1,717,437 reads, 3
read groups, 1,103,547 known sites; medians of 5 runs, seconds.

| Step | ADAM wall | ADAM CPU | `duckdb-4` wall | `duckdb-4` CPU | `duckdb` wall | `duckdb` CPU |
| --- | --- | --- | --- | --- | --- | --- |
| Read and write only | 16.6 | 47.2 | | | | |
| Duplicate marking | 29.1 | 107.0 | 5.0 | 16.1 | 3.6 | 22.2 |
| Recalibration | 67.0 | 267.4 | 48.2 | 153.5 | 26.5 | 188.3 |
| Sort | 20.7 | 71.0 | 1.6 | 4.4 | 0.8 | 4.8 |
| Pipeline | 88.3 | 334.3 | 53.5 | 172.1 | 31.1 | 215.8 |

Every ADAM step includes the 16.6 s that reading and writing alone take.
Recalibration here was measured before the per-batch rewrite and the
known-sites change below. With 4 threads DuckDB uses less CPU than
unconstrained: more threads finish sooner but each does less.

Each stage's output on this data matches ADAM's (4 duplicate reads differ
inside consistent ties). The pipelines' outputs differ in 3,129 bases of
2,737 reads, by up to 19: the 4 tied reads are duplicates in one output and
not the other, recalibration leaves duplicates out, and moving a few
observations shifts rare covariate keys. Recalibrating ADAM's own
duplicate-marked reads in DuckDB gives ADAM's pipeline output exactly. Two
ADAM runs can differ the same way when the shuffle breaks a tie
differently.

**HG00096 chr22, 1000 Genomes 30x high coverage (GRCh38):** 10,576,752 reads
of 150 bases, 12 read groups, 59,154 supplementary alignments, 1,066,557
known sites; medians of 3 runs, seconds.

| Step | ADAM wall | ADAM CPU | `duckdb-4` wall | `duckdb-4` CPU | `duckdb` wall | `duckdb` CPU |
| --- | --- | --- | --- | --- | --- | --- |
| Read and write only | 44.0 | 152.4 | | | | |
| Duplicate marking | 160.8 | 715.3 | 78.2 | 214.2 | 43.3 | 227.8 |
| Recalibration | 305.3 | 1147.9 | 1116.5 | 3976.4 | 287.3 | 2063.5 |
| Sort | 80.8 | 342.7 | 11.7 | 32.5 | 3.5 | 31.6 |
| Pipeline | 482.4 (8 GB) | 1841.8 (8 GB) | 1047.2 | 3404.4 | 287.4 | 2196.9 |

- DuckDB is ahead on duplicate marking and sort at either setting.
- Recalibration is about level on wall time when DuckDB has the whole
  machine, at 1.8 times ADAM's CPU, and well behind in 4 GB, where the
  per-read lists spill.
- ADAM's pipeline runs out of memory at 4 GB on this data; its row is at
  8 GB. DuckDB's completes within 4 GB, slowly.
- The DuckDB pipeline's recalibration took no longer than recalibration
  alone: duplicate marking writes smaller files, so `BQSR_PARTS` batches are
  smaller. Batch size matters and has not been tuned.

Duplicate marking and recalibration match ADAM on this data: no unexplained
mismatches in 10,576,752 reads, and every base of 8,969,336 recalibrated
reads identical. ADAM's own two runs disagree here too, on 37,696 reads
through score ties and 91 through the choice of a mate's alignment.

Getting this data: the 30x alignments are CRAMs, cut to chr22 with
`samtools view -b <cram URL> chr22`. Decoding needs
`REF_PATH=https://www.ebi.ac.uk/ena/cram/md5/%s`: htslib's default uses
`http://`, which EBI redirects and htslib does not follow. The known sites
are the chr22 phased panel, reduced to `CHROM POS ID REF ALT` under a
minimal header with a `##contig` line, because htsjdk, and so ADAM, rejects
the panel's header (an INFO line with `Type` before `Number`).

### What changed recalibration's speed and memory

Timing each statement (`.timer on` over the script) found where the time
went.

| Change | Effect |
| --- | --- |
| Covariates joined to the known sites and MD mismatches, instead of a correlated `EXISTS` per base; built from the CIGAR's bases instead of `range(readLength)` joined back; without a join to the full-width reads table for one column | 300k reads: the covariates statement from 7.6 s (16 s system time) to 1.1 s |
| `preserve_insertion_order = false` (the final `COPY` orders its rows) | 300k reads: 4.0 to 3.1 s |
| The dinucleotide as a two-character string instead of a struct | cheaper to group and join on |
| Known sites read with `read_csv` instead of splitting `read_text` | 1.1M sites: 6.1 s to under 0.3 s |
| Tried and dropped: each read's new qualities collected unordered and sorted within the read, instead of `string_agg(... ORDER BY baseIdx)` | no measurable change |

Together, 300k reads went from 7.0 s wall and 24.7 s CPU to 2.7 s and 12.6
s. Then the 30x data ran out of memory, in 4 GB and after spilling about 80
GB unconstrained, because per-base tables held about 1.6 billion rows.

| Change for 30x | Effect |
| --- | --- |
| Two passes over batches of input parts (`BQSR_PARTS`, 4 by default): count each batch, sum the counts into the table, recalibrate each batch | Finishes; memory is bounded by a batch. Counts add exactly, so the table is the one a single pass gives |
| Duplicate marking writes several files | A pipeline's recalibration has parts to batch |
| The covariate key as one integer, alone | No measurable CPU saving: grouping and joining were not the main cost |
| Each read's bases and qualities turned into integer lists once, instead of `substr` per base | Computing the keys: 66 to 9 s CPU on 92M bases |
| The apply pass leaves out the CIGAR walk, MD tags and known sites, since a key depends only on its read | Part of the saving below |
| Tried and dropped: per-base tables as views | 222 s, nearly single-threaded, because the planner cannot size the joins. Joining at one row per CIGAR operator, before expanding to bases, avoids them |

The list and apply changes took a 930,651-read sample from 186 to about 115
s CPU, and the full 30x recalibration from 463 s wall and 2,797 s CPU to 287
s and 2,064 s, with identical output. In 4 GB they cost time: 734 s before,
1,117 s after.

Possible next steps: tuning `BQSR_PARTS` by memory, and avoiding the
per-base regrouping that rebuilds each read's quality string, now the
largest cost in the apply pass.

### Measuring

- CPU time comes from `/usr/bin/time`. On macOS it loses the CPU time of
  scripts chained inside one `bash -c`, so the DuckDB pipeline is timed
  stage by stage and summed. ADAM's CPU through `adam-submit` matches
  calling `spark-submit` directly, within the wrapper's 0.5 s.
- ADAM will not overwrite an output directory and exits early, so the
  benchmark removes ADAM's output before each run.
- The machine these ran on is shared; each benchmark started once the
  1-minute load average was below 20, and alternating ADAM and DuckDB runs
  spreads what load remains over both.

Needs DuckDB 2.0 (lambdas are written `lambda x: ...`) with the `avro`
extension, which reads ADAM's `_readGroups.avro`.
