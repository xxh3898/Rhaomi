import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/validate-ci-credential-output.py"
SPEC = importlib.util.spec_from_file_location("credential_output", SCRIPT)
SCANNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCANNER)


class CredentialOutputTests(unittest.TestCase):
    def test_detects_postgres_detail_without_recording_values(self):
        email = "rollback-fixture" + "@example.com"
        password_hash = "$2b$12$" + "a" * 53
        result = SCANNER.scan_text("DETAIL: Failing row contains (" + email + ", " + password_hash + ")")
        self.assertEqual(result["counts"]["syntheticEmail"], 1)
        self.assertEqual(result["counts"]["bcryptHash"], 1)
        self.assertEqual(result["locations"][0]["line"], 1)
        self.assertFalse(email in json.dumps(result))
        self.assertFalse(password_hash in json.dumps(result))

    def test_detects_initial_admin_password_family(self):
        for prefix in ("synthetic-initial-", "different-synthetic-", "concurrent-"):
            result = SCANNER.scan_text(prefix + "password-one-123!")
            self.assertEqual(result["counts"]["syntheticPassword"], 1)

    def test_accepts_safe_guard_failure_and_npm_version_not_email(self):
        text = ('ERROR: relation "initial_admin_rollback_guard" violates check constraint\n'
                'DETAIL: Failing row contains (1).\nSTATEMENT: insert into admin_users values ($1)\n'
                'npm install -g npm@12.2.0\neslint@10.2.0')
        self.assertFalse(any(SCANNER.scan_text(text)["counts"].values()))

    def run_cli(self, directory, *args, stdin=""):
        output = directory / "summary.json"
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--git-head", "a" * 40,
             "--output", str(output), *args],
            input=stdin, text=True, capture_output=True,
        )
        return result, json.loads(output.read_text())

    def test_cli_fails_closed_without_echoing_credential(self):
        with tempfile.TemporaryDirectory() as name:
            email = "rollback-fixture" + "@example.invalid"
            result, report = self.run_cli(Path(name), "--stdin-label", "postgres", stdin=email)
            self.assertEqual(result.returncode, 1)
            self.assertEqual(report["counts"]["syntheticEmail"], 1)
            self.assertFalse(email in result.stdout + result.stderr + json.dumps(report))

    def test_cli_rejects_absent_empty_or_symlink_evidence(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            empty = directory / "empty"
            empty.mkdir()
            target = directory / "target.log"
            target.write_text("safe")
            link = directory / "link.log"
            link.symlink_to(target)
            for args in ((), ("--file", str(directory / "missing")),
                         ("--directory", str(empty)), ("--file", str(link)),
                         ("--stdin-label", "postgres")):
                result, report = self.run_cli(directory, *args)
                self.assertEqual(result.returncode, 1)
                self.assertTrue(report["inputError"])

    def test_junit_requires_real_initial_admin_tests_without_skips(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            cases = "".join(
                '<testcase classname="kr.co.rhaomi.production.ProductionInitialAdminTaskIntegrationTests" '
                'name="' + method + '()"/>' for method in SCANNER.INITIAL_ADMIN_TESTS
            )
            report = directory / "TEST-initial-admin.xml"
            report.write_text('<testsuite tests="4" failures="0" errors="0" skipped="0">' + cases + '</testsuite>')
            self.assertEqual(SCANNER.junit_summary(directory)["tests"], 4)
            for tag in ("skipped", "failure", "error"):
                report.write_text('<testsuite tests="4">' + cases.replace(
                    '/>', '><' + tag + '/></testcase>', 1) + '</testsuite>')
                with self.assertRaises(ValueError):
                    SCANNER.junit_summary(directory)

    def test_compose_step_generates_and_masks_ephemeral_credentials(self):
        workflow = (ROOT / ".github/workflows/validate.yml").read_text()
        step = workflow.split("- name: Compose health·인증·재기동 검증\n", 1)[1]
        command = "\n".join(line[10:] for line in step.split("run: |\n", 1)[1].splitlines())
        command = command.replace(
            "sh scripts/validate-backend-compose.sh .env.dev.local",
            'test "$RHAOMI_BOOTSTRAP_ADMIN_ENABLED" = true\n'
            'test "${#RHAOMI_BOOTSTRAP_ADMIN_PASSWORD}" -eq 64\n'
            'case "$RHAOMI_BOOTSTRAP_ADMIN_EMAIL" in '
            'compose-smoke-*@example.invalid) ;; *) exit 1 ;; esac',
        )
        result = subprocess.run(["sh", "-eu", "-c", command], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0)
        lines = result.stdout.splitlines()
        self.assertEqual(len(lines), 2)
        self.assertTrue(all(line.startswith("::add-mask::") for line in lines))


if __name__ == "__main__":
    unittest.main()
