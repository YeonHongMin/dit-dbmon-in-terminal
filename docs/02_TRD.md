# DOC-2: TRD (기술 요구사항 정의서) — SDD 수준 상세 설계

> **Status**: Archived. 이 문서는 Python/Textual/asyncio 기반 설계이며, 현재 DIT는 Java 8+ / Lanterna / JDBC 기반으로 재작성되었다. DBMS별 메트릭 카탈로그(2.2.2)는 향후 DBMS 확장 시 참고 가치가 있다.

## 2.1 시스템 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│                    DIT — System Architecture          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─ TUI Presentation Layer (Textual) ────────────────────────┐ │
│  │  ┌──────────────────────────┐ ┌──────────────────────────┐│ │
│  │  │ Chart Stack Panel        │ │ Sidebar Panel            ││ │
│  │  │ ┌──────────────────────┐ │ │ ┌──────────────────────┐ ││ │
│  │  │ │ TimeSeries Chart 1   │ │ │ │ Instance Selector    │ ││ │
│  │  │ ├──────────────────────┤ │ │ │ (Radio List of DBs)  │ ││ │
│  │  │ │ TimeSeries Chart 2   │ │ │ ├──────────────────────┤ ││ │
│  │  │ ├──────────────────────┤ │ │ │ Current Values       │ ││ │
│  │  │ │ TimeSeries Chart N   │ │ │ │  CPU     85.2%       │ ││ │
│  │  │ └──────────────────────┘ │ │ │  Sessions  42        │ ││ │
│  │  └──────────────────────────┘ │ │  DB Time  3.2s       │ ││ │
│  │  ┌──────────────────────────┐ │ ├──────────────────────┤ ││ │
│  │  │ Metric Group Tabs        │ │ │ Shortcut Keys        │ ││ │
│  │  │ [Overview] Sessions I/O  │ │ └──────────────────────┘ ││ │
│  │  └──────────────────────────┘ └──────────────────────────┘│ │
│  └───────────────────────────────────────────────────────────┘ │
│                              │                                  │
│  ┌─ Metric Registry ────────────────────────────────────────┐  │
│  │  ┌──────────┐┌──────────┐┌──────────┐┌──────────┐       │  │
│  │  │Oracle    ││MySQL     ││PostgreSQL││SQL Server│       │  │
│  │  │MetricDefs││MetricDefs││MetricDefs││MetricDefs│       │  │
│  │  └──────────┘└──────────┘└──────────┘└──────────┘       │  │
│  │  (DBMS별 메트릭 그룹 정의, SQL 쿼리, 차트 유형, 임계치)    │  │
│  └───────────────────────────────────────────────────────────┘  │
│                              │                                  │
│  ┌─ Core Service Layer ─────────────────────────────────────┐  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────────┐  │  │
│  │  │Metric    │ │Alert     │ │History   │ │Config      │  │  │
│  │  │Aggregator│ │Engine    │ │Manager   │ │Manager     │  │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └────────────┘  │  │
│  └───────────────────────────────────────────────────────────┘  │
│                              │                                  │
│  ┌─ Async Collector Layer (asyncio) ────────────────────────┐  │
│  │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐            │  │
│  │  │Oracle  │ │MySQL   │ │PgSQL   │ │MSSQL   │            │  │
│  │  │Collector│ │Collector│ │Collector│ │Collector│            │  │
│  │  └───┬────┘ └───┬────┘ └───┬────┘ └───┬────┘            │  │
│  └──────┼──────────┼──────────┼──────────┼──────────────────┘  │
│         │          │          │          │                      │
│  ┌──────┼──────────┼──────────┼──────────┼──────────────────┐  │
│  │      ▼          ▼          ▼          ▼                   │  │
│  │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐            │  │
│  │  │Java 8+ │ │aiomysql│ │asyncpg │ │aioodbc │            │  │
│  │  │Bridge  │ │        │ │        │ │pymssql │            │  │
│  │  └───┬────┘ └───┬────┘ └───┬────┘ └───┬────┘            │  │
│  │      │   Driver/Connector Layer       │                   │  │
│  └──────┼──────────┼──────────┼──────────┼──────────────────┘  │
│         ▼          ▼          ▼          ▼                      │
│     ┌──────┐   ┌──────┐   ┌──────┐   ┌──────┐                │
│     │Oracle│   │MySQL │   │PgSQL │   │MSSQL │                │
│     │ DB   │   │ DB   │   │ DB   │   │ DB   │                │
│     └──────┘   └──────┘   └──────┘   └──────┘                │
│                                                                 │
│  ┌─ Local Storage ──────────────────────────────────────────┐  │
│  │  ┌────────────────┐  ┌────────────────────────────────┐  │  │
│  │  │ SQLite          │  │ YAML Config                    │  │  │
│  │  │(Metric History) │  │(connections, thresholds, etc.) │  │  │
│  │  └────────────────┘  └────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌─ External Integration (Optional) ────────────────────────┐  │
│  │  ┌──────┐  ┌──────┐  ┌──────────┐  ┌────────────────┐   │  │
│  │  │Slack │  │Email │  │PagerDuty │  │Webhook (Custom)│   │  │
│  │  └──────┘  └──────┘  └──────────┘  └────────────────┘   │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

## 2.2 핵심 설계: Metric Registry

Metric Registry는 Grafana 대시보드/Exporter의 메트릭 정의를 TUI 위젯으로 매핑하는 핵심 컴포넌트다. DBMS 유형별로 메트릭 그룹과 수집 SQL을 정의한다.

### 2.2.1 데이터 파이프라인

```
Exporter SQL Query  →  Async Collector  →  Metric Aggregator  →  Chart Stack
(oracle_custom_      (Java bridge CLI)    (시계열 버퍼)          (plotext 렌더)
 metrics.toml)
```

### 2.2.2 DBMS별 메트릭 그룹 카탈로그

#### Oracle (Source: Grafana "Oracle One Instance" + oracle_custom_metrics.toml)

| 메트릭 그룹 | TUI 차트 | 수집 소스 | 갱신 주기 |
|-------------|---------|-----------|-----------|
| **OS** | CPU %, Memory % | `V$OSSTAT` (ora_cpustat, ora_memstat) | 5s |
| **Time Model & Wait** | DB Time, CPU Time, Active Sessions, Wait Events | `V$SYSSTAT`, `V$SYSTEM_EVENT`, `V$WAITCLASSMETRIC` | 5s |
| **Logical I/O** | Logical Reads, Block Gets, Consistent Gets | `V$SYSSTAT` (ora_sysstat) | 5s |
| **Physical I/O** | Physical Reads/Writes (bytes, blocks) | `V$SYSSTAT` (ora_sysstat) | 5s |
| **Tablespace** | Data/Temp/Undo size & growth | DBA_TABLESPACE_USAGE_METRICS | 60s |
| **Hit Ratio** | Buffer/Library/Row Cache, PGA, Soft Parse | `V$SYSMETRIC` (ora_metric) | 5s |
| **Sessions** | Active/Blocked sessions, Wait info, SQL info | `V$SESSION` + `V$PROCESS` (ora_wait_sess) | 5s |
| **Top SQLs** | CPU Time, Elapsed, Disk Reads, Buffer Gets | `V$SQL` + `V$ACTIVE_SESSION_HISTORY` (ora_top_cpu_sqls) | 10s |
| **RAC** | GC Wait Events, CR/Current Blocks | `V$SYSSTAT` (gc_*), `V$DLM_MISC` | 5s |

#### MySQL / MariaDB (Source: Grafana "MySQL Monitor" + mysql_custom.collector.yml)

| 메트릭 그룹 | TUI 차트 | 수집 소스 | 갱신 주기 |
|-------------|---------|-----------|-----------|
| **SQL Activity** | SQL Time, Executions, Slow queries | `events_statements_summary_by_digest` | 5s |
| **Connections** | Threads running, Current conn, Lock Wait | `SHOW GLOBAL STATUS` | 5s |
| **Handler** | Handler read (scan/index), Insert/Update/Delete | `SHOW GLOBAL STATUS` | 5s |
| **InnoDB Activity** | Logical reads, Disk reads/writes, Fsyncs | `information_schema.innodb_metrics` | 5s |
| **Sort & Temp** | Sort merge passes, Tmp disk tables, Open tables | `SHOW GLOBAL STATUS` | 5s |
| **InnoDB Buffer** | Buffer pool hit %, free/data/dirty pages | `SHOW GLOBAL STATUS` | 5s |
| **Redo Log** | Log waits, Log writes, Checkpoint Age | `SHOW GLOBAL STATUS` | 5s |
| **Replication** | Slave IO/SQL running, Slave Lag | `SHOW SLAVE STATUS` | 5s |
| **Top SQLs** | Digest: time, lock time, rows, errors | `events_statements_summary_by_digest` | 10s |

#### PostgreSQL (Source: Grafana "PostgreSQL Monitor" + queries.yaml)

| 메트릭 그룹 | TUI 차트 | 수집 소스 | 갱신 주기 |
|-------------|---------|-----------|-----------|
| **SQL Activity** | SQL Time, Executions, rows fetched | `pg_stat_statements` | 5s |
| **Connections** | Active, idle, idle-in-transaction, max | `pg_stat_activity` | 5s |
| **Rows** | Fetched, inserted, updated, deleted | `pg_stat_database` | 5s |
| **Data Blocks** | blk_read_time, blk_write_time, hit ratio | `pg_stat_database` | 5s |
| **Transactions** | Commit, rollback, deadlocks, lock count | `pg_stat_database` | 5s |
| **WAL** | WAL Size, segments, archived/failed files | `pg_stat_archiver` | 10s |
| **Checkpoint** | Write time, sync time, buffers | `pg_stat_bgwriter` | 10s |
| **Top SQLs** | Calls, seconds, rows, block r/w time | `pg_stat_statements` | 10s |

#### SQL Server (Source: Grafana "SQL Server One Instance" + mssql_custom.collector.yml)

| 메트릭 그룹 | TUI 차트 | 수집 소스 | 갱신 주기 |
|-------------|---------|-----------|-----------|
| **OS** | DB CPU %, OS Memory %, Disk I/O stall | `dm_os_sys_memory`, `dm_os_process_memory` | 5s |
| **General** | Batch Requests/sec, User Connections, Blocked | `dm_os_performance_counters` | 5s |
| **Access Method** | Full Scan, Range Scan, Table Lock Escalations | `dm_os_performance_counters` | 5s |
| **Buffer Manager** | Page lookups/reads/writes, Lazy writes | `dm_os_performance_counters` | 5s |
| **Memory Clerk** | SQLBUFFERPOOL, CACHESTORE sizes | `dm_os_memory_clerks` | 10s |
| **Hit Ratio** | Buffer Cache, Plan Cache, Catalog Cache | `dm_os_performance_counters` | 5s |
| **Wait Info** | Wait Type Time (CPU, Lock, Latch, I/O, ...) | `dm_os_wait_stats` | 5s |
| **Locks & Latches** | Deadlocks, Lock Timeout, Latch Wait | `dm_os_performance_counters` | 5s |
| **Sessions** | Active sessions, blocking, wait details | `sys.dm_exec_sessions` + `dm_exec_requests` | 5s |
| **Top SQLs** | CPU, elapsed, logical/physical reads, writes | `dm_exec_query_stats` | 10s |

## 2.3 권장 기술 스택

### 2.3.1 TUI 프레임워크

| 항목 | 내용 |
|------|------|
| **선택 기술** | **Textual** (Python) |
| **선택 이유** | CSS 기반 레이아웃, 위젯 시스템, 반응형 TUI, Rich 통합. s-tui 스타일 세로 차트 스택 구현에 적합 |
| **대안** | **urwid** — 저수준 제어에 강하나 위젯 부족 |
| **벤더 락인 리스크** | 낮음 — 오픈소스(MIT), UI Layer만 교체 가능 |

### 2.3.2 차트/그래프 엔진

| 항목 | 내용 |
|------|------|
| **선택 기술** | **plotext** + **Rich Sparkline** + **Textual Canvas** |
| **선택 이유** | plotext는 터미널에서 시계열 바 차트/라인 차트를 렌더링. s-tui와 동일한 스타일 구현 가능 |
| **대안** | **asciichartpy** — 라인 차트만 지원 |

### 2.3.3 백엔드

| 항목 | 내용 |
|------|------|
| **선택 기술** | **Python 3.11+ / asyncio** |
| **선택 이유** | 4종 DB 드라이버가 모두 Python 지원, Textual이 asyncio 기반 |

### 2.3.4 데이터베이스 커넥터

| 대상 DB | 선택 드라이버 | 대안 | 비고 |
|---------|--------------|------|------|
| Oracle | **Java 8+ bridge JAR (JDBC thin)** | python-oracledb | 오프라인 앱 서버/무설치 배포 대응 |
| MySQL | **aiomysql** | mysql-connector-python | asyncio 네이티브 |
| PostgreSQL | **asyncpg** | psycopg3 (async) | 최고 성능 async 드라이버 |
| SQL Server | **aioodbc** + FreeTDS | pymssql | ODBC 기반 |

### 2.3.5 로컬 저장소

| 항목 | 내용 |
|------|------|
| **선택 기술** | **SQLite** (메트릭 히스토리) + **YAML** (설정 + 메트릭 정의) |
| **선택 이유** | 외부 DB 불필요, Python 표준 라이브러리 포함 |

### 2.3.6 배포

| 항목 | 내용 |
|------|------|
| **선택 방식** | **PyPI** (`pip install dit-monitor`) + **Docker** |
| **예상 비용** | 0원 (클라이언트 도구) |

### 2.3.7 외부 알림

| 서비스 | 용도 | 대체 |
|--------|------|------|
| Slack Webhook | 알림 발송 | Teams Webhook |
| SMTP | 이메일 알림 | SendGrid |
| PagerDuty API | 온콜 에스컬레이션 | OpsGenie |
| Custom Webhook | 범용 알림 | — |

## 2.4 비기능적 요구사항

### 2.4.1 성능

- TUI 렌더링 주기: 최소 1초, 최대 60초 (설정 가능)
- 단일 DB 지표 수집 시간: 500ms 이하
- 키 입력 ~ 화면 반응 지연: 100ms 미만
- 등록 가능 인스턴스: 최대 50개
- 메모리 사용: 10MB(기본) + 인스턴스당 4MB 이하

### 2.4.2 보안

- DB 접속 비밀번호: 환경변수 또는 키링(keyring) 라이브러리 사용 (YAML 평문 금지)
- 선택적 SSH 터널링 지원 (paramiko)
- 모니터링 계정: 최소 권한 (READ-ONLY)
- SQLite 히스토리: 파일 퍼미션 600
- 설정 파일: 파일 퍼미션 640

### 2.4.3 확장성

- 새 DBMS 추가: Collector 인터페이스 + Metric Registry 정의 파일(YAML) 추가
- 커스텀 메트릭: YAML에서 사용자 정의 SQL 쿼리 추가 가능
- 커스텀 알림 채널: Notifier 인터페이스 구현으로 확장

### 2.4.4 가용성

- 대상 DB 접속 실패 시 사이드바에 OFFLINE 표시, 다른 인스턴스 전환 가능
- 자동 재접속 (지수 백오프: 1s → 2s → 4s → 최대 60s)
- 수집 타임아웃: 3초 (설정 가능), 초과 시 TIMEOUT 표시

## 2.5 SQLite 스키마 설계

**설계 원칙:**
- 시계열 데이터 최적화 (타임스탬프 기반)
- 자동 집계: Raw(24h) → 5min(7d) → 1h(30d) → 1d(365d) → 삭제
- WAL 모드로 읽기/쓰기 동시성 확보

**인덱싱 전략:**
- Primary: `(instance_id, collected_at)`
- Secondary: `(metric_name, collected_at)`
- Partial: `collected_at > datetime('now', '-24 hours')`

## 2.6 Collector 인터페이스 설계

```python
class BaseCollector(ABC):
    """DBMS별 Collector의 추상 기반 클래스"""
    
    @abstractmethod
    async def connect(self, config: DBConfig) -> None: ...
    
    @abstractmethod
    async def collect_metrics(self, group: str) -> dict[str, MetricValue]: ...
    
    @abstractmethod
    async def disconnect(self) -> None: ...
    
    @abstractmethod
    def get_metric_groups(self) -> list[MetricGroup]: ...
```

각 DBMS Collector는 Exporter의 SQL 쿼리를 수집 커넥터로 직접 실행한다:
- `OracleCollector` → Java 8+ bridge JAR(JDBC thin)로 `oracle_custom_metrics.toml` 기반 쿼리 실행
- `MySQLCollector` → `mysql_custom.collector.yml`의 SQL을 `aiomysql`로 실행
- `PostgreSQLCollector` → `queries.yaml`의 SQL을 `asyncpg`로 실행
- `MSSQLCollector` → `mssql_custom.collector.yml`의 SQL을 `aioodbc`로 실행

## 2.7 접근제어·권한 모델

| 역할 | 권한 | 설명 |
|------|------|------|
| viewer | 대시보드 조회, 히스토리 조회 | 기본 권한 |
| operator | viewer + 임계치 변경, 알림 설정 | 운영자 |
| admin | operator + DB 인스턴스 추가/삭제, 세션 Kill | 관리자 |

## 2.8 데이터 생명주기

| 단계 | 보존 | 해상도 | 처리 |
|------|------|--------|------|
| Raw | 24시간 | 수집 주기(1~5초) | 실시간 차트 |
| 5분 집계 | 7일 | 5분 avg/max/min | 히스토리 뷰 |
| 1시간 집계 | 30일 | 1시간 avg/max/min | 추이 분석 |
| 1일 집계 | 365일 | 1일 avg/max/min | 연간 추이 |
| 삭제 | 365일 초과 | — | 자동 퍼지 |
