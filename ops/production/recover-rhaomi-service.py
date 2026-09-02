#!/usr/bin/python3

import json
import sys

from rhaomi_homeops import PRODUCTION_ROOT, recover_service


def main() -> int:
    operation = sys.argv[1] if len(sys.argv) > 1 else ""
    service = sys.argv[2] if len(sys.argv) > 2 else ""
    if len(sys.argv) != 3:
        operation = ""
        service = ""
    result, status = recover_service(PRODUCTION_ROOT, operation, service)
    print(json.dumps(result, separators=(",", ":")))
    return status


if __name__ == "__main__":
    raise SystemExit(main())
