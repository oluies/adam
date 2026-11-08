-- Checks DuckDB's duplicate marks against ADAM's, read by read. A mismatch
-- in a bucket listed in actual/_duckdb_ties.parquet depends on an order ADAM
-- leaves to its shuffle: a score tie, explained only when ADAM and DuckDB
-- keep the same number of reads in that tie group (so DuckDB's own tie list
-- cannot excuse a grouping bug), or the choice of a mate's alignment, which
-- can regroup every bucket at the left positions it reaches. Any other
-- mismatch is a bug.
--
-- Variables: expected (ADAM's marked output), actual (DuckDB's).

CREATE TEMP MACRO read_key(r) AS
    {'readGroupId': r.readGroupId, 'readName': r.readName, 'readInFragment': r.readInFragment,
     'readMapped': r.readMapped, 'primaryAlignment': r.primaryAlignment,
     'supplementaryAlignment': r.supplementaryAlignment, 'referenceName': r.referenceName,
     'start': r.start, 'cigar': r.cigar, 'readNegativeStrand': r.readNegativeStrand};

CREATE TEMP TABLE expected AS
SELECT read_key(r) AS k, r.readGroupId, r.readName, r.primaryAlignment, r.duplicateRead
FROM read_parquet(getvariable('expected') || '/part-*.parquet') r;

CREATE TEMP TABLE actual AS
SELECT read_key(r) AS k, r.duplicateRead
FROM read_parquet(getvariable('actual') || '/part-*.parquet') r;

CREATE TEMP TABLE listed AS
SELECT DISTINCT readGroupId, readName, tieGroup, mateChoice
FROM read_parquet(getvariable('actual') || '/_duckdb_ties.parquet');

CREATE TEMP TABLE tied_buckets AS
SELECT readGroupId, readName, tieGroup FROM listed WHERE NOT mateChoice;

CREATE TEMP TABLE mate_choice AS
SELECT DISTINCT readGroupId, readName FROM listed WHERE mateChoice;

-- a tie group is consistent when both outputs keep as many primary reads
CREATE TEMP TABLE consistent_groups AS
SELECT t.tieGroup
FROM tied_buckets t
JOIN expected e ON e.readGroupId IS NOT DISTINCT FROM t.readGroupId AND e.readName = t.readName
JOIN actual a ON a.k = e.k
WHERE e.primaryAlignment
GROUP BY t.tieGroup
HAVING count(*) FILTER (WHERE NOT e.duplicateRead) = count(*) FILTER (WHERE NOT a.duplicateRead);

CREATE TEMP TABLE ties AS
SELECT readGroupId, readName FROM tied_buckets WHERE tieGroup IN (SELECT tieGroup FROM consistent_groups);

CREATE TEMP TABLE mismatches AS
SELECT
    e.readGroupId, e.readName,
    EXISTS (SELECT 1 FROM mate_choice m WHERE m.readGroupId IS NOT DISTINCT FROM e.readGroupId AND m.readName = e.readName) AS byMateChoice,
    EXISTS (SELECT 1 FROM ties t WHERE t.readGroupId IS NOT DISTINCT FROM e.readGroupId AND t.readName = e.readName) AS byTie
FROM expected e JOIN actual a ON e.k = a.k
WHERE e.duplicateRead IS DISTINCT FROM a.duplicateRead;

-- the key must identify a read, or the comparison below is not read by read
SELECT 'duplicate keys in expected' AS check, count(*) AS n
FROM (SELECT k FROM expected GROUP BY k HAVING count(*) > 1)
UNION ALL
SELECT 'reads only in one output', count(*)
FROM expected e FULL JOIN actual a ON e.k = a.k
WHERE e.k IS NULL OR a.k IS NULL
UNION ALL
SELECT 'reads', count(*) FROM expected
UNION ALL
SELECT 'duplicates in expected', count(*) FILTER (WHERE duplicateRead) FROM expected
UNION ALL
SELECT 'duplicates in actual', count(*) FILTER (WHERE duplicateRead) FROM actual
UNION ALL
SELECT 'mismatches explained by ties', count(*) FILTER (WHERE byTie AND NOT byMateChoice) FROM mismatches
UNION ALL
SELECT 'mismatches explained by mate choice', count(*) FILTER (WHERE byMateChoice) FROM mismatches
UNION ALL
SELECT 'mismatches not explained', count(*) FILTER (WHERE NOT byTie AND NOT byMateChoice) FROM mismatches
UNION ALL
SELECT 'buckets in mate choice', count(*) FROM mate_choice
UNION ALL
SELECT 'buckets in consistent ties', count(*) FROM ties
UNION ALL
SELECT 'tie groups kept differently', count(DISTINCT tieGroup) - (SELECT count(*) FROM consistent_groups)
FROM tied_buckets;
