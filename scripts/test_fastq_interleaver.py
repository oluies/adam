"""Tests for fastq-interleaver.py. Run with: python3 -m pytest scripts"""

import importlib.util
import io
import os
import subprocess
import sys

import pytest

_SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fastq-interleaver.py")
_spec = importlib.util.spec_from_file_location("fastq_interleaver", _SCRIPT)
interleaver = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(interleaver)


def record(name):
    return "@%s\nACGT\n+\nIIII\n" % name


def run(text1, text2):
    out = io.StringIO()
    interleaver.interleave(io.StringIO(text1), io.StringIO(text2), out)
    return out.getvalue()


def test_reads_one_record_at_a_time():
    f = io.StringIO(record("a/1") + record("b/1"))
    assert interleaver.read_record(f) == ["@a/1\n", "ACGT\n", "+\n", "IIII\n"]
    assert interleaver.read_record(f)[0] == "@b/1\n"
    assert interleaver.read_record(f) is None


def test_a_truncated_record_is_rejected():
    with pytest.raises(Exception, match="middle of a fastq record"):
        interleaver.read_record(io.StringIO("@a/1\nACGT\n"))


def test_interleaves_with_the_first_file_holding_read_1():
    out = run(record("a/1") + record("b/1"), record("a/2") + record("b/2"))
    assert out == record("a/1") + record("a/2") + record("b/1") + record("b/2")


def test_interleaves_with_the_files_given_in_either_order():
    out = run(record("a/2") + record("b/2"), record("a/1") + record("b/1"))
    assert out == record("a/1") + record("a/2") + record("b/1") + record("b/2")


def test_empty_files_produce_nothing():
    assert run("", "") == ""


@pytest.mark.parametrize(("text1", "text2", "message"), [
    ("", record("a/2"), "file 1 ended prematurely"),
    (record("a/1"), "", "file 2 ended prematurely"),
    (record("a/1"), record("b/2"), "read names don't match"),
    (record("a/1"), record("a/1"), "don't end in /1 and /2"),
])
def test_mismatched_files_are_rejected(text1, text2, message):
    with pytest.raises(Exception, match=message):
        run(text1, text2)


def test_command_line(tmp_path):
    fastq1 = tmp_path / "r1.fq"
    fastq2 = tmp_path / "r2.fq"
    fastq1.write_text(record("a/1"))
    fastq2.write_text(record("a/2"))
    done = subprocess.run([sys.executable, _SCRIPT, str(fastq1), str(fastq2)],
                          capture_output=True, text=True, check=True)
    assert done.stdout == record("a/1") + record("a/2")


def test_command_line_usage():
    done = subprocess.run([sys.executable, _SCRIPT], capture_output=True, text=True)
    assert done.returncode == 1
    assert "Usage" in done.stderr
