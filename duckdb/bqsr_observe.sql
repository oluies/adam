-- Counts one batch's observations per covariate key; bqsr_table.sql sums
-- the batches. Run after bqsr_bases.sql.
--
-- Variables: sites (bqsr_sites.sql's output), output (Parquet file).

-- SnpTable.maskedSites, from bqsr_sites.sql
CREATE TEMP TABLE known_sites AS SELECT * FROM read_parquet(getvariable('sites'));

-- computeResiduesToInclude walks the CIGAR. One row per operator, with the
-- read bases, reference bases and MD tag bases (reference bases other than
-- N skips) before it.
CREATE TEMP TABLE cigar_ops AS
WITH ops AS (
    SELECT
        rid,
        unnest(range(len(ops))) AS opIndex,
        unnest(ops) AS op,
        unnest(lens)::BIGINT AS len
    FROM (
        SELECT
            rid,
            regexp_extract_all(cigar, '\d+([MIDNSHP=X])', 1) AS ops,
            regexp_extract_all(cigar, '(\d+)[MIDNSHP=X]', 1) AS lens
        FROM included
    )
)
SELECT
    *,
    sum(CASE WHEN op IN ('M', 'I', 'S', '=', 'X') THEN len ELSE 0 END) OVER w
        - CASE WHEN op IN ('M', 'I', 'S', '=', 'X') THEN len ELSE 0 END AS readBefore,
    sum(CASE WHEN op IN ('M', 'D', 'N', '=', 'X') THEN len ELSE 0 END) OVER w
        - CASE WHEN op IN ('M', 'D', 'N', '=', 'X') THEN len ELSE 0 END AS refBefore,
    sum(CASE WHEN op IN ('M', 'D', '=', 'X') THEN len ELSE 0 END) OVER w
        - CASE WHEN op IN ('M', 'D', '=', 'X') THEN len ELSE 0 END AS mdBefore
FROM ops
WINDOW w AS (PARTITION BY rid ORDER BY opIndex ROWS UNBOUNDED PRECEDING);

-- MdTag: the MD offsets of mismatched bases, and the MD tag's length; an
-- aligned M base outside every match range is a mismatch (!MdTag.isMatch)
CREATE TEMP TABLE md_tokens AS
WITH tokens AS (
    SELECT rid, unnest(range(len(toks))) AS i, unnest(toks) AS tok
    FROM (SELECT rid, regexp_extract_all(md, '\d+|\^[A-Z]+|[A-Z]') AS toks FROM included)
),
sized AS (
    SELECT
        *,
        CASE
            WHEN regexp_full_match(tok, '\d+') THEN tok::BIGINT
            WHEN starts_with(tok, '^') THEN length(tok) - 1
            ELSE 1
        END AS tokLen
    FROM tokens
)
SELECT
    rid,
    tok,
    sum(tokLen) OVER (PARTITION BY rid ORDER BY i ROWS UNBOUNDED PRECEDING) - tokLen AS mdStart,
    sum(tokLen) OVER (PARTITION BY rid) AS mdLength
FROM sized;

CREATE TEMP TABLE md_per_read AS
SELECT
    rid,
    list(mdStart) FILTER (WHERE regexp_full_match(tok, '[A-Z]')) AS mismatches,
    any_value(mdLength) AS mdLength
FROM md_tokens
GROUP BY rid;

-- The aligned operators (inserted and clipped bases are never included;
-- stepOverAlignedBase) with their reads. Joining here, a few rows per read,
-- leaves one join to do per base.
CREATE TEMP TABLE aligned_ops AS
SELECT o.op, o.len, o.readBefore, o.refBefore, o.mdBefore, i.*, m.mismatches, m.mdLength
FROM cigar_ops o
JOIN coded i USING (rid)
LEFT JOIN md_per_read m USING (rid)
WHERE o.op IN ('M', '=', 'X');

-- BaseQualityRecalibration.observed: totals and mismatches per covariate key
COPY (
    SELECT
        key,
        count(*) AS total,
        count(*) FILTER (WHERE isMismatch) AS mismatches
    FROM (
        SELECT
            b.keys[b.baseIdx + 1] AS key,
            -- isAlignedBaseMismatch
            CASE b.op
                WHEN 'X' THEN true
                WHEN 'M' THEN coalesce(list_contains(b.mismatches, b.mdOffset), false)
                    OR b.mdOffset >= coalesce(b.mdLength, 0)
                ELSE false
            END AS isMismatch
        FROM (
            SELECT
                *,
                (readBefore + k)::BIGINT AS baseIdx,
                start + (refBefore + k)::BIGINT AS pos,
                (mdBefore + k)::BIGINT AS mdOffset
            FROM (SELECT *, unnest(range(len)) AS k FROM aligned_ops)
            WHERE (readBefore + k)::BIGINT < readLength
        ) b
        LEFT JOIN known_sites s ON s.referenceName = b.referenceName AND s.pos = b.pos
        WHERE b.codes[b.baseIdx + 1] <> 4   -- not an N
          AND b.quals[b.baseIdx + 1] > 33   -- phred above 0
          AND s.pos IS NULL
    )
    GROUP BY key
) TO (getvariable('output')) (FORMAT parquet);
