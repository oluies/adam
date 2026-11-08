-- Marks duplicate reads as ADAM's MarkDuplicates does
-- (adam-core/.../ds/read/MarkDuplicates.scala, SingleReadBucket.scala,
-- ReferencePositionPair.scala). Each step names the Scala it ports.
--
-- Variables: input, output (ADAM alignment directories).
-- Also writes output/_duckdb_ties.parquet: the buckets whose mark depends on
-- an order ADAM leaves to its shuffle. Either several buckets tie for the
-- best score in a group (each listed with that group), or a mate has more
-- than one alignment ADAM could take its position from (a primary and a
-- supplementary alignment), which can move the bucket to another left
-- position; every bucket at the left positions it could take is listed,
-- with mateChoice set.

LOAD avro;

CREATE TEMP TABLE reads AS
SELECT row_number() OVER (ORDER BY filename, file_row_number) AS rid, *
FROM read_parquet(getvariable('input') || '/part-*.parquet', filename = true, file_row_number = true);

-- library by read group, from the read group dictionary
CREATE TEMP TABLE libraries AS
SELECT id AS readGroupId, library
FROM read_avro(getvariable('input') || '/_readGroups.avro');

-- RichAlignment.isClipped: soft and hard clips
CREATE TEMP MACRO clipped_bases(clips) AS
    coalesce(list_sum(list_transform(regexp_extract_all(clips, '(\d+)[SH]', 1), lambda n: n::BIGINT)), 0);

-- SingleReadBucket: primary mapped, secondary mapped and unmapped reads.
-- ReferencePositionPair.getPos: a mapped read's five prime position and
-- strand (RichAlignment.fivePrimeReferencePosition, clips counted back in);
-- an unmapped read's sequence at 0.
-- MarkDuplicates.score: base qualities of 15 or more, summed over primary,
-- non-supplementary reads.
CREATE TEMP TABLE positioned AS
SELECT
    rid,
    readGroupId,
    readName,
    readInFragment,
    CASE
        WHEN readMapped AND primaryAlignment THEN 'primary'
        WHEN readMapped THEN 'secondary'
        ELSE 'unmapped'
    END AS kind,
    CASE
        WHEN NOT readMapped THEN {'n': sequence, 'p': 0::BIGINT, 's': 'INDEPENDENT'}
        WHEN readNegativeStrand THEN
            {'n': referenceName,
             'p': greatest(0, "end" + clipped_bases(regexp_extract(cigar, '(?:\d+[SH])+$'))),
             's': 'REVERSE'}
        ELSE
            {'n': referenceName,
             'p': greatest(0, start - clipped_bases(regexp_extract(cigar, '^(?:\d+[SH])+'))),
             's': 'FORWARD'}
    END AS pos,
    CASE
        WHEN readMapped AND primaryAlignment AND NOT supplementaryAlignment THEN
            coalesce(list_sum(list_filter(
                list_transform(string_split(qualityScores, ''), lambda c: ascii(c) - 33),
                lambda q: q >= 15)), 0)
        ELSE 0
    END AS score
FROM reads;

-- ReferencePositionPair.apply: read 1 and read 2 are the first primary
-- mapped, else unmapped, read with readInFragment 0 and 1. Without either,
-- read 1 is the first primary mapped or unmapped read. "First" is input
-- order; a bucket with more than one candidate for a mate is flagged, since
-- ADAM's choice there depends on its iteration order.
CREATE TEMP TABLE buckets AS
WITH candidates AS (
    SELECT *, (kind <> 'primary')::BIGINT * 4294967296 + rid AS candidateOrder
    FROM positioned
    WHERE kind IN ('primary', 'unmapped')
),
per_bucket AS (
    SELECT
        readGroupId,
        readName,
        arg_min(pos, candidateOrder) FILTER (WHERE readInFragment = 0) AS first,
        arg_min(pos, candidateOrder) FILTER (WHERE readInFragment = 1) AS second,
        arg_min(pos, candidateOrder) AS anyRead,
        count(*) FILTER (WHERE readInFragment = 0) > 1
            OR count(*) FILTER (WHERE readInFragment = 1) > 1 AS ambiguousMates
    FROM candidates
    GROUP BY ALL
)
SELECT
    s.readGroupId,
    s.readName,
    s.score,
    CASE WHEN b.first IS NULL AND b.second IS NULL THEN b.anyRead ELSE b.first END AS read1,
    CASE WHEN b.first IS NULL AND b.second IS NULL THEN NULL ELSE b.second END AS read2,
    coalesce(b.ambiguousMates, false) AS ambiguousMates
FROM (SELECT readGroupId, readName, sum(score) AS score FROM positioned GROUP BY ALL) s
LEFT JOIN per_bucket b
    ON b.readGroupId IS NOT DISTINCT FROM s.readGroupId AND b.readName = s.readName;

-- MarkDuplicates.markBuckets: the left position prefers a forward read,
-- the right position a reverse read (positionForStrand).
CREATE TEMP MACRO left_position(read1, read2) AS
    CASE WHEN read1.s = 'FORWARD' THEN read1 WHEN read2.s = 'FORWARD' THEN read2 ELSE read1 END;

CREATE TEMP TABLE keyed AS
SELECT
    b.*,
    l.library,
    left_position(b.read1, b.read2) AS leftPos,
    CASE
        WHEN b.read2.s = 'REVERSE' THEN b.read2
        WHEN b.read1.s = 'REVERSE' THEN b.read1
        ELSE b.read2
    END AS rightPos
FROM buckets b
LEFT JOIN libraries l USING (readGroupId);

-- The left positions a bucket with an ambiguous mate could take: one per
-- choice of each mate's alignment
CREATE TEMP TABLE mate_choice_lefts AS
WITH ambiguous AS (
    SELECT readGroupId, readName, library FROM keyed WHERE ambiguousMates
),
mates AS (
    SELECT a.readGroupId, a.readName, a.library, p.readInFragment, p.pos
    FROM ambiguous a
    JOIN positioned p
        ON p.readGroupId IS NOT DISTINCT FROM a.readGroupId AND p.readName = a.readName
    WHERE p.kind IN ('primary', 'unmapped') AND p.readInFragment IN (0, 1)
),
choices AS (
    SELECT a.library, f.pos AS read1, s.pos AS read2
    FROM ambiguous a
    LEFT JOIN mates f
        ON f.readGroupId IS NOT DISTINCT FROM a.readGroupId AND f.readName = a.readName AND f.readInFragment = 0
    LEFT JOIN mates s
        ON s.readGroupId IS NOT DISTINCT FROM a.readGroupId AND s.readName = a.readName AND s.readInFragment = 1
)
SELECT DISTINCT library, left_position(read1, read2) AS leftPos FROM choices;

-- Group by library and left position, then by right position. A group is
-- scored unless it holds fragments (no right position) beside pairs; in a
-- scored group the best bucket keeps its primary reads, and every other
-- bucket, and every secondary read, is a duplicate.
CREATE TEMP TABLE decided AS
WITH ranked AS (
    SELECT
        *,
        row_number() OVER right_group AS scoreRank,
        max(score) OVER right_group AS topScore,
        dense_rank() OVER (PARTITION BY library, leftPos ORDER BY rightPos) AS rightRank
    FROM keyed
    WINDOW right_group AS (PARTITION BY library, leftPos, rightPos ORDER BY score DESC, readName)
),
counted AS (
    SELECT
        *,
        max(rightRank) OVER (PARTITION BY library, leftPos) AS groupCount,
        count(*) FILTER (WHERE score = topScore) OVER (PARTITION BY library, leftPos, rightPos) AS topCount
    FROM ranked
)
SELECT
    readGroupId,
    readName,
    leftPos IS NOT NULL AS hasLeft,
    rightPos IS NOT NULL OR groupCount = 1 AS scored,
    (rightPos IS NOT NULL OR groupCount = 1) AND scoreRank = 1 AS kept,
    leftPos IS NOT NULL AND (rightPos IS NOT NULL OR groupCount = 1)
        AND topCount > 1 AND score = topScore AS tied,
    hash(library, leftPos, rightPos) AS tieGroup,
    ambiguousMates OR EXISTS (
        SELECT 1 FROM mate_choice_lefts m
        WHERE m.library IS NOT DISTINCT FROM counted.library AND m.leftPos = counted.leftPos) AS mateChoice
FROM counted;

CREATE TEMP TABLE marked AS
SELECT
    r.rid,
    CASE
        WHEN p.kind = 'unmapped' THEN false
        WHEN NOT d.hasLeft THEN false
        WHEN p.kind = 'primary' THEN NOT d.kept
        ELSE true
    END AS duplicateRead
FROM reads r
JOIN positioned p USING (rid)
JOIN decided d
    ON d.readGroupId IS NOT DISTINCT FROM p.readGroupId AND d.readName = p.readName;

-- several files, so recalibration can work through them a few at a time
COPY (
    SELECT r.* EXCLUDE (rid, filename, file_row_number) REPLACE (m.duplicateRead AS duplicateRead)
    FROM reads r
    JOIN marked m USING (rid)
    ORDER BY r.rid
) TO (getvariable('output')) (FORMAT parquet, FILE_SIZE_BYTES '32MB', FILENAME_PATTERN 'part-r-{i}', OVERWRITE_OR_IGNORE);

COPY (
    SELECT readGroupId, readName, tieGroup, mateChoice FROM decided WHERE tied OR mateChoice
) TO (getvariable('output') || '/_duckdb_ties.parquet') (FORMAT parquet);
