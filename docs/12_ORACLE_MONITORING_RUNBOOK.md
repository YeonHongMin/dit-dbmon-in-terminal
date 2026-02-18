# DOC-12: Oracle Monitoring Runbook

## Goal

Run Oracle load + terminal monitoring together, and persist a replay file for post-incident analysis.

## Scope (current prototype)

- Oracle load is real (JDBC workload generator JAR).
- DIT `demo`/`play` UX is available with recording and incident navigation.
- Collector-based realtime Oracle ingestion uses one Java 8+ DBMS bridge JAR (`dit-dbms-bridge.jar`) with bundled JDBC drivers.

## Inputs

- Oracle host: `192.168.0.172`
- Oracle service name: `DEV`
- App account: `app / app`
- System account (optional admin ops): `system / manager`

## One-command stack run

```bash
chmod +x tools/oracle_monitoring_stack.sh

DB_HOST=192.168.0.172 \
DB_PORT=1521 \
DB_SERVICE_NAME=DEV \
APP_USER=app \
APP_PASSWORD=app \
ORACLE_COLLECTOR_USER=system \
ORACLE_COLLECTOR_PASSWORD=manager \
WORKLOAD_MODE=full \
TARGET_TPS=0 \
TRUNCATE_BEFORE_TEST=true \
WORKLOAD_OUTPUT_FORMAT=json \
WORKLOAD_OUTPUT_FILE=$HOME/.dit/workload-result.json \
THREAD_COUNT=20 \
WARMUP_SECONDS=5 \
LOAD_SECONDS=120 \
MONITOR_SECONDS=120 \
ENABLE_VSYSSTAT_DELTA=true \
DBMS_BRIDGE_JAR=$HOME/.dit/bin/dit-dbms-bridge.jar \
tools/oracle_monitoring_stack.sh
```

If execute permission is missing, run via bash:

```bash
bash tools/oracle_monitoring_stack.sh
```

## Repeated cycle run (archive per run)

```bash
chmod +x tools/oracle_monitoring_cycle.sh

CYCLE_COUNT=3 \
CYCLE_PAUSE_SECONDS=10 \
LOAD_SECONDS=120 \
MONITOR_SECONDS=120 \
MAX_SHUTDOWN_WAIT=10 \
tools/oracle_monitoring_cycle.sh
```

Cycle artifacts:

- root: `~/.dit/cycles/`
- per run: `oracle-run-<timestamp>-<index>/`
- index: `~/.dit/cycles/index.md`

Artifacts:

- Recording: `~/.dit/oracle-live.ditr.jsonl`
- Workload log: `~/.dit/workload.log`
- Monitor log: `~/.dit/dit-demo.log`
- Workload output (optional): `~/.dit/workload-result.json` or `.csv`
- Run report: `~/.dit/run-report.md`
- Realtime v$ delta log: `~/.dit/ora-vsysstat-delta.log`
- DBMS bridge jar: `~/.dit/bin/dit-dbms-bridge.jar`

DBMS bridge jar build (Java 8+):

```bash
tools/build_dbms_bridge.sh
```

Offline app-server flow:

- build jar once on an internet-enabled build host
- copy only JAR artifacts (`multi-db-load-tester-*.jar`, `dit-dbms-bridge.jar`) to app server
- run monitor/workload without pip dependency installation on app server

Collector credential split (recommended):

- workload schema actions: `APP_USER` / `APP_PASSWORD`
- monitoring v$ queries: `ORACLE_COLLECTOR_USER` / `ORACLE_COLLECTOR_PASSWORD`
- when collector env is omitted, collector falls back to `APP_USER` / `APP_PASSWORD`
- timeout tuning (optional): `ORACLE_COLLECTOR_CALL_TIMEOUT_MS`, `ORACLE_COLLECTOR_TCP_CONNECT_TIMEOUT`

Optional SQL*Plus precheck:

```bash
SQLPLUS_PRECHECK=true \
SYS_USER=system \
SYS_PASSWORD=manager \
PRECHECK_MAX_SESSIONS_UTIL_PCT=85 \
PRECHECK_MAX_PROCESSES_UTIL_PCT=85 \
PRECHECK_REPORT_FILE=$HOME/.dit/oracle-precheck.log \
tools/oracle_monitoring_stack.sh
```

Precheck report output path can be controlled with `PRECHECK_REPORT_FILE`.

Realtime `v$sysstat` delta capture (non-AWR):

```bash
ENABLE_VSYSSTAT_DELTA=true \
VSYSSTAT_DELTA_LOG_FILE=$HOME/.dit/ora-vsysstat-delta.log \
tools/oracle_monitoring_stack.sh
```

With this option, stack runner passes `--oracle-delta-log` to the demo app automatically, so the realtime sidebar includes latest `ora_sysstat` delta/sec and `ora_metric` gauges.
If Oracle env values are present (`DB_HOST`, `DB_SERVICE_NAME`, `APP_USER`, `APP_PASSWORD`) and `DBMS_BRIDGE_JAR` (or `ORACLE_BRIDGE_JAR`) is available, demo collector polling is enabled automatically with reconnect backoff.

Oracle delta scaling profile examples:

- Low-load profile: `docs/examples/thresholds.oracle.lowload.json`
- High-load profile: `docs/examples/thresholds.oracle.highload.json`

Apply a profile to stack run:

```bash
THRESHOLDS_FILE=docs/examples/thresholds.oracle.highload.json \
ENABLE_VSYSSTAT_DELTA=true \
tools/oracle_monitoring_stack.sh
```

The same threshold file is used for collector scaling (`oracle_delta_scaling`) when collector mode is active.

`oracle_delta_scaling` keys:

- `logical_reads_per_sec_to_k`: converts `Logical Reads Per Sec` to TUI `logical_io` K/s scale
- `redo_generated_per_sec_to_mb`: converts redo bytes/sec to MB/s for `redo_log`
- `db_wait_ratio_to_wait_ms`: converts `Database Wait Time Ratio` to displayed wait milliseconds
- `physical_io_ops_per_sec_to_mb`: converts physical read/write ops to `physical_io` MB/s-like scale
- `aas_to_blocked_sessions`: slope for deriving `blocked_sessions` from AAS overflow
- `aas_blocked_base`: AAS baseline before blocked-session estimate starts increasing

Direct one-shot `v$` delta check:

```bash
sqlplus app/app@"(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=192.168.0.172)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=DEV)))" \
  @awr/ora_sysstat_delta_live.sql
```

Expected script output markers (for parser compatibility):

- `STAT_NAME|DELTA_VALUE|DELTA_PER_SEC`
- `ORA_METRIC_GAUGE_SNAPSHOT`
- `METRIC_NAME|METRIC_UNIT_CSEC|VALUE`

## Replay and incident review

```bash
PYTHONPATH=src python3 -m dit_monitor play \
  --file ~/.dit/oracle-live.ditr.jsonl \
  --thresholds docs/examples/thresholds.sample.json
```

Playback keys:

- `space`: play/pause
- `left/right`: +/-1 frame
- `shift+left/right`: +/-10 frames
- `home/end`: first/last frame
- `n/p`: next/prev incident
- `g/x/z`: jump to peak wait/sql-error/blocked-session frames
- `alt+left/right`: history back/forward
- `[` `]` `v` `enter`: timeline cursor/anchor/apply

Realtime wallboard/session keys:

- `w`: wallboard on/off (single-screen view)
- `j` / `k`: select session
- `f`: follow blocking session
- wallboard includes Top Wait Events (Top 10; `wait_class` + event + ratio) and Top CPU SQLs (Top 10; `sql_id` + `plan_hash_value` + CPU%) panels
- session list includes wait event + `sql_id` (SQL text may be truncated)
- sidebar delta panel includes **Load Profile** (`DB Time/s`, `CPU/s`, `Wait/s`, `Logical Reads/Sec`, `Redo MB/Sec`, `SQL Exec/Sec`)

Load Profile unit semantics:

- `DB Time/s` and `CPU/s` are shown as per-second workload rates (from `v$sysmetric` gauges)
- `Wait/s` is derived as `max(0, DB Time/s - CPU/s)`
- `Logical Reads/Sec` is shown as reads per second (raw)
- `Redo MB/Sec` is normalized from redo bytes to MB/s

Wait-event noise filtering:

- collector supports `ORACLE_WAIT_EXCLUDE` (comma-separated patterns)
- `*` wildcard is supported (`SQL*Net message from client` style)
- example:

```bash
ORACLE_WAIT_EXCLUDE="SQL*Net message from client,log file sync" \
ENABLE_VSYSSTAT_DELTA=true \
tools/oracle_monitoring_stack.sh
```

## Workload JAR direct run (manual)

```bash
curl -L -o /tmp/multi-db-load-tester-0.2.4.jar \
  "https://github.com/YeonHongMin/Multidb-HA-Workload-Testing/releases/download/%23Multidb/multi-db-load-tester-0.2.4.jar"

java -Xms512m -Xmx1g -jar /tmp/multi-db-load-tester-0.2.4.jar \
  --db-type oracle \
  --host 192.168.0.172 \
  --port 1521 \
  --service-name DEV \
  --user app \
  --password app \
  --truncate \
  --thread-count 20 \
  --test-duration 60 \
  --warmup 5
```

## Notes

- Avoid committing credentials in scripts or markdown.
- If SSH route is unavailable but direct DB port is open, run workload/monitor from reachable host.
- Realtime load interpretation should use `v$sysstat` delta/sec and `v$sysmetric` gauges.
- AWR snapshots are excluded from realtime troubleshooting flow in this runbook.
- Collector path dependency: Java runtime 1.8+ and Oracle bridge JAR (no app-server pip install required).
- Realtime delta source should use `v$` views (`ora_sysstat` + `ora_metric`) instead of AWR snapshots.

## Validation checklist

- `pytest` returns pass
- `bash -n tools/oracle_monitoring_stack.sh` passes
- `bash -n tools/oracle_monitoring_cycle.sh` passes
- run generates: recording, workload log, monitor log, run-report
- cycle mode generates `index.md`

Short smoke example (validated):

```bash
DB_HOST=192.168.0.172 \
DB_PORT=1521 \
DB_SERVICE_NAME=DEV \
APP_USER=app \
APP_PASSWORD=app \
WORKLOAD_MODE=select-only \
THREAD_COUNT=8 \
WARMUP_SECONDS=2 \
LOAD_SECONDS=15 \
MONITOR_SECONDS=15 \
WORKLOAD_OUTPUT_FORMAT=json \
ENABLE_VSYSSTAT_DELTA=false \
bash tools/oracle_monitoring_stack.sh
```

Observed outcome:

- recording/log/report artifacts generated successfully
- monitor process may require TERM fallback at shutdown on some terminals

## Generated run report

`tools/generate_monitoring_report.py` is executed by the stack script at shutdown.

- input: recording + workload log + monitor log
- optional input: workload JSON output + precheck report
- output: markdown summary (`RUN_REPORT_FILE`, default `~/.dit/run-report.md`)

Report includes:

- recording frame count and metadata
- workload summary (TPS/transactions/errors/latency)
- workload series insights (peak realtime TPS, peak P95 timestamp)
- precheck highlights (resource utilization warning lines, if available)

Recording/playback provenance:

- each frame stores `collector_state` (`ON`/`OFF`/`ERR`/`NA`)
- each frame stores `data_sources` (`metrics`, `sessions`, `wait_events`, `sql_hotspots`)
- playback sidebar shows recorded source hints for forensic traceability
- run report summarizes provenance source counts for all four source groups and collector state counts
- run report includes latest Load Profile snapshot (`DBT/CPU/WT`, `Logical Reads/Sec`, `Redo MB/Sec`, `SQL Exec/Sec`)
- run report includes replay reproducibility status (`collector` frame counts for sessions/waits/sql)

## Workload profile presets

- `WORKLOAD_MODE=full`: CRUD cycle baseline
- `WORKLOAD_MODE=insert-only`: write-heavy load
- `WORKLOAD_MODE=select-only`: read-heavy load
- `WORKLOAD_MODE=mixed`: mixed load similar to application traffic

## Design/roadmap reference

- DBMS-specific monitoring targets: `docs/13_DBMS_MONITORING_TARGETS.md`
