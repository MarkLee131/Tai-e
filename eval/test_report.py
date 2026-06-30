"""
TDD tests for eval/report.py.

Feeds tiny canned CSVs (written inline) into the report generator and asserts:
  - comparison_table.md exists and has one row per config in results.csv
  - pareto_precision_vs_time.png is created and non-empty
  - robustness_recall_vs_error.png is created and non-empty
  - cost_vs_precision.png is created and non-empty
  - comparison_table.md contains costUsd and llmQueries columns
"""

import pathlib
import subprocess
import sys
import textwrap

import pytest

# ---------------------------------------------------------------------------
# Canned fixture data
# ---------------------------------------------------------------------------

# results.csv columns (from MetricCollector.CSV_HEADER):
# config,benchmark,timeMs,memMb,mayFailCasts,avgPtsSize,polyCallSites,
# reachableMethods,aliasPairs,objects,costUsd,llmQueries
RESULTS_CSV = textwrap.dedent("""\
    config,benchmark,timeMs,memMb,mayFailCasts,avgPtsSize,polyCallSites,reachableMethods,aliasPairs,objects,costUsd,llmQueries
    B0,test-bench,1000,256,50,3.200000,20,500,1000,800,0.000000,0
    B1,test-bench,1200,280,45,2.900000,18,510,950,790,0.000000,0
    B2z,test-bench,1400,300,42,2.700000,17,515,900,780,0.000000,0
    B2s,test-bench,1600,320,40,2.500000,16,520,880,770,0.000000,0
    B3,test-bench,1800,340,38,2.300000,15,525,860,760,0.000000,0
    A1,test-bench,2000,360,35,2.100000,14,530,840,750,0.001234,42
    A2,test-bench,2200,380,33,1.900000,13,535,820,740,0.000987,38
    A3,test-bench,2400,400,30,1.700000,12,540,800,730,0.000543,21
""")

# robustness.csv columns (derived from SweepPoint record):
# config,errorRate,recall,mayFailCasts,avgPtsSize,polyCallSites,
# reachableMethods,aliasPairs,objects
ROBUSTNESS_CSV = textwrap.dedent("""\
    config,errorRate,recall,mayFailCasts,avgPtsSize,polyCallSites,reachableMethods,aliasPairs,objects
    A1,0.0,1.0,35,2.100000,14,530,840,750
    A1,0.25,1.0,36,2.150000,14,530,838,750
    A1,0.5,1.0,37,2.200000,15,530,835,750
    A1,0.75,1.0,38,2.250000,15,530,832,750
    A1,1.0,1.0,39,2.300000,15,530,830,750
    A2,0.0,1.0,33,1.900000,13,535,820,740
    A2,0.25,1.0,34,1.950000,13,535,818,740
    A2,0.5,1.0,35,2.000000,14,535,815,740
    A2,0.75,1.0,36,2.050000,14,535,812,740
    A2,1.0,1.0,37,2.100000,14,535,810,740
    CAFD-unsound,0.0,1.0,50,3.200000,20,500,1000,800
    CAFD-unsound,0.25,0.85,52,3.300000,21,495,1010,810
    CAFD-unsound,0.5,0.65,55,3.500000,22,490,1020,820
    CAFD-unsound,0.75,0.45,58,3.700000,23,485,1030,830
    CAFD-unsound,1.0,0.25,62,4.000000,24,480,1040,840
""")

EXPECTED_CONFIGS = {"B0", "B1", "B2z", "B2s", "B3", "A1", "A2", "A3"}


# ---------------------------------------------------------------------------
# Helper: run report.py via subprocess
# ---------------------------------------------------------------------------

def _run_report(results_csv_path: str, robustness_csv_path: str, outdir: str):
    """Invoke report.py as a subprocess."""
    report_py = pathlib.Path(__file__).parent / "report.py"
    result = subprocess.run(
        [sys.executable, str(report_py),
         results_csv_path, robustness_csv_path, outdir],
        capture_output=True,
        text=True,
    )
    return result


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture()
def canned_data(tmp_path):
    """Write canned CSVs to a temp directory and return paths."""
    results_path = tmp_path / "results.csv"
    robustness_path = tmp_path / "robustness.csv"
    outdir = tmp_path / "out"
    outdir.mkdir()

    results_path.write_text(RESULTS_CSV)
    robustness_path.write_text(ROBUSTNESS_CSV)

    return {
        "results": str(results_path),
        "robustness": str(robustness_path),
        "outdir": str(outdir),
        "tmp_path": tmp_path,
    }


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class TestReportGeneration:
    """Tests for report.py CLI."""

    def test_report_exits_zero(self, canned_data):
        """report.py should exit with code 0 on valid input."""
        proc = _run_report(
            canned_data["results"],
            canned_data["robustness"],
            canned_data["outdir"],
        )
        assert proc.returncode == 0, (
            f"report.py exited {proc.returncode}.\n"
            f"stdout: {proc.stdout}\nstderr: {proc.stderr}"
        )

    def test_comparison_table_created(self, canned_data):
        """comparison_table.md must be created in outdir."""
        _run_report(
            canned_data["results"],
            canned_data["robustness"],
            canned_data["outdir"],
        )
        table_path = pathlib.Path(canned_data["outdir"]) / "comparison_table.md"
        assert table_path.exists(), "comparison_table.md was not created"
        assert table_path.stat().st_size > 0, "comparison_table.md is empty"

    def test_comparison_table_has_one_row_per_config(self, canned_data):
        """The markdown table must contain exactly one data row per config."""
        _run_report(
            canned_data["results"],
            canned_data["robustness"],
            canned_data["outdir"],
        )
        table_path = pathlib.Path(canned_data["outdir"]) / "comparison_table.md"
        text = table_path.read_text()

        # Parse markdown table rows: lines starting with '|' that are not the
        # header or the separator (---)
        data_rows = [
            line.strip()
            for line in text.splitlines()
            if line.strip().startswith("|")
            and "---" not in line
            and not all(
                cell.strip() in ("config", "benchmark", "timeMs", "memMb",
                                 "mayFailCasts", "avgPtsSize", "polyCallSites",
                                 "reachableMethods", "aliasPairs", "objects",
                                 "costUsd", "llmQueries")
                for cell in line.strip().strip("|").split("|")
                if cell.strip()
            )
        ]

        # The first cell of each data row is the config name
        found_configs = set()
        for row in data_rows:
            cells = [c.strip() for c in row.strip("|").split("|")]
            if cells:
                found_configs.add(cells[0])

        assert found_configs == EXPECTED_CONFIGS, (
            f"Expected configs {EXPECTED_CONFIGS}, found {found_configs}\n"
            f"Table:\n{text}"
        )

    def test_pareto_png_created(self, canned_data):
        """pareto_precision_vs_time.png must be created and non-empty."""
        _run_report(
            canned_data["results"],
            canned_data["robustness"],
            canned_data["outdir"],
        )
        png_path = pathlib.Path(canned_data["outdir"]) / "pareto_precision_vs_time.png"
        assert png_path.exists(), "pareto_precision_vs_time.png was not created"
        assert png_path.stat().st_size > 0, "pareto_precision_vs_time.png is empty"

    def test_robustness_png_created(self, canned_data):
        """robustness_recall_vs_error.png must be created and non-empty."""
        _run_report(
            canned_data["results"],
            canned_data["robustness"],
            canned_data["outdir"],
        )
        png_path = pathlib.Path(canned_data["outdir"]) / "robustness_recall_vs_error.png"
        assert png_path.exists(), "robustness_recall_vs_error.png was not created"
        assert png_path.stat().st_size > 0, "robustness_recall_vs_error.png is empty"

    def test_comparison_table_has_required_metric_columns(self, canned_data):
        """The markdown table header must include precision and cost metric columns."""
        _run_report(
            canned_data["results"],
            canned_data["robustness"],
            canned_data["outdir"],
        )
        table_path = pathlib.Path(canned_data["outdir"]) / "comparison_table.md"
        text = table_path.read_text()

        # Find the header line (first row starting with '|')
        header_line = next(
            (line for line in text.splitlines() if line.strip().startswith("|")),
            None,
        )
        assert header_line is not None, "No header row found in table"
        header_cells = {c.strip() for c in header_line.strip("|").split("|")}

        required = {"config", "mayFailCasts", "avgPtsSize", "timeMs"}
        missing = required - header_cells
        assert not missing, (
            f"Table header missing columns: {missing}\nHeader: {header_line}"
        )

    def test_cost_vs_precision_png_created(self, canned_data):
        """cost_vs_precision.png must be created and non-empty."""
        _run_report(
            canned_data["results"],
            canned_data["robustness"],
            canned_data["outdir"],
        )
        png_path = pathlib.Path(canned_data["outdir"]) / "cost_vs_precision.png"
        assert png_path.exists(), "cost_vs_precision.png was not created"
        assert png_path.stat().st_size > 0, "cost_vs_precision.png is empty"

    def test_comparison_table_has_cost_and_query_columns(self, canned_data):
        """The comparison table header must contain costUsd and llmQueries columns."""
        _run_report(
            canned_data["results"],
            canned_data["robustness"],
            canned_data["outdir"],
        )
        table_path = pathlib.Path(canned_data["outdir"]) / "comparison_table.md"
        text = table_path.read_text()
        header_line = next(
            (line for line in text.splitlines() if line.strip().startswith("|")),
            None,
        )
        assert header_line is not None, "No header row found in table"
        header_cells = {c.strip() for c in header_line.strip("|").split("|")}
        for col in ("costUsd", "llmQueries"):
            assert col in header_cells, (
                f"Table header missing '{col}' column.\nHeader: {header_line}"
            )
