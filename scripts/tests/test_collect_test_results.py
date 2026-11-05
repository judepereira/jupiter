import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1] / "collect_test_results.py"
spec = importlib.util.spec_from_file_location("collector", MODULE)
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


class CollectorTests(unittest.TestCase):
    def test_keeps_valid_failures_when_another_report_is_malformed(self):
        with tempfile.TemporaryDirectory() as temp:
            reports = Path(temp)
            (reports / "TEST-a.xml").write_text('<testsuite><testcase classname="A" name="fails"><failure message="bad"/></testcase><testcase name="skip"><skipped/><failure message="ignored"/></testcase></testsuite>')
            (reports / "TEST-b.xml").write_text("<testsuite><testcase")
            files, failures, errors = collector.collect(reports)
            self.assertEqual(["TEST-a.xml", "TEST-b.xml"], [p.name for p in files])
            self.assertEqual([{"class": "A", "name": "fails", "message": "bad"}], failures)
            self.assertEqual(1, len(errors))

    def test_failure_message_falls_back_to_first_nonblank_text_line(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "TEST-x.xml").write_text('<testsuite><testcase name="x"><error>\n  trace line\nother\n</error></testcase></testsuite>')
            _, failures, _ = collector.collect(root)
            self.assertEqual("trace line", failures[0]["message"])

    def test_cli_persists_partial_results_and_nonzero_exit_status(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            reports = root / "reports"
            reports.mkdir()
            (reports / "TEST-bad.xml").write_text("<not-closed>")
            output = root / "nested" / "result.json"
            # Exercise CLI behavior in a subprocess so argparse and its exit code are covered.
            import subprocess, sys
            proc = subprocess.run([sys.executable, str(MODULE), "--reports", str(reports), "--output", str(output), "--build-sha", "build", "--head-sha", "head", "--exit-status", "17"], capture_output=True)
            self.assertEqual(1, proc.returncode)
            data = json.loads(output.read_text())
            self.assertEqual("build", data["build_sha"])
            self.assertEqual("head", data["head_sha"])
            self.assertEqual(17, data["build_exit_status"])
            self.assertTrue(data["reports_available"])
            self.assertEqual(1, len(data["report_errors"]))

    def test_combined_failure_message_is_capped_without_truncating_name(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "TEST-x.xml").write_text('<testsuite><testcase name="' + "n" * 5000 + '"><failure message="' + "a" * 3000 + '"/><error message="' + "b" * 3000 + '"/></testcase></testsuite>')
            _, failures, _ = collector.collect(root)
            self.assertEqual(5000, len(failures[0]["name"]))
            self.assertEqual(1000, len(failures[0]["message"]))

    def test_failure_exit_without_reports_is_diagnosed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            output = root / "out.json"
            import subprocess, sys
            proc = subprocess.run([sys.executable, str(MODULE), "--reports", str(root), "--output", str(output), "--build-sha", "b", "--exit-status", "17"], capture_output=True)
            self.assertEqual(1, proc.returncode)
            self.assertIn("No Surefire XML reports found", json.loads(output.read_text())["report_errors"])

    def test_success_without_reports_is_an_error(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            _, failures, errors = collector.collect(root)
            self.assertEqual([], failures)
            self.assertEqual([], errors)
            # CLI adds the successful-build/no-reports diagnostic.
            import subprocess, sys
            output = root / "out.json"
            proc = subprocess.run([sys.executable, str(MODULE), "--reports", str(root), "--output", str(output), "--build-sha", "b", "--exit-status", "0"], capture_output=True)
            self.assertEqual(1, proc.returncode)
            self.assertIn("No Surefire XML reports found", json.loads(output.read_text())["report_errors"])


if __name__ == "__main__":
    unittest.main()
