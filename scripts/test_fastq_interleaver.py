"""Tests for fastq-interleaver.py. Run with: uv run --with pytest pytest scripts"""

import importlib.util
import io
import subprocess
import sys
from pathlib import Path

import pytest

_SCRIPT = Path(__file__).resolve().parent / "fastq-interleaver.py"


def _load_script():
    # the file name has a dash, so it cannot be imported by name
    spec = importlib.util.spec_from_file_location("fastq_interleaver", _SCRIPT)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


interleaver = _load_script()


def record(name: str) -> str:
    return f"@{name}\nACGT\n+\nIIII\n"


def run(text1: str, text2: str) -> str:
    return "".join(
        interleaver.interleave(
            interleaver.records(io.StringIO(text1)),
            interleaver.records(io.StringIO(text2)),
        )
    )


def test_records_are_read_four_lines_at_a_time():
    found = list(interleaver.records(io.StringIO(record("a/1") + record("b/1"))))
    assert [r.lines for r in found] == [
        ("@a/1\n", "ACGT\n", "+\n", "IIII\n"),
        ("@b/1\n", "ACGT\n", "+\n", "IIII\n"),
    ]
    assert (found[0].name, found[0].stem, found[0].suffix) == ("@a/1", "@a", "/1")


def test_a_truncated_record_is_rejected():
    with pytest.raises(interleaver.InterleaveError, match="middle of a fastq record"):
        list(interleaver.records(io.StringIO("@a/1\nACGT\n")))


def test_interleaves_with_the_first_file_holding_read_1():
    out = run(record("a/1") + record("b/1"), record("a/2") + record("b/2"))
    assert out == record("a/1") + record("a/2") + record("b/1") + record("b/2")


def test_interleaves_with_the_files_given_in_either_order():
    out = run(record("a/2") + record("b/2"), record("a/1") + record("b/1"))
    assert out == record("a/1") + record("a/2") + record("b/1") + record("b/2")


def test_empty_files_produce_nothing():
    assert run("", "") == ""


@pytest.mark.parametrize(
    ("text1", "text2", "message"),
    [
        ("", record("a/2"), "file 1 ended prematurely"),
        (record("a/1"), "", "file 2 ended prematurely"),
        (record("a/1"), record("b/2"), "read names don't match"),
        (record("a/1"), record("a/1"), "don't end in /1 and /2"),
    ],
)
def test_mismatched_files_are_rejected(text1, text2, message):
    with pytest.raises(interleaver.InterleaveError, match=message):
        run(text1, text2)


def test_command_line(tmp_path):
    fastq1 = tmp_path / "r1.fq"
    fastq2 = tmp_path / "r2.fq"
    fastq1.write_text(record("a/1"))
    fastq2.write_text(record("a/2"))
    done = subprocess.run(
        [sys.executable, str(_SCRIPT), str(fastq1), str(fastq2)],
        capture_output=True,
        text=True,
        check=True,
    )
    assert done.stdout == record("a/1") + record("a/2")


def test_command_line_usage():
    done = subprocess.run(
        [sys.executable, str(_SCRIPT)], capture_output=True, text=True, check=False
    )
    assert done.returncode == 1
    assert "Usage" in done.stderr


def test_main_writes_the_interleaved_records(tmp_path, capsys):
    fastq1 = tmp_path / "r1.fq"
    fastq2 = tmp_path / "r2.fq"
    fastq1.write_text(record("a/2"))
    fastq2.write_text(record("a/1"))
    interleaver.main(["fastq-interleaver", str(fastq1), str(fastq2)])
    assert capsys.readouterr().out == record("a/1") + record("a/2")


def test_main_prints_usage_for_the_wrong_number_of_arguments(capsys):
    with pytest.raises(SystemExit) as exit_info:
        interleaver.main(["fastq-interleaver", "only-one.fq"])
    assert exit_info.value.code == 1
    assert "Usage" in capsys.readouterr().err
