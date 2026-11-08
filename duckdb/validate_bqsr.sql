-- Checks DuckDB's recalibrated qualities against ADAM's, base by base.
--
-- Variables: expected (ADAM's recalibrated output), actual (DuckDB's).

CREATE TEMP MACRO read_key(r) AS
    {'readGroupId': r.readGroupId, 'readName': r.readName, 'readInFragment': r.readInFragment,
     'readMapped': r.readMapped, 'primaryAlignment': r.primaryAlignment,
     'supplementaryAlignment': r.supplementaryAlignment, 'referenceName': r.referenceName,
     'start': r.start, 'cigar': r.cigar, 'readNegativeStrand': r.readNegativeStrand};

CREATE TEMP TABLE pairs AS
SELECT
    e.qualityScores AS expectedQuals, a.qualityScores AS actualQuals,
    e.originalQualityScores AS expectedOriginal, a.originalQualityScores AS actualOriginal,
    e.k IS NULL OR a.k IS NULL AS unmatched
FROM (SELECT read_key(r) AS k, * FROM read_parquet(getvariable('expected') || '/part-*.parquet') r) e
FULL JOIN (SELECT read_key(r) AS k, * FROM read_parquet(getvariable('actual') || '/part-*.parquet') r) a
    ON e.k = a.k;

-- per base, the difference in phred score where the qualities differ
CREATE TEMP TABLE base_diffs AS
SELECT ascii(substr(actualQuals, i + 1, 1)) - ascii(substr(expectedQuals, i + 1, 1)) AS diff
FROM (SELECT *, unnest(range(length(expectedQuals))) AS i FROM pairs
      WHERE NOT unmatched AND expectedQuals IS DISTINCT FROM actualQuals AND length(expectedQuals) = length(actualQuals))
WHERE substr(actualQuals, i + 1, 1) <> substr(expectedQuals, i + 1, 1);

SELECT 'reads' AS check, count(*) AS n FROM pairs
UNION ALL
SELECT 'reads only in one output', count(*) FILTER (WHERE unmatched) FROM pairs
UNION ALL
SELECT 'reads recalibrated by ADAM', count(*) FILTER (WHERE expectedOriginal IS NOT NULL) FROM pairs
UNION ALL
SELECT 'original qualities differ', count(*) FILTER (WHERE NOT unmatched AND expectedOriginal IS DISTINCT FROM actualOriginal) FROM pairs
UNION ALL
SELECT 'reads with different qualities', count(*) FILTER (WHERE NOT unmatched AND expectedQuals IS DISTINCT FROM actualQuals) FROM pairs
UNION ALL
SELECT 'bases with different qualities', count(*) FROM base_diffs
UNION ALL
SELECT 'largest difference in a base', coalesce(max(abs(diff)), 0) FROM base_diffs;
