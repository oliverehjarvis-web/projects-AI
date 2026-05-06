#!/usr/bin/env bash
# Generate server/web/src/api/types.gen.ts from the server's Pydantic models.
#
# Pydantic 2 + the Python 3.10 union syntax used in the routers means we need
# Python 3.10+. Rather than make every contributor install that toolchain,
# this script runs the codegen in the project's docker base image.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT="$SERVER_DIR/web/src/api/types.gen.ts"

docker run --rm \
    -e API_TOKEN=dummy \
    -v "$SERVER_DIR:/app" \
    -w /app \
    python:3.12-slim \
    sh -c "pip install --quiet -r requirements.txt && python scripts/gen_ts_types.py" \
    > "$OUT"

echo "Wrote $OUT"
