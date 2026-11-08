-- Applies the recalibration table to one batch's reads. Run after
-- bqsr_bases.sql. A base's key depends only on its read, so this pass needs
-- no CIGAR walk.
--
-- Variables: recalibration_table (bqsr_table.sql's output),
-- min_acceptable_quality, output (Parquet file).

-- Recalibrator: bases at or above the minimum acceptable quality take the
-- table's quality; keys never observed keep theirs
CREATE TEMP TABLE new_quals AS
SELECT
    b.rid,
    string_agg(
        chr(CASE
            WHEN (b.key >> 25) & 127 >= getvariable('min_acceptable_quality') + 33
                THEN coalesce(t.newQ, ((b.key >> 25) & 127)::INTEGER)
            ELSE ((b.key >> 25) & 127)::INTEGER
        END), '' ORDER BY b.idx) AS qualityScores
FROM (
    SELECT rid, unnest(range(readLength)) AS idx, unnest(keys) AS key FROM coded
) b
LEFT JOIN read_parquet(getvariable('recalibration_table')) t ON t.key = b.key
GROUP BY b.rid;

COPY (
    SELECT r.* EXCLUDE (rid, filename, file_row_number) REPLACE (
        coalesce(n.qualityScores, r.qualityScores) AS qualityScores,
        CASE WHEN n.rid IS NULL THEN r.originalQualityScores ELSE r.qualityScores END AS originalQualityScores)
    FROM reads r
    LEFT JOIN new_quals n USING (rid)
    ORDER BY r.rid
) TO (getvariable('output')) (FORMAT parquet);
