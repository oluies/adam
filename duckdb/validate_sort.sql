-- Checks a DuckDB sort against ADAM's. Equal keys may come out in any order,
-- so this checks that each output is in key order and that both hold the
-- same rows, not that the rows are in the same order.
--
-- Variables: expected (ADAM's sorted output), actual (DuckDB's).

CREATE TEMP MACRO sort_key(readMapped, referenceName, readName, start) AS
    {'n': CASE WHEN readMapped THEN referenceName ELSE '~~~' || readName END,
     'p': CASE WHEN readMapped THEN start ELSE 0 END};

CREATE TEMP TABLE expected AS
SELECT row_number() OVER (ORDER BY filename, file_row_number) AS i, * EXCLUDE (filename, file_row_number)
FROM read_parquet(getvariable('expected') || '/part-*.parquet', filename = true, file_row_number = true);

CREATE TEMP TABLE actual AS
SELECT row_number() OVER (ORDER BY filename, file_row_number) AS i, * EXCLUDE (filename, file_row_number)
FROM read_parquet(getvariable('actual') || '/part-*.parquet', filename = true, file_row_number = true);

SELECT
    'expected' AS output,
    count(*) AS rows,
    count(*) FILTER (WHERE k < lag_k) AS out_of_order
FROM (SELECT sort_key(readMapped, referenceName, readName, start) AS k,
             lag(sort_key(readMapped, referenceName, readName, start)) OVER (ORDER BY i) AS lag_k
      FROM expected)
UNION ALL
SELECT
    'actual',
    count(*),
    count(*) FILTER (WHERE k < lag_k)
FROM (SELECT sort_key(readMapped, referenceName, readName, start) AS k,
             lag(sort_key(readMapped, referenceName, readName, start)) OVER (ORDER BY i) AS lag_k
      FROM actual)
UNION ALL
SELECT 'only in expected', count(*), NULL
FROM (SELECT * EXCLUDE (i) FROM expected EXCEPT ALL SELECT * EXCLUDE (i) FROM actual)
UNION ALL
SELECT 'only in actual', count(*), NULL
FROM (SELECT * EXCLUDE (i) FROM actual EXCEPT ALL SELECT * EXCLUDE (i) FROM expected);
