-- Sorts alignments as ADAM's sortByReferencePosition does: mapped reads by
-- reference name (string order) and start; unmapped reads after them, by
-- read name ("~~~" sorts after any reference name). Reads with equal keys
-- come out in no particular order, in ADAM too.
--
-- Variables: input, output (ADAM alignment directories).

COPY (
    SELECT *
    FROM read_parquet(getvariable('input') || '/part-*.parquet')
    ORDER BY
        CASE WHEN readMapped THEN referenceName ELSE '~~~' || readName END,
        CASE WHEN readMapped THEN start ELSE 0 END
) TO (getvariable('output') || '/part-r-00000.parquet') (FORMAT parquet);
