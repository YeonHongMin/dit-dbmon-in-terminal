# DOC-14: DB Monitoring Competitive Gap Analysis (GitHub/GitLab/SNS)

## Scope

This note compares current DIT capabilities with patterns found in open-source monitoring/query toolkits and community operator requests.

Primary focus:

- monitoring items
- query patterns
- triage usability (session/wait/sql)
- replay/report forensic usefulness

## Research Sweep (GitHub/GitLab/SNS)

This document includes findings from multi-source research sweeps across:

- GitHub/GitLab repositories (Oracle, PostgreSQL, MySQL, SQL Server monitoring/query toolkits)
- community discussions (HN/Reddit/blog/forum style operator pain points)
- dashboard/exporter implementations and SQL script collections

Representative source groups:

- Oracle DBA tooling: `tanelpoder/tpt-oracle`, `fatdba/Oracle-Database-Scripts`, `jkstill/oracle-script-lib`, `LucaCanali/Oracle_DBA_scripts`
- SQL Server/PostgreSQL/MySQL diagnostic query sets and CLIs used for parity reference

## Current Baseline (DIT)

현재 Java/Lanterna 기반 TUI 구현:

- Lanterna Screen 기반 실시간 대시보드 (Load Profile + Top Waits + Sessions + Top SQL)
- Top Wait Events (Top 12, V$SYSTEM_EVENT 델타 기반 실시간 반영)
- Top CPU SQLs (Top 15, `sql_id`, `plan_hash_value`, CPU time 기준, SQL text truncated)
- Active Session 목록 + blocker_sid 표시 + 스크롤
- Load Profile (13개 메트릭 + Unicode Sparkline 이력)
  - AAS, DB Time/s, CPU/s, Wait/s, SQL Exec/s, Logical Reads/s
  - Physical R/W/s, Physical R/W MB/s, Redo MB/s
  - Commits/s, Rollbacks/s, Txn/s, Parse/Hard Parse/s, Buffer Cache Hit%
- JSONL 녹화 (monitor 명령어 `--record-file`)
- Markdown 리포트 생성 (report 명령어)
- Per-query 에러 격리 (collectAll)
- 자기 세션 필터링 (v$session.program = 'dit-bridge')

## External Findings (High Signal)

### Oracle monitoring/query patterns

- **Tanel Poder Snapper / tpt-oracle** uses ASH-like sampling from `v$session`/`gv$session` and emphasizes `sql_id + event + wait_class` grouping for top triage.
  - https://github.com/tanelpoder/tpt-oracle/blob/master/snapper.sql
- **Oracle DBA scripts (fatdba, jkstill, LucaCanali)** repeatedly use:
  - wait filtering of idle events,
  - wait-class/event aggregation,
  - microsecond wait time normalization,
  - session wait rows with `sql_id` + truncated SQL text.
  - https://github.com/fatdba/Oracle-Database-Scripts
  - https://github.com/jkstill/oracle-script-lib
  - https://github.com/LucaCanali/Oracle_DBA_scripts

### PostgreSQL/MySQL/SQL Server reference query patterns

- `pg_stat_activity` long-running/lock-focused slices (duration-based triage) are common in ops scripts and CLIs.
  - https://github.com/supabase/cli/blob/develop/internal/inspect/long_running_queries/long_running_queries.sql
- MySQL top digest patterns strongly center on `events_statements_summary_by_digest` plus count/time/rows metrics.
  - https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/receiver/mysqlreceiver
- SQL Server toolkits prioritize `sys.dm_exec_query_stats` + query text/plan joins and percent-contribution columns (CPU/elapsed/reads).
  - https://github.com/BrentOzarULTD/SQL-Server-First-Responder-Kit
  - https://github.com/microsoft/tigertoolbox

### Community/SNS recurring asks (summarized)

Across public operator threads and tooling discussions, recurring demands are:

- stable identifiers first (`sql_id`, pid/sid/session id)
- lock trees/blocker chains as first-class panel
- top waits grouped by class and filtered for idle noise
- top SQL with percent-of-total and optional plan/drill-down path
- alert noise reduction (state change only)
- compact reports with latest triage snapshot + traceability metadata

Community-ranked operator pain points (latest sweep):

1. Alert fatigue / noisy alarms (state-change-only + dedup + cooldown needed)
2. Missing lock tree and root-blocker visibility for fast triage
3. Wait-event noise filtering (idle/client waits should be suppressible)
4. Stable query identifiers + plan context (`sql_id`, plan hash) in top SQL panels
5. Top SQL percent contribution (`%CPU`, `%Elapsed`) over absolute values only
6. Compact but actionable reports (latest load profile + provenance + triage hint)

Implication for DIT:

- Lock-tree view and alert dedup/cooldown remain the highest-impact next UX improvements.
- Top SQL에 `%CPU`/`%Elapsed` 비율 컬럼 추가 고려.

## Gap Matrix (Current vs Desired)

1) **Wait normalization and filtering depth**

- Current: Top 12 waits with `wait_class` + event, V$SYSTEM_EVENT 델타 기반 실시간 반영.
- Gap: no optional wait histogram view, no configurable idle event filtering.
- Improvement: optional latency histogram mode, wait class filter 설정.

2) **Top SQL context richness**

- Current: `sql_id`, `plan_hash_value`, cpu_time, executions, buffer_gets, disk_reads, rows_processed, truncated text.
- Gap: no plan drill path, no `%CPU`/`%Elapsed` percent columns.
- Improvement: include `%CPU`/`%Elapsed` contribution columns, optional plan snapshot toggle.

3) **Session triage depth**

- Current: active session list + `blocking_sid` + `sql_id` + event + wait_class + seconds_in_wait.
- Gap: no explicit lock tree panel (multi-level), no event parameter (`p1/p2/p3`) view.
- Improvement: add lock tree mini-panel and optional per-session wait parameter details.

4) **Replay forensic payload completeness**

- Current: JSONL 녹화 (monitor 명령어), Markdown 리포트 (report 명령어).
- Gap: no explicit source timestamp drift indicator, no triage hint in reports.
- Improvement: add `collector_sample_ts` / `sample_age_ms` into frame metadata, add `Triage Hint` section.

5) **Cross-DB parity roadmap clarity**

- Current: Oracle path only (Java/Lanterna TUI).
- Gap: no MySQL/PostgreSQL/SQL Server support.
- Improvement: create parity checklist: `{Load Profile, Sessions, Top Waits, Top SQL, Playback, Report}` for each DB engine.

## Recommended Improvement Backlog (Priority)

### P0 (next)

1. Session blocker tree compact panel (multi-level lock chain visualization)
2. ASH-style top activity mode (`sql_id + event + wait_class` time-windowed ranking)
3. Top SQL `%CPU` / `%Elapsed` percent contribution columns

### P1

4. Top SQL plan drill path (plan snapshot/explain hook)
5. Wait histogram mode for event latency distribution
6. Report `Triage Hint` section (single actionable pointer based on strongest anomaly)

### P2

7. Cross-DB parity rollout (MySQL/PostgreSQL/SQL Server) with same triage primitives

## Notes for Implementation

- keep `sql_id` always visible; truncate text only
- keep unit semantics explicit (`/s`, `MB/s`, `%`)
- Java 8 호환 유지 (var 사용 금지)
- Lanterna Screen layer만 사용 (GUI toolkit 금지)
