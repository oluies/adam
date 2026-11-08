-- Recalibrates base quality scores as ADAM's BaseQualityRecalibration does
-- (adam-core/.../ds/read/recalibration/). Each step names the Scala it ports.
--
-- Variables: input, output (ADAM alignment directories); known_snps (a VCF
-- of sites to leave out, or /dev/null); min_acceptable_quality (ADAM's
-- -min_acceptable_quality, 5 by default).
-- Not ported: -sampling_fraction, and CIGAR P operators.

CREATE TEMP TABLE reads AS
SELECT row_number() OVER (ORDER BY filename, file_row_number) AS rid, *
FROM read_parquet(getvariable('input') || '/part-*.parquet', filename = true, file_row_number = true);

-- BaseQualityRecalibration.shouldIncludeRead
CREATE TEMP TABLE included AS
SELECT
    rid, readGroupId, start, cigar, sequence, qualityScores, readNegativeStrand, readInFragment,
    upper(mismatchingPositions) AS md,
    length(sequence) AS readLength
FROM reads
WHERE readMapped AND primaryAlignment AND NOT duplicateRead
  AND qualityScores IS NOT NULL
  AND mappingQuality > 0
  AND cigar IS NOT NULL AND cigar <> '*'
  AND NOT failedVendorQualityChecks
  AND length(sequence) > 0;

-- SnpTable.maskedSites: the start of every known variant (VCF POS - 1)
CREATE TEMP TABLE known_sites AS
SELECT DISTINCT split_part(line, chr(9), 1) AS referenceName, split_part(line, chr(9), 2)::BIGINT - 1 AS pos
FROM (SELECT unnest(string_split(content, chr(10))) AS line FROM read_text(getvariable('known_snps')))
WHERE line <> '' AND NOT starts_with(line, '#');

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

-- one row per read base consumed by an operator; aligned bases (M, =, X)
-- also get their reference position and offset into the MD tag
CREATE TEMP TABLE cigar_bases AS
SELECT
    rid,
    op,
    readBefore + k AS baseIdx,
    CASE WHEN op IN ('M', '=', 'X') THEN refBefore + k END AS refOffset,
    CASE WHEN op IN ('M', '=', 'X') THEN mdBefore + k END AS mdOffset
FROM (
    SELECT *, unnest(range(len)) AS k
    FROM cigar_ops
    WHERE op IN ('M', 'I', 'S', '=', 'X')
);

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

CREATE TEMP TABLE md_mismatches AS
SELECT DISTINCT rid, mdStart AS mdOffset FROM md_tokens WHERE regexp_full_match(tok, '[A-Z]');

CREATE TEMP TABLE md_lengths AS
SELECT DISTINCT rid, mdLength FROM md_tokens;

-- DinucCovariate: the complement of a base, N for anything but ACGT
CREATE TEMP MACRO complement(b) AS
    CASE b WHEN 'A' THEN 'T' WHEN 'T' THEN 'A' WHEN 'C' THEN 'G' WHEN 'G' THEN 'C' ELSE 'N' END;

CREATE TEMP MACRO dinuc(previous, current) AS
    CASE
        WHEN previous IS NULL OR previous = 'N' OR current = 'N' THEN {'p': 'N', 'c': 'N'}
        ELSE {'p': previous, 'c': current}
    END;

-- CovariateSpace: one covariate key per read base. CycleCovariate counts
-- from the read's five prime end, negated for the second read of a pair;
-- DinucCovariate pairs each base with the one before it in sequencing
-- order (the complement of the next base on the reverse strand).
CREATE TEMP TABLE covariates AS
WITH per_base AS (
    SELECT
        i.rid,
        i.readGroupId,
        i.readLength,
        i.readNegativeStrand,
        i.readInFragment,
        i.start,
        idx AS baseIdx,
        substr(i.sequence, idx + 1, 1) AS base,
        substr(i.qualityScores, idx + 1, 1) AS q,
        CASE WHEN idx > 0 THEN substr(i.sequence, idx, 1) END AS before,
        CASE WHEN idx < i.readLength - 1 THEN substr(i.sequence, idx + 2, 1) END AS after
    FROM (SELECT *, unnest(range(readLength)) AS idx FROM included) i
)
SELECT
    b.rid,
    b.baseIdx,
    b.readGroupId,
    b.q,
    CASE
        WHEN NOT b.readNegativeStrand AND b.readInFragment <> 0 THEN -(b.baseIdx + 1)
        WHEN NOT b.readNegativeStrand THEN b.baseIdx + 1
        WHEN b.readInFragment <> 0 THEN -(b.readLength - b.baseIdx)
        ELSE b.readLength - b.baseIdx
    END AS cycle,
    CASE
        WHEN b.readNegativeStrand THEN dinuc(complement(b.after), complement(b.base))
        ELSE dinuc(b.before, b.base)
    END AS dinuc,
    -- stepOverAlignedBase; inserted and clipped bases are never included
    c.op IN ('M', '=', 'X')
        AND b.base <> 'N'
        AND b.q > '!'
        AND NOT EXISTS (
            SELECT 1 FROM known_sites s
            WHERE s.referenceName = r.referenceName AND s.pos = b.start + c.refOffset) AS shouldInclude,
    -- isAlignedBaseMismatch
    CASE c.op
        WHEN 'X' THEN true
        WHEN 'M' THEN EXISTS (SELECT 1 FROM md_mismatches m WHERE m.rid = b.rid AND m.mdOffset = c.mdOffset)
            OR c.mdOffset >= coalesce(l.mdLength, 0)
        ELSE false
    END AS isMismatch
FROM per_base b
JOIN reads r USING (rid)
LEFT JOIN cigar_bases c ON c.rid = b.rid AND c.baseIdx = b.baseIdx
LEFT JOIN md_lengths l ON l.rid = b.rid;

-- BaseQualityRecalibration.observed: totals and mismatches per covariate key
CREATE TEMP TABLE observed AS
SELECT
    readGroupId, q, cycle, dinuc,
    count(*) AS total,
    count(*) FILTER (WHERE isMismatch) AS mismatches
FROM covariates
WHERE shouldInclude
GROUP BY ALL;

-- PhredUtils.phredToErrorProbability and Observation.bayesianErrorProbability
CREATE TEMP MACRO error_probability(phred) AS pow(10.0, -greatest(0, least(255, phred)) / 10.0);
CREATE TEMP MACRO bayesian_error(mismatches, total) AS (1.0 + mismatches) / (2.0 + total);

-- RecalibrationTable: per read group, a global delta; per reported quality,
-- a quality delta; per cycle and per dinucleotide within a quality, extras
-- deltas, all in log error probability. The sums keep ADAM's order of
-- operations.
CREATE TEMP TABLE recalibration AS
WITH global AS (
    SELECT
        readGroupId,
        ln(bayesian_error(sum(mismatches), sum(total)))
            - ln(sum(error_probability(ascii(q) - 33) * total) / sum(total)) AS globalDelta
    FROM observed
    GROUP BY ALL
),
quality AS (
    SELECT readGroupId, q, ln(bayesian_error(sum(mismatches), sum(total))) AS logP
    FROM observed GROUP BY ALL
),
by_cycle AS (
    SELECT readGroupId, q, cycle, ln(bayesian_error(sum(mismatches), sum(total))) AS logP
    FROM observed GROUP BY ALL
),
by_dinuc AS (
    SELECT readGroupId, q, dinuc, ln(bayesian_error(sum(mismatches), sum(total))) AS logP
    FROM observed GROUP BY ALL
),
deltas AS (
    SELECT
        o.readGroupId, o.q, o.cycle, o.dinuc,
        ln(error_probability(ascii(o.q) - 33)) AS residueLogP,
        g.globalDelta,
        qt.logP - (ln(error_probability(ascii(o.q) - 33)) + g.globalDelta) AS qualityDelta,
        c.logP AS cycleLogP,
        d.logP AS dinucLogP
    FROM observed o
    JOIN global g USING (readGroupId)
    JOIN quality qt USING (readGroupId, q)
    JOIN by_cycle c USING (readGroupId, q, cycle)
    JOIN by_dinuc d USING (readGroupId, q, dinuc)
),
corrected AS (
    SELECT
        *,
        residueLogP + globalDelta + qualityDelta
            + ((cycleLogP - (residueLogP + globalDelta + qualityDelta))
               + (dinucLogP - (residueLogP + globalDelta + qualityDelta))) AS correctedLogP
    FROM deltas
)
-- qualityFromLogP: bounded to [phred 50, phred 0], then Math.round
SELECT
    readGroupId, q, cycle, dinuc,
    chr((floor(-10.0 * log10(exp(least(0.0, greatest(ln(error_probability(50)), correctedLogP))))
        + 0.5))::INTEGER + 33) AS newQ
FROM corrected;

-- Recalibrator: bases at or above the minimum acceptable quality take the
-- table's quality; keys never observed keep theirs
CREATE TEMP TABLE new_quals AS
SELECT
    c.rid,
    string_agg(
        CASE
            WHEN ascii(c.q) >= getvariable('min_acceptable_quality') + 33 THEN coalesce(t.newQ, c.q)
            ELSE c.q
        END, '' ORDER BY c.baseIdx) AS qualityScores
FROM covariates c
LEFT JOIN recalibration t
    ON t.readGroupId IS NOT DISTINCT FROM c.readGroupId AND t.q = c.q
   AND t.cycle = c.cycle AND t.dinuc = c.dinuc
GROUP BY c.rid;

COPY (
    SELECT r.* EXCLUDE (rid, filename, file_row_number) REPLACE (
        coalesce(n.qualityScores, r.qualityScores) AS qualityScores,
        CASE WHEN n.rid IS NULL THEN r.originalQualityScores ELSE r.qualityScores END AS originalQualityScores)
    FROM reads r
    LEFT JOIN new_quals n USING (rid)
    ORDER BY r.rid
) TO (getvariable('output') || '/part-r-00000.parquet') (FORMAT parquet);
