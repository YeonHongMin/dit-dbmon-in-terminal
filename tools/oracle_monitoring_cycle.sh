#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

CYCLE_COUNT="${CYCLE_COUNT:-1}"
CYCLE_PAUSE_SECONDS="${CYCLE_PAUSE_SECONDS:-5}"
CYCLE_ROOT_DIR="${CYCLE_ROOT_DIR:-$ROOT_DIR/.dit/cycles}"
RUN_PREFIX="${RUN_PREFIX:-oracle-run}"

print_usage() {
  cat <<'EOF'
Oracle monitoring cycle runner

Runs tools/oracle_monitoring_stack.sh repeatedly and archives artifacts per run directory.

Environment variables:
  CYCLE_COUNT, CYCLE_PAUSE_SECONDS, CYCLE_ROOT_DIR, RUN_PREFIX
  Plus all variables supported by tools/oracle_monitoring_stack.sh

Example:
  CYCLE_COUNT=3 LOAD_SECONDS=300 MONITOR_SECONDS=300 tools/oracle_monitoring_cycle.sh
EOF
}

if [[ "${1:-}" == "--help" ]]; then
  print_usage
  exit 0
fi

if ! [[ "$CYCLE_COUNT" =~ ^[0-9]+$ ]] || [[ "$CYCLE_COUNT" -lt 1 ]]; then
  echo "CYCLE_COUNT must be integer >= 1 (current: $CYCLE_COUNT)" >&2
  exit 1
fi

mkdir -p "$CYCLE_ROOT_DIR"

run_single_cycle() {
  local idx="$1"
  local ts
  ts="$(date +%Y%m%d-%H%M%S)"
  local run_dir="$CYCLE_ROOT_DIR/${RUN_PREFIX}-${ts}-${idx}"
  mkdir -p "$run_dir"

  echo "[cycle $idx/$CYCLE_COUNT] run_dir=$run_dir"

  RECORD_DIR="$run_dir" \
  RECORD_FILE="$run_dir/oracle-live.ditr.jsonl" \
  WORKLOAD_OUTPUT_FILE="$run_dir/workload-result.json" \
  RUN_REPORT_FILE="$run_dir/run-report.md" \
  PRECHECK_REPORT_FILE="$run_dir/oracle-precheck.log" \
  "$ROOT_DIR/tools/oracle_monitoring_stack.sh"
}

for ((i=1; i<=CYCLE_COUNT; i++)); do
  run_single_cycle "$i"
  if [[ "$i" -lt "$CYCLE_COUNT" ]]; then
    echo "[cycle $i/$CYCLE_COUNT] sleeping ${CYCLE_PAUSE_SECONDS}s"
    sleep "$CYCLE_PAUSE_SECONDS"
  fi
done

{
  echo "# Oracle Monitoring Cycles"
  echo
  echo "Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  for dir in "$CYCLE_ROOT_DIR"/${RUN_PREFIX}-*; do
    if [[ -d "$dir" ]]; then
      base="$(basename "$dir")"
      echo "- ${base}"
      [[ -f "$dir/run-report.md" ]] && echo "  - run-report: ${base}/run-report.md"
      [[ -f "$dir/workload-result.json" ]] && echo "  - workload-result: ${base}/workload-result.json"
      [[ -f "$dir/monitor-screen.txt" ]] && echo "  - monitor-screen: ${base}/monitor-screen.txt"
      [[ -f "$dir/oracle-live.ditr.jsonl" ]] && echo "  - recording: ${base}/oracle-live.ditr.jsonl"
    fi
  done
} >"$CYCLE_ROOT_DIR/index.md"

echo "Cycle execution completed"
echo "Cycle root: $CYCLE_ROOT_DIR"
echo "Index report: $CYCLE_ROOT_DIR/index.md"
