#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RECORD_DIR="${RECORD_DIR:-$ROOT_DIR/.dit}"
RECORD_FILE="${RECORD_FILE:-$RECORD_DIR/oracle-live.ditr.jsonl}"
THRESHOLDS_FILE="${THRESHOLDS_FILE:-$ROOT_DIR/docs/examples/thresholds.sample.json}"

WORKLOAD_JAR="${WORKLOAD_JAR:-$ROOT_DIR/.dit/bin/multi-db-load-tester-0.2.4.jar}"
WORKLOAD_URL="${WORKLOAD_URL:-https://github.com/YeonHongMin/Multidb-HA-Workload-Testing/releases/download/%23Multidb/multi-db-load-tester-0.2.4.jar}"
DBMS_BRIDGE_JAR="${DBMS_BRIDGE_JAR:-${ORACLE_BRIDGE_JAR:-$ROOT_DIR/.dit/bin/dit-dbms-bridge.jar}}"
JAVA_BIN="${JAVA_BIN:-java}"

DB_HOST="${DB_HOST:-192.168.0.172}"
DB_PORT="${DB_PORT:-1521}"
DB_SERVICE_NAME="${DB_SERVICE_NAME:-DEV}"
APP_USER="${APP_USER:-app}"
APP_PASSWORD="${APP_PASSWORD:-app}"
ORACLE_COLLECTOR_USER="${ORACLE_COLLECTOR_USER:-$APP_USER}"
ORACLE_COLLECTOR_PASSWORD="${ORACLE_COLLECTOR_PASSWORD:-$APP_PASSWORD}"
ORACLE_COLLECTOR_PORT="${ORACLE_COLLECTOR_PORT:-$DB_PORT}"
SYS_USER="${SYS_USER:-system}"
SYS_PASSWORD="${SYS_PASSWORD:-}"

THREAD_COUNT="${THREAD_COUNT:-20}"
WARMUP_SECONDS="${WARMUP_SECONDS:-5}"
LOAD_SECONDS="${LOAD_SECONDS:-120}"
MONITOR_SECONDS="${MONITOR_SECONDS:-120}"
WORKLOAD_MODE="${WORKLOAD_MODE:-full}"
TARGET_TPS="${TARGET_TPS:-0}"
TRUNCATE_BEFORE_TEST="${TRUNCATE_BEFORE_TEST:-true}"
MONITOR_INTERVAL="${MONITOR_INTERVAL:-1.0}"
SQLPLUS_PRECHECK="${SQLPLUS_PRECHECK:-false}"
PRECHECK_REPORT_FILE="${PRECHECK_REPORT_FILE:-$RECORD_DIR/oracle-precheck.log}"
WORKLOAD_OUTPUT_FORMAT="${WORKLOAD_OUTPUT_FORMAT:-none}"
WORKLOAD_OUTPUT_FILE="${WORKLOAD_OUTPUT_FILE:-$RECORD_DIR/workload-result.json}"
RUN_REPORT_FILE="${RUN_REPORT_FILE:-$RECORD_DIR/run-report.md}"
MONITOR_CAPTURE_FILE="${MONITOR_CAPTURE_FILE:-$RECORD_DIR/monitor-screen.txt}"
PRECHECK_MAX_SESSIONS_UTIL_PCT="${PRECHECK_MAX_SESSIONS_UTIL_PCT:-85}"
PRECHECK_MAX_PROCESSES_UTIL_PCT="${PRECHECK_MAX_PROCESSES_UTIL_PCT:-85}"
MAX_SHUTDOWN_WAIT="${MAX_SHUTDOWN_WAIT:-10}"
ENABLE_VSYSSTAT_DELTA="${ENABLE_VSYSSTAT_DELTA:-false}"
VSYSSTAT_DELTA_LOG_FILE="${VSYSSTAT_DELTA_LOG_FILE:-$RECORD_DIR/ora-vsysstat-delta.log}"

print_usage() {
  cat <<'EOF'
Oracle monitoring stack runner

Environment variables:
  DB_HOST, DB_PORT, DB_SERVICE_NAME
  APP_USER, APP_PASSWORD
  ORACLE_COLLECTOR_USER, ORACLE_COLLECTOR_PASSWORD, ORACLE_COLLECTOR_PORT(optional)
  ORACLE_COLLECTOR_CALL_TIMEOUT_MS(optional), ORACLE_COLLECTOR_TCP_CONNECT_TIMEOUT(optional)
  THREAD_COUNT, WARMUP_SECONDS, LOAD_SECONDS, MONITOR_SECONDS
  WORKLOAD_MODE(full|insert-only|select-only|update-only|delete-only|mixed)
  TARGET_TPS(0=unlimited), TRUNCATE_BEFORE_TEST(true|false)
  MONITOR_INTERVAL(for workload monitor output)
  SQLPLUS_PRECHECK(true|false), SYS_USER, SYS_PASSWORD(optional)
  WORKLOAD_OUTPUT_FORMAT(none|json|csv), WORKLOAD_OUTPUT_FILE
  PRECHECK_REPORT_FILE, RUN_REPORT_FILE, MONITOR_CAPTURE_FILE
  PRECHECK_MAX_SESSIONS_UTIL_PCT, PRECHECK_MAX_PROCESSES_UTIL_PCT
  ENABLE_VSYSSTAT_DELTA(true|false), VSYSSTAT_DELTA_LOG_FILE
  MAX_SHUTDOWN_WAIT(seconds)
  RECORD_FILE, THRESHOLDS_FILE
  WORKLOAD_JAR, WORKLOAD_URL
  DBMS_BRIDGE_JAR (or ORACLE_BRIDGE_JAR), JAVA_BIN

Example:
  APP_PASSWORD='app' WORKLOAD_MODE=mixed MONITOR_SECONDS=60 tools/oracle_monitoring_stack.sh
EOF
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

ensure_oracle_bridge_jar() {
  if [[ -f "$DBMS_BRIDGE_JAR" ]]; then
    return 0
  fi

  local repo_jar="$ROOT_DIR/java/oracle-bridge/target/dit-dbms-bridge.jar"
  if [[ -f "$repo_jar" ]]; then
    cp "$repo_jar" "$DBMS_BRIDGE_JAR"
    return 0
  fi

  echo "Missing DBMS bridge jar: $DBMS_BRIDGE_JAR" >&2
  echo "Build once on a build host with internet, then copy jar to app server:" >&2
  echo "  mvn -f $ROOT_DIR/java/oracle-bridge/pom.xml -DskipTests package" >&2
  echo "  cp $ROOT_DIR/java/oracle-bridge/target/dit-dbms-bridge.jar $DBMS_BRIDGE_JAR" >&2
  exit 1
}

validate_bool() {
  local value="$1"
  local name="$2"
  if [[ "$value" != "true" && "$value" != "false" ]]; then
    echo "$name must be true or false (current: $value)" >&2
    exit 1
  fi
}

sqlplus_connect_descriptor() {
  echo "(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=$DB_HOST)(PORT=$DB_PORT))(CONNECT_DATA=(SERVICE_NAME=$DB_SERVICE_NAME)))"
}

precheck_connectivity() {
  if command -v nc >/dev/null 2>&1; then
    if ! nc -z "$DB_HOST" "$DB_PORT" >/dev/null 2>&1; then
      echo "DB connectivity precheck failed: $DB_HOST:$DB_PORT is not reachable" >&2
      exit 1
    fi
  fi
}

precheck_sqlplus() {
  if [[ "$SQLPLUS_PRECHECK" != "true" ]]; then
    return 0
  fi

  if ! command -v sqlplus >/dev/null 2>&1; then
    echo "SQLPLUS_PRECHECK=true but sqlplus is not installed" >&2
    exit 1
  fi

  if [[ -z "$SYS_PASSWORD" ]]; then
    echo "SQLPLUS_PRECHECK=true requires SYS_PASSWORD" >&2
    exit 1
  fi

  sqlplus -s "$SYS_USER/$SYS_PASSWORD@$(sqlplus_connect_descriptor)" <<'EOF' >/dev/null
set heading off feedback off verify off
select 'ok' from dual;
exit;
EOF

  sqlplus -s "$SYS_USER/$SYS_PASSWORD@$(sqlplus_connect_descriptor)" <<EOF >"$PRECHECK_REPORT_FILE"
set pagesize 500 linesize 200 heading on feedback on verify off
prompt [oracle-precheck] host=${DB_HOST} port=${DB_PORT} service=${DB_SERVICE_NAME}
select instance_name, status from v\$instance;
select open_mode from v\$database;
select name, value from v\$parameter where name in ('sessions','processes') order by name;
select resource_name,
       current_utilization,
       max_utilization,
       limit_value,
       case
         when regexp_like(limit_value, '^[0-9]+$') then round((current_utilization / to_number(limit_value)) * 100, 2)
         else null
       end as utilization_pct
  from v\$resource_limit
 where resource_name in ('sessions', 'processes')
 order by resource_name;
select username, account_status from dba_users where username in (upper('${APP_USER}'), upper('${SYS_USER}')) order by username;
exit;
EOF

}

start_vsysstat_delta_monitor() {
  if [[ "$ENABLE_VSYSSTAT_DELTA" != "true" ]]; then
    return 0
  fi

  if ! command -v sqlplus >/dev/null 2>&1; then
    echo "ENABLE_VSYSSTAT_DELTA=true but sqlplus is not installed, skipping v$ delta monitor" >&2
    ENABLE_VSYSSTAT_DELTA=false
    return 0
  fi

  if [[ -z "$APP_PASSWORD" ]]; then
    echo "ENABLE_VSYSSTAT_DELTA=true requires APP_PASSWORD, skipping v$ delta monitor" >&2
    ENABLE_VSYSSTAT_DELTA=false
    return 0
  fi

  : >"$VSYSSTAT_DELTA_LOG_FILE"
  local connect_string
  connect_string="$APP_USER/$APP_PASSWORD@$(sqlplus_connect_descriptor)"
  local start_epoch
  start_epoch="$(date +%s)"

  (
    while true; do
      now_epoch="$(date +%s)"
      if [[ $((now_epoch - start_epoch)) -ge $MONITOR_SECONDS ]]; then
        break
      fi
      printf '[sample_start] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" >>"$VSYSSTAT_DELTA_LOG_FILE"
      sqlplus -s "$connect_string" @"$ROOT_DIR/awr/ora_sysstat_delta_live.sql" >>"$VSYSSTAT_DELTA_LOG_FILE" 2>&1 || true
      printf '[sample_end] %s\n\n' "$(date '+%Y-%m-%d %H:%M:%S')" >>"$VSYSSTAT_DELTA_LOG_FILE"
    done
  ) &

  VSYSSTAT_PID=$!
}

if [[ "${1:-}" == "--help" ]]; then
  print_usage
  exit 0
fi

require_command curl
require_command "$JAVA_BIN"
validate_bool "$TRUNCATE_BEFORE_TEST" "TRUNCATE_BEFORE_TEST"
validate_bool "$SQLPLUS_PRECHECK" "SQLPLUS_PRECHECK"
validate_bool "$ENABLE_VSYSSTAT_DELTA" "ENABLE_VSYSSTAT_DELTA"
if [[ "$WORKLOAD_OUTPUT_FORMAT" != "none" && "$WORKLOAD_OUTPUT_FORMAT" != "json" && "$WORKLOAD_OUTPUT_FORMAT" != "csv" ]]; then
  echo "WORKLOAD_OUTPUT_FORMAT must be none, json, or csv (current: $WORKLOAD_OUTPUT_FORMAT)" >&2
  exit 1
fi
precheck_connectivity
precheck_sqlplus

mkdir -p "$(dirname "$WORKLOAD_JAR")"
mkdir -p "$RECORD_DIR"
mkdir -p "$(dirname "$DBMS_BRIDGE_JAR")"
ensure_oracle_bridge_jar

export DBMS_BRIDGE_JAR
export ORACLE_BRIDGE_JAR="$DBMS_BRIDGE_JAR"

if [[ ! -f "$WORKLOAD_JAR" ]]; then
  echo "Downloading workload jar: $WORKLOAD_JAR"
  curl -L -o "$WORKLOAD_JAR" "$WORKLOAD_URL"
fi

echo "Using workload jar: $WORKLOAD_JAR"
"$JAVA_BIN" -jar "$WORKLOAD_JAR" --version >/dev/null

WORKLOAD_ARGS=(
  --db-type oracle
  --host "$DB_HOST"
  --port "$DB_PORT"
  --service-name "$DB_SERVICE_NAME"
  --user "$APP_USER"
  --password "$APP_PASSWORD"
  --thread-count "$THREAD_COUNT"
  --test-duration "$LOAD_SECONDS"
  --warmup "$WARMUP_SECONDS"
  --mode "$WORKLOAD_MODE"
  --target-tps "$TARGET_TPS"
  --monitor-interval "$MONITOR_INTERVAL"
)

if [[ "$TRUNCATE_BEFORE_TEST" == "true" ]]; then
  WORKLOAD_ARGS+=(--truncate)
fi

if [[ "$WORKLOAD_OUTPUT_FORMAT" != "none" ]]; then
  WORKLOAD_ARGS+=(--output-format "$WORKLOAD_OUTPUT_FORMAT" --output-file "$WORKLOAD_OUTPUT_FILE")
fi

echo "Starting Oracle workload generator"
"$JAVA_BIN" -Xms512m -Xmx1g -jar "$WORKLOAD_JAR" "${WORKLOAD_ARGS[@]}" >"$RECORD_DIR/workload.log" 2>&1 &
WORKLOAD_PID=$!

echo "Starting DIT monitor demo recorder"
"$JAVA_BIN" -jar "$DBMS_BRIDGE_JAR" \
  --command monitor \
  --host "$DB_HOST" \
  --port "$ORACLE_COLLECTOR_PORT" \
  --service-name "$DB_SERVICE_NAME" \
  --user "$ORACLE_COLLECTOR_USER" \
  --password "$ORACLE_COLLECTOR_PASSWORD" \
  --call-timeout-ms "${ORACLE_COLLECTOR_CALL_TIMEOUT_MS:-8000}" \
  --tcp-connect-timeout-seconds "${ORACLE_COLLECTOR_TCP_CONNECT_TIMEOUT:-5}" \
  --interval-seconds 1 \
  --record-file "$RECORD_FILE" \
  --capture-file "$MONITOR_CAPTURE_FILE" \
  >"$RECORD_DIR/dit-demo.log" 2>&1 &
DEMO_PID=$!

VSYSSTAT_PID=""
start_vsysstat_delta_monitor

cleanup() {
  kill -INT "$DEMO_PID" >/dev/null 2>&1 || true
  kill -INT "$WORKLOAD_PID" >/dev/null 2>&1 || true
  if [[ -n "$VSYSSTAT_PID" ]]; then
    kill -INT "$VSYSSTAT_PID" >/dev/null 2>&1 || true
  fi
}

wait_for_exit() {
  local pid="$1"
  local label="$2"
  local elapsed=0

  while kill -0 "$pid" >/dev/null 2>&1; do
    if [[ "$elapsed" -ge "$MAX_SHUTDOWN_WAIT" ]]; then
      echo "$label did not exit in ${MAX_SHUTDOWN_WAIT}s, sending TERM" >&2
      kill -TERM "$pid" >/dev/null 2>&1 || true
      sleep 1
      if kill -0 "$pid" >/dev/null 2>&1; then
        echo "$label still running, sending KILL" >&2
        kill -KILL "$pid" >/dev/null 2>&1 || true
      fi
      break
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done

  wait "$pid" >/dev/null 2>&1 || true
}

trap cleanup EXIT INT TERM HUP QUIT

echo "Running for ${MONITOR_SECONDS}s"
elapsed=0
while [[ "$elapsed" -lt "$MONITOR_SECONDS" ]]; do
  if ! kill -0 "$WORKLOAD_PID" >/dev/null 2>&1; then
    echo "Workload process exited early. Check $RECORD_DIR/workload.log" >&2
    break
  fi
  sleep 1
  elapsed=$((elapsed + 1))
done

echo "Stopping processes"
cleanup
wait_for_exit "$WORKLOAD_PID" "workload process"
wait_for_exit "$DEMO_PID" "DIT demo process"
if [[ -n "$VSYSSTAT_PID" ]]; then
  wait_for_exit "$VSYSSTAT_PID" "vsysstat delta monitor"
fi

"$JAVA_BIN" -jar "$DBMS_BRIDGE_JAR" \
  --command report \
  --record-file "$RECORD_FILE" \
  --workload-log "$RECORD_DIR/workload.log" \
  --monitor-log "$RECORD_DIR/dit-demo.log" \
  --workload-result "$WORKLOAD_OUTPUT_FILE" \
  --output "$RUN_REPORT_FILE"

echo "Done"
echo "Recording file: $RECORD_FILE"
echo "Workload log: $RECORD_DIR/workload.log"
echo "Monitor log: $RECORD_DIR/dit-demo.log"
echo "Monitor capture: $MONITOR_CAPTURE_FILE"
echo "DBMS bridge jar: $DBMS_BRIDGE_JAR"
if [[ -f "$PRECHECK_REPORT_FILE" ]]; then
  echo "Precheck report: $PRECHECK_REPORT_FILE"
fi
if [[ "$WORKLOAD_OUTPUT_FORMAT" != "none" ]]; then
  echo "Workload result: $WORKLOAD_OUTPUT_FILE"
fi
if [[ "$ENABLE_VSYSSTAT_DELTA" == "true" ]]; then
  echo "v$ sysstat delta log: $VSYSSTAT_DELTA_LOG_FILE"
fi
echo "Run report: $RUN_REPORT_FILE"
