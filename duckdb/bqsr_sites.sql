-- The known sites recalibration leaves out, read once from the VCF.
--
-- Variables: known_snps (a VCF, or /dev/null), sites (output Parquet file).

-- SnpTable.maskedSites: the start of every known variant (VCF POS - 1)
-- (read_csv parses in parallel; VCF header lines are comments)
COPY (
SELECT DISTINCT chrom AS referenceName, pos - 1 AS pos
FROM read_csv(getvariable('known_snps'), delim = '\t', header = false, comment = '#',
              quote = '', escape = '', null_padding = true, auto_detect = false,
              columns = {'chrom': 'VARCHAR', 'pos': 'BIGINT', 'id': 'VARCHAR', 'ref': 'VARCHAR',
                         'alt': 'VARCHAR', 'qual': 'VARCHAR', 'filter': 'VARCHAR', 'info': 'VARCHAR'})
) TO (getvariable('sites')) (FORMAT parquet);
