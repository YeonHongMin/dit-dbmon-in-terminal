# DOC-13: DBMS Monitoring Targets (Deep Research Synthesis)

## Goal

Define concrete, implementable single-screen monitoring targets for Oracle/MySQL/PostgreSQL/SQL Server.

## Research References

- btop layout/interaction patterns: https://github.com/aristocratos/btop
- glances plugin and multi-pane concepts: https://github.com/nicolargo/glances
- lazysql TUI navigation model: https://github.com/jorgerojas26/lazysql
- Oracle wait/session docs: https://docs.oracle.com/en/database/oracle/oracle-database/23/refrn/V-SESSION_WAIT.html
- PostgreSQL monitoring docs: https://www.postgresql.org/docs/16/monitoring.html
- MySQL Performance Schema docs: https://dev.mysql.com/doc/refman/8.4/en/performance-schema.html
- SQL Server DMV references (examples):
  - https://github.com/DataDog/integrations-core/tree/master/sqlserver

## Single-Screen Wallboard Target (Common)

- Upper area: 8 key sparklines (CPU, sessions, waits, I/O, blockers, SQL throughput/errors)
- Right panel: session list + selected session wait chain/blocker path
- Footer: keymap always visible
- Playback: same screen with timeline + incident jump

## DBMS-Specific Metric Priorities

### Oracle

- Top waits (`v$active_session_history`)
- Active/blocked sessions (`v$session`)
- Top SQL elapsed/cpu (`v$sql`, ASH join)
- DB time and physical I/O
- `V$SYSSTAT` counters should be interpreted as delta/sec (`ora_sysstat` context)
- `V$SYSMETRIC` gauges (`ora_metric`) should be used as immediate workload health signals

Operational helper script:

- `awr/ora_sysstat_delta_live.sql` (`v$sysmetric` 기반 delta/sec-compatible output + workload gauge snapshot)

### MySQL

- Threads running / connections
- Lock wait and row lock contention
- statement digest top SQL (`performance_schema.events_statements_summary_by_digest`)
- InnoDB read/write pressure

### PostgreSQL

- Active sessions + wait_event (`pg_stat_activity`)
- Long-running and idle-in-transaction sessions
- top SQL (`pg_stat_statements`)
- deadlocks/checkpoint I/O signals

### SQL Server

- Blocking sessions and wait categories (`sys.dm_exec_requests`, `sys.dm_os_wait_stats`)
- batch requests/sec, user connections
- top SQL elapsed (`dm_exec_query_stats` + sql_text)
- memory/buffer pressure indicators

## Implementation Backlog (Prioritized)

1. **P0**: Wallboard metric profile per DBMS (8-core metrics on one screen)
2. **P0**: Session drill-down (select session, follow blocker/wait chain)
3. **P0**: Incident timeline jump and replay consistency on same layout
4. **P1**: Per-DB wait category summary box (Top 10 waits)
5. **P1**: Top SQL mini-table (sql_id/query hash + cpu + executions + truncated text)
6. **P1**: Severity badges + threshold profiles per DBMS
7. **P2**: Collector integration path replacing synthetic metrics in demo mode

## Done in current prototype

- Single-screen wallboard toggle (`w`) with 8 metric charts
- Session list panel (`j/k`) and blocker-follow (`f`)
- Playback timeline/incident navigation (arrow keys, `n/p`, history)
- Top wait events panel and SQL hotspot panel rendered on one screen
- Oracle wallboard now surfaces Load Profile (`DB Time/s`, `CPU/s`, `Wait/s`, `Logical Reads/Sec`, `Redo MB/Sec`, `SQL Exec/Sec`)
- Run report now includes collector provenance/source-count summary for replay traceability
- Playback forensic peak jumps (`g/x/z`) for wait/sql-error/blocked-session spikes

## Next Step Definition

- Replace synthetic session generation with collector-fed sessions while preserving current keybindings and wallboard structure.
- See `docs/14_DB_MONITORING_COMPETITIVE_GAP_ANALYSIS.md` for cross-solution gap matrix and prioritized improvement backlog.
