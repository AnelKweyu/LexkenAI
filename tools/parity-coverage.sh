#!/usr/bin/env bash
# Parity coverage gate (parity plan §1, §5): every request row of an in-scope appendix area must
# have a contract test whose name carries the row's tag, e.g. `auth#12`.
#
#   tools/parity-coverage.sh                 # appendix at $PARITY_APPENDIX or the Desktop default
#   PARITY_APPENDIX=/path/to/appendix tools/parity-coverage.sh
#
# Prints a per-area table (paste it into the PR description) and exits 1 when an in-scope area
# has an untagged row. Without the appendix directory it skips with exit 0, so CI stays green on
# machines that do not hold the inventories.
set -euo pipefail
cd "$(dirname "$0")/.."

APPENDIX="${PARITY_APPENDIX:-$HOME/OneDrive/Desktop/LexkenAI-Parity-Appendix}"
if [ ! -d "$APPENDIX" ]; then
  echo "parity-coverage: appendix directory not found at '$APPENDIX' (set PARITY_APPENDIX); skipped."
  exit 0
fi

PY=python3
command -v python3 >/dev/null 2>&1 || PY=python
exec "$PY" -I tools/parity-coverage.py "$APPENDIX" app/src/test/resources/parity app/src/test/java "$@"
