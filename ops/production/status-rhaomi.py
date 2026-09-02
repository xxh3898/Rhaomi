#!/usr/bin/python3

import json
import sys

from rhaomi_homeops import HomeOpsContractError, PRODUCTION_ROOT, collect_status, status_failure


def main() -> int:
    if len(sys.argv) != 1:
        print(json.dumps(status_failure("RHAOMI_STATUS_INPUT_INVALID"), separators=(",", ":")))
        return 1
    try:
        result = collect_status(PRODUCTION_ROOT)
    except HomeOpsContractError as error:
        print(json.dumps(status_failure(error.code), separators=(",", ":")))
        return 1
    except Exception:
        print(json.dumps(status_failure("RHAOMI_STATUS_UNAVAILABLE"), separators=(",", ":")))
        return 1
    print(json.dumps(result, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
