-- Sums every part's observation counts and computes the recalibration
-- table. Counts add exactly, so the table is the one a single pass over all
-- reads gives.
--
-- Variables: input (directory of bqsr_observe.sql outputs), output
-- (Parquet file).

-- BaseQualityRecalibration.observed: totals and mismatches per covariate
-- key, unpacked (bqsr_bases.sql's covariate_key)
CREATE TEMP TABLE observed AS
SELECT
    key,
    key >> 32 AS rg,
    (key >> 25) & 127 AS q,
    ((key >> 5) & 1048575) - 524288 AS cycle,
    key & 31 AS dinuc,
    sum(total) AS total,
    sum(mismatches) AS mismatches
FROM read_parquet(getvariable('input') || '/*.parquet')
GROUP BY key;

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
        rg,
        ln(bayesian_error(sum(mismatches), sum(total)))
            - ln(sum(error_probability(q - 33) * total) / sum(total)) AS globalDelta
    FROM observed
    GROUP BY ALL
),
quality AS (
    SELECT rg, q, ln(bayesian_error(sum(mismatches), sum(total))) AS logP
    FROM observed GROUP BY ALL
),
by_cycle AS (
    SELECT rg, q, cycle, ln(bayesian_error(sum(mismatches), sum(total))) AS logP
    FROM observed GROUP BY ALL
),
by_dinuc AS (
    SELECT rg, q, dinuc, ln(bayesian_error(sum(mismatches), sum(total))) AS logP
    FROM observed GROUP BY ALL
),
deltas AS (
    SELECT
        o.key,
        ln(error_probability(o.q - 33)) AS residueLogP,
        g.globalDelta,
        qt.logP - (ln(error_probability(o.q - 33)) + g.globalDelta) AS qualityDelta,
        c.logP AS cycleLogP,
        d.logP AS dinucLogP
    FROM observed o
    JOIN global g USING (rg)
    JOIN quality qt USING (rg, q)
    JOIN by_cycle c USING (rg, q, cycle)
    JOIN by_dinuc d USING (rg, q, dinuc)
),
corrected AS (
    SELECT
        *,
        residueLogP + globalDelta + qualityDelta
            + ((cycleLogP - (residueLogP + globalDelta + qualityDelta))
               + (dinucLogP - (residueLogP + globalDelta + qualityDelta))) AS correctedLogP
    FROM deltas
)
-- qualityFromLogP: bounded to [phred 50, phred 0], then Math.round; the
-- new quality as an ASCII code
SELECT
    key,
    (floor(-10.0 * log10(exp(least(0.0, greatest(ln(error_probability(50)), correctedLogP))))
        + 0.5))::INTEGER + 33 AS newQ
FROM corrected;

COPY recalibration TO (getvariable('output')) (FORMAT parquet);
