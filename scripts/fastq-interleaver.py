#!/usr/bin/env python3
"""
Interleave two paired FASTQ files into one: each read from the /1 file is
followed by its mate from the /2 file.

Usage: fastq-interleaver <fastq_1> <fastq_2> > interleaved.ifq

The files may be given in either order; the /1 and /2 suffixes of the first
pair of read names decide which file's read is written first. Needs Python 3.10+.
"""

import sys
from collections.abc import Iterable, Iterator
from dataclasses import dataclass
from itertools import zip_longest
from typing import TextIO


class InterleaveError(ValueError):
    """The two files are not a valid pair of FASTQ files."""


@dataclass(frozen=True)
class Record:
    """One four-line FASTQ record, lines kept with their newlines."""

    lines: tuple[str, ...]

    @property
    def name(self) -> str:
        return self.lines[0].strip()

    @property
    def stem(self) -> str:
        """The read name without its /1 or /2 suffix."""
        return self.name[:-2]

    @property
    def suffix(self) -> str:
        return self.name[-2:]

    @property
    def text(self) -> str:
        return "".join(self.lines)


def records(f: TextIO) -> Iterator[Record]:
    """The FASTQ records of f, in order."""
    while first_line := f.readline():
        rest = tuple(f.readline() for _ in range(3))
        if not all(rest):
            raise InterleaveError("File ended in the middle of a fastq record")
        yield Record((first_line, *rest))


def check_pair(record1: Record | None, record2: Record | None) -> tuple[Record, Record]:
    """Both records, when both exist and name the same read; otherwise raises."""
    match record1, record2:
        case None, _:
            raise InterleaveError("file 1 ended prematurely")
        case _, None:
            raise InterleaveError("file 2 ended prematurely")
        case Record() as r1, Record() as r2 if r1.stem != r2.stem:
            raise InterleaveError(f"read names don't match: {r1.name} != {r2.name}")
        case Record() as r1, Record() as r2:
            return r1, r2
        case _:
            raise InterleaveError(f"not FASTQ records: {record1!r}, {record2!r}")


def file1_first(record1: Record, record2: Record) -> bool:
    """True when file 1 holds the /1 reads, from the suffixes of one pair of reads."""
    match record1.suffix, record2.suffix:
        case "/1", "/2":
            return True
        case "/2", "/1":
            return False
        case _:
            raise InterleaveError(
                f"Read names '{record1.name}' and '{record2.name}' don't end in /1 and /2"
            )


def interleave(fastq1: Iterable[Record], fastq2: Iterable[Record]) -> Iterator[str]:
    """The records of both files as text, each /1 read followed by its /2 mate."""
    order: bool | None = None
    for record1, record2 in (
        check_pair(r1, r2) for r1, r2 in zip_longest(fastq1, fastq2)
    ):
        if order is None:
            order = file1_first(record1, record2)
        first, second = (record1, record2) if order else (record2, record1)
        yield first.text + second.text


def main(argv: list[str]) -> None:
    match argv:
        case [_, path1, path2]:
            with open(path1) as fastq1, open(path2) as fastq2:
                sys.stdout.writelines(interleave(records(fastq1), records(fastq2)))
        case _:
            sys.stderr.write("Usage: fastq-interleaver <fastq_1> <fastq_2>\n")
            sys.exit(1)


if __name__ == "__main__":
    main(sys.argv)
