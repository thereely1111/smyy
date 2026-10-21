#!/usr/bin/env sh
# ============================================================================
#  run.sh - single entry point for running this skill's Python scripts (Linux/macOS).
#
#  WHY: the platform shell gets a minimal env (PATH only, inheritEnv=false), and a
#  machine often has several interpreters with different packages installed. This
#  wrapper picks an interpreter in this order:
#    1. python_path.txt next to this script (one line = absolute path)  <-- override
#    2. python3 / python on PATH, preferring one that imports all deps
#    3. common absolute locations (/usr/bin, /usr/local/bin, venvs)
#
#  TO CHANGE THE INTERPRETER LATER:
#    - preferred: write the absolute path into python_path.txt next to this script.
#    - or bypass this wrapper and call "<python>" <script.py> directly.
#
#  Keep this file ASCII-only and LF line endings.
#
#  USAGE (note: invoke via "sh run.sh", NOT "./run.sh" -- the exec bit is not set
#         in git; see the maintenance note in the mm-0 skill):
#    sh run.sh probe_inputs.py --problem "<dir>" --out-dir "<session>/analysis"
#    sh run.sh render_animation.py --scene-map ... --problem ... --out-dir ...
#    sh run.sh install_deps.py
# ============================================================================
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)

if [ $# -lt 1 ]; then
  echo "[run.sh] usage: run.sh <script.py> [args...]" >&2
  exit 2
fi

SCRIPT="$1"
shift

# Absolute script path (e.g. a script from another skill) is used as-is.
case "$SCRIPT" in
  /*) SCRIPTFULL="$SCRIPT" ;;
  *)  SCRIPTFULL="$HERE/$SCRIPT" ;;
esac

DEPS="import docx, openpyxl, pymupdf, latex2mathml"

can_run() {
  "$1" -c "import sys" >/dev/null 2>&1
}

has_deps() {
  "$1" -c "$DEPS" >/dev/null 2>&1
}

RUN=""

# ---- 0) session-scoped override (from platform shell env) ------------------
if [ -z "$RUN" ] && [ -n "${SMYY_PYTHON:-}" ] && can_run "$SMYY_PYTHON"; then
  RUN="$SMYY_PYTHON"
  echo "[run.sh] interpreter from SMYY_PYTHON: $SMYY_PYTHON"
fi
if [ -z "$RUN" ] && [ -n "${SMYY_SESSION_DIR:-}" ] && [ -f "$SMYY_SESSION_DIR/python_path.txt" ]; then
  SPY=$(head -n 1 "$SMYY_SESSION_DIR/python_path.txt" | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
  if [ -n "$SPY" ] && can_run "$SPY"; then
    RUN="$SPY"
    echo "[run.sh] interpreter from session python_path.txt: $SPY"
  fi
fi
# ---- 1) explicit override file ---------------------------------------------
OVERRIDE_FILE="$HERE/python_path.txt"
if [ -f "$OVERRIDE_FILE" ]; then
  OVERRIDE=$(head -n 1 "$OVERRIDE_FILE" | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
  if [ -n "$OVERRIDE" ]; then
    if [ -x "$OVERRIDE" ] || command -v "$OVERRIDE" >/dev/null 2>&1; then
      if can_run "$OVERRIDE"; then
        RUN="$OVERRIDE"
        echo "[run.sh] interpreter from python_path.txt: $OVERRIDE"
        if ! has_deps "$RUN"; then
          echo "[run.sh] WARNING: it is missing rendering dependencies. Run: run.sh install_deps.py" >&2
        fi
      else
        echo "[run.sh] python_path.txt points to a broken interpreter; ignoring it." >&2
      fi
    else
      echo "[run.sh] python_path.txt path not executable; ignoring it." >&2
    fi
  fi
fi

# ---- 2) PATH + common locations --------------------------------------------
if [ -z "$RUN" ]; then
  FALLBACK=""
  for CAND in python3 python /usr/local/bin/python3 /usr/bin/python3 \
              /opt/venv/bin/python "$HERE/../../../.venv/bin/python"; do
    command -v "$CAND" >/dev/null 2>&1 || [ -x "$CAND" ] || continue
    if [ -z "$FALLBACK" ] && can_run "$CAND"; then
      FALLBACK="$CAND"
    fi
    if can_run "$CAND" && has_deps "$CAND"; then
      RUN="$CAND"
      break
    fi
  done
  [ -z "$RUN" ] && [ -n "$FALLBACK" ] && RUN="$FALLBACK"
  if [ -n "$RUN" ] && ! has_deps "$RUN"; then
    echo "[run.sh] WARNING: $RUN is missing rendering dependencies. Run: run.sh install_deps.py" >&2
  fi
fi

if [ -z "$RUN" ]; then
  echo "[run.sh] no usable Python interpreter found." >&2
  echo "[run.sh] install Python 3.8+ or write an absolute path into python_path.txt next to this script." >&2
  exit 3
fi

exec "$RUN" "$SCRIPTFULL" "$@"
