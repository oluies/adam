#!/usr/bin/env python3
"""
Interleave two paired FASTQ files into one: each read from the /1 file is
followed by its mate from the /2 file.

Usage: fastq-interleaver <fastq_1> <fastq_2> > interleaved.ifq

The files may be given in either order; the /1 and /2 suffixes of the first
pair of read names decide which file's read is written first.
"""

import sys


def read_record(f):
    """The next four-line FASTQ record from f, or None at the end of the file."""
    first_line = f.readline()
    if not first_line:
        return None
    record = [first_line]
    for _ in range(3):
        line = f.readline()
        if not line:
            raise Exception("File ended in the middle of a fastq record")
        record.append(line)
    return record


def check_pair(record1, record2):
    """Raises unless both records exist and their read names match apart from the /1 /2 suffix."""
    if record1 is None:
        raise Exception("file 1 ended prematurely")
    if record2 is None:
        raise Exception("file 2 ended prematurely")
    name1 = record1[0].strip()
    name2 = record2[0].strip()
    if name1[:-2] != name2[:-2]:
        raise Exception("read names don't match: %s != %s" % (name1, name2))


def file1_first(record1, record2):
    """True when file 1 holds the /1 reads, from the suffixes of one pair of read names."""
    name1 = record1[0].strip()
    name2 = record2[0].strip()
    suffixes = (name1[-2:], name2[-2:])
    if suffixes == ("/1", "/2"):
        return True
    if suffixes == ("/2", "/1"):
        return False
    raise Exception("Read names '%s' and '%s' don't end in /1 and /2" % (name1, name2))


def interleave(fastq1, fastq2, out):
    """Writes the records of fastq1 and fastq2 to out, each /1 read followed by its /2 mate."""
    order = None
    while True:
        record1 = read_record(fastq1)
        record2 = read_record(fastq2)
        if record1 is None and record2 is None:
            return
        check_pair(record1, record2)
        if order is None:
            order = file1_first(record1, record2)
        first, second = (record1, record2) if order else (record2, record1)
        out.write("".join(first))
        out.write("".join(second))


def main(argv):
    if len(argv) != 3:
        sys.stderr.write("Usage: fastq-interleaver <fastq_1> <fastq_2>\n")
        sys.exit(1)
    with open(argv[1]) as fastq1, open(argv[2]) as fastq2:
        interleave(fastq1, fastq2, sys.stdout)


if __name__ == "__main__":
    main(sys.argv)
