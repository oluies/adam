-- Shared by bqsr_observe.sql and bqsr_apply.sql: one batch of parts, the
-- reads ADAM recalibrates, and the covariate key of a base, as ADAM's
-- BaseQualityRecalibration computes them (adam-core/.../ds/read/recalibration/).
-- Each step names the Scala it ports.
--
-- Variables: input (a list of part files), read_groups (_readGroups.avro).
-- Not ported: -sampling_fraction, and CIGAR P operators.

LOAD avro;

-- bqsr_apply.sql orders its output; nothing else needs input order kept
SET preserve_insertion_order = false;

CREATE TEMP VIEW reads AS
SELECT row_number() OVER (ORDER BY filename, file_row_number) AS rid, *
FROM read_parquet(getvariable('input'), filename = true, file_row_number = true);

-- ReadGroupDictionary.getIndex: the same number in every batch
CREATE TEMP TABLE read_groups AS
SELECT row_number() OVER (ORDER BY id) AS rg, id AS readGroupId
FROM read_avro(getvariable('read_groups'));

-- BaseQualityRecalibration.shouldIncludeRead
CREATE TEMP TABLE included AS
SELECT
    r.rid, coalesce(g.rg, 0) AS rg, r.referenceName, r.start, r.cigar, r.sequence, r.qualityScores,
    r.readNegativeStrand, r.readInFragment,
    upper(r.mismatchingPositions) AS md,
    length(r.sequence) AS readLength
FROM reads r
LEFT JOIN read_groups g USING (readGroupId)
WHERE r.readMapped AND r.primaryAlignment AND NOT r.duplicateRead
  AND r.qualityScores IS NOT NULL
  AND r.mappingQuality > 0
  AND r.cigar IS NOT NULL AND r.cigar <> '*'
  AND NOT r.failedVendorQualityChecks
  AND length(r.sequence) > 0;

-- A CovariateKey packed into one integer, cheaper to group and join on than
-- its parts: read group from bit 32, the quality's ASCII code in bits 25-31,
-- the cycle (offset to be positive) in bits 5-24, the dinucleotide in 0-4.
CREATE TEMP MACRO covariate_key(rg, q, cycle, dinuc) AS
    (rg::BIGINT << 32) | (q::BIGINT << 25) | ((cycle::BIGINT + 524288) << 5) | dinuc::BIGINT;

-- DinucCovariate: A, C, G, T as 0-3 so a complement is 3 - code; N and
-- anything else as 4
CREATE TEMP MACRO base_code(b) AS
    CASE b WHEN 'A' THEN 0 WHEN 'C' THEN 1 WHEN 'G' THEN 2 WHEN 'T' THEN 3 ELSE 4 END;
CREATE TEMP MACRO complement_code(c) AS CASE WHEN c = 4 THEN 4 ELSE 3 - c END;
CREATE TEMP MACRO dinuc_code(previous, current) AS
    CASE WHEN previous IS NULL OR previous = 4 OR current = 4 THEN 24 ELSE previous * 5 + current END;

-- A read's bases and qualities as integers, computed once per read: a
-- string operation on every base costs several times the rest of the key.
CREATE TEMP MACRO base_codes(sequence) AS list_transform(string_split(sequence, ''), lambda b: base_code(b));
CREATE TEMP MACRO quality_codes(qualities) AS list_transform(string_split(qualities, ''), lambda c: ascii(c));

-- CovariateSpace: a read's covariate keys, one per base. CycleCovariate
-- counts from the read's five prime end, negated for the second read of a
-- pair; DinucCovariate pairs each base with the one before it in sequencing
-- order (the complement of the next base on the reverse strand).
CREATE TEMP MACRO read_keys(rg, codes, quals, negative, inFragment, readLength) AS
    list_transform(range(readLength), lambda idx: covariate_key(
        rg,
        quals[idx + 1],
        CASE
            WHEN NOT negative AND inFragment <> 0 THEN -(idx + 1)
            WHEN NOT negative THEN idx + 1
            WHEN inFragment <> 0 THEN -(readLength - idx)
            ELSE readLength - idx
        END,
        CASE
            WHEN negative THEN dinuc_code(
                CASE WHEN idx < readLength - 1 THEN complement_code(codes[idx + 2]) END,
                complement_code(codes[idx + 1]))
            ELSE dinuc_code(CASE WHEN idx > 0 THEN codes[idx] END, codes[idx + 1])
        END));

CREATE TEMP VIEW coded AS
SELECT *, read_keys(rg, codes, quals, readNegativeStrand, readInFragment, readLength) AS keys
FROM (SELECT *, base_codes(sequence) AS codes, quality_codes(qualityScores) AS quals FROM included);
