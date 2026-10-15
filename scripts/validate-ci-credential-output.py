"""CI 합성 credential을 원문 없이 검사하고 count-only evidence를 만든다."""

import argparse
import json
import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET


PATTERNS = {
    "syntheticEmail": re.compile(
        r"[A-Za-z0-9_.+%-]+@(?:example\.com|example\.invalid|build\.example)(?![\w.-])",
        re.IGNORECASE,
    ),
    "bcryptHash": re.compile(r"\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}"),
    "syntheticPassword": re.compile(
        r"\b(?:synthetic|different|concurrent)[A-Za-z0-9_-]*password[A-Za-z0-9_!.-]*",
        re.IGNORECASE,
    ),
}
INITIAL_ADMIN_TESTS = {
    "should_createExactlyOneHashedAdmin_withoutWebOrLongLivedBeans",
    "should_rejectReplayAndPreserveExistingAdministrator",
    "should_allowAtMostOneCommit_when_twoProvisioningTransactionsOverlap",
    "should_leaveDatabaseEmpty_when_insertFailsInsideTransaction",
}


def scan_text(text):
    counts = {name: 0 for name in PATTERNS}
    locations = []
    for line_number, line in enumerate(text.splitlines(), 1):
        found = {name: len(pattern.findall(line)) for name, pattern in PATTERNS.items()}
        if any(found.values()):
            locations.append({"line": line_number, "counts": found})
        for name, count in found.items():
            counts[name] += count
    return {"counts": counts, "locations": locations}


def junit_summary(directory):
    files = sorted(directory.glob("TEST-*.xml"))
    if not files:
        raise ValueError("JUnit evidence missing")
    totals = {name: 0 for name in ("tests", "failures", "errors", "skipped")}
    observed = set()
    for file in files:
        suite = ET.parse(file).getroot()
        for name in totals:
            totals[name] += int(suite.get(name, "0"))
        for case in suite.findall("testcase"):
            if case.get("classname") != (
                "kr.co.rhaomi.production.ProductionInitialAdminTaskIntegrationTests"
            ):
                continue
            if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                raise ValueError("Initial-admin regression unsuccessful")
            observed.add(case.get("name", "").removesuffix("()"))
    if not INITIAL_ADMIN_TESTS.issubset(observed) or totals["failures"] or totals["errors"]:
        raise ValueError("Required regression evidence missing or unsuccessful")
    return {**totals, "initialAdminPassed": sorted(observed)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", action="append", default=[], type=Path)
    parser.add_argument("--directory", action="append", default=[], type=Path)
    parser.add_argument("--junit-dir", type=Path)
    parser.add_argument("--stdin-label")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--git-head", required=True)
    args = parser.parse_args()
    report = {"schemaVersion": 1, "gitHead": args.git_head, "sources": [], "result": "FAIL"}
    try:
        if not re.fullmatch(r"[0-9a-f]{40}", args.git_head):
            raise ValueError("Exact Git head required")
        paths = list(args.file)
        for directory in args.directory + ([args.junit_dir] if args.junit_dir else []):
            if directory.is_symlink() or not directory.is_dir():
                raise ValueError("Required evidence directory missing")
            entries = sorted(directory.rglob("*"))
            if any(entry.is_symlink() for entry in entries):
                raise ValueError("Evidence symlink rejected")
            files = [entry for entry in entries if entry.is_file()]
            if not files:
                raise ValueError("Required evidence directory empty")
            paths.extend(files)
        for index, file in enumerate(dict.fromkeys(paths)):
            if file.is_symlink() or not file.is_file():
                raise ValueError("Required evidence file missing")
            report["sources"].append({
                "label": "file-" + str(index),
                **scan_text(file.read_bytes().decode("utf-8", errors="replace")),
            })
        if args.stdin_label:
            # 호출자의 임의 문자열이나 credential을 evidence label에 저장하지 않는다.
            text = sys.stdin.read()
            if not text.strip():
                raise ValueError("Required service log missing")
            report["sources"].append({"label": "stdin", **scan_text(text)})
        if not report["sources"]:
            raise ValueError("Evidence input required")
        report["counts"] = {
            name: sum(source["counts"][name] for source in report["sources"])
            for name in PATTERNS
        }
        if args.junit_dir:
            report["junit"] = junit_summary(args.junit_dir)
        report["result"] = "FAIL" if any(report["counts"].values()) else "PASS"
    except (OSError, ValueError, ET.ParseError):
        # 파일 내용·exception detail·credential 원문은 실패 출력에도 기록하지 않는다.
        report["inputError"] = True
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("CI credential output scan: " + report["result"])
    print(json.dumps(report.get("counts", {}), sort_keys=True))
    return 0 if report["result"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
