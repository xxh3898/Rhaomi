#!/usr/bin/python3

import sys
from pathlib import Path

from rhaomi_homeops import (
    HomeOpsContractError,
    build_backup_event,
    build_deployment_event,
    report_event,
)


def optional(value: str) -> str | None:
    return None if value == "-" else value


def main() -> int:
    app_root = Path(__file__).resolve().parent
    if len(sys.argv) == 8 and sys.argv[1] == "deployment":
        event = build_deployment_event(
            sys.argv[2],
            sys.argv[3],
            sys.argv[4],
            sys.argv[5],
            optional(sys.argv[6]),
            optional(sys.argv[7]),
        )
        outcome = report_event("deployments", event, app_root)
    elif len(sys.argv) == 7 and sys.argv[1] == "backup":
        event = build_backup_event(
            sys.argv[2],
            sys.argv[3],
            sys.argv[4],
            optional(sys.argv[5]),
            optional(sys.argv[6]),
        )
        outcome = report_event("backups", event, app_root)
    else:
        raise HomeOpsContractError("HOMEOPS_EVENT_INVALID")
    print(outcome)
    return 0 if outcome in {"RETAINED", "NOT_CONFIGURED"} else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except HomeOpsContractError as error:
        print(error.code, file=sys.stderr)
        raise SystemExit(1)
    except Exception:
        print("HOMEOPS_EVENT_FAILED", file=sys.stderr)
        raise SystemExit(1)
