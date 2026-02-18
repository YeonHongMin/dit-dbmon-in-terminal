# DOC-9: TDD (Technical Design Document)

## 9.1 개요

DIT Oracle 모니터링의 기술 구현 상세. Java 8+ fat JAR, Lanterna TUI, JDBC 직접 쿼리 방식.

## 9.2 클래스별 설계

### 9.2.1 OracleBridgeMain

**역할:** 진입점, CLI 명령어 라우팅, JSON 직렬화

```java
public final class OracleBridgeMain {
    public static void main(String[] args)                          // 진입점
    static Connection openConnection(Map<String, String> options)   // JDBC 커넥션 팩토리

    // 명령어 실행
    private static void executeHealth(Map<String, String> options)
    private static void executeKill(Map<String, String> options)
    private static void executeMetrics(Map<String, String> options)
    private static void executeSessions(Map<String, String> options)
    private static void executeWaits(Map<String, String> options)
    private static void executeSqlHotspots(Map<String, String> options)
    private static void executeMonitor(Map<String, String> options)
    private static void executeReport(Map<String, String> options)
    private static void executeTui(Map<String, String> options)

    // 유틸리티
    private static Map<String, String> parseArgs(String[] args)     // --key value 파싱
    private static String toJson(Object value)                      // 재귀 JSON 직렬화
    private static String quote(String value)                       // JSON 문자열 이스케이프
    private static String renderScreen(...)                         // monitor용 텍스트 렌더
}
```

**CLI 인자 파싱 규칙:**
- `--key value` 쌍을 순회하여 `Map<String, String>` 생성
- `--` 접두사가 없는 값은 이전 키의 value로 처리
- `--key` 다음에 또 `--key`가 오면 value는 빈 문자열

**JSON 직렬화 (`toJson`):**
- `null` → `"null"`
- `String` → `quote()` 처리 (", \, 제어문자 이스케이프)
- `Number`, `Boolean` → `String.valueOf()`
- `Map` → `{"key":value, ...}` (LinkedHashMap 순서 보존)
- `List` → `[item, ...]`

**JDBC 커넥션 설정:**

| 프로퍼티 | 기본값 | 용도 |
|----------|--------|------|
| `oracle.net.CONNECT_TIMEOUT` | 5000ms | TCP 연결 타임아웃 |
| `oracle.jdbc.ReadTimeout` | 3000ms (CLI) / 5000ms (TUI) | 쿼리 읽기 타임아웃 |
| `v$session.program` | `"dit-bridge"` | 자기 세션 식별/필터링 |

### 9.2.2 OracleCollector

**역할:** Oracle V$ 뷰 JDBC 쿼리 계층

```java
public final class OracleCollector {
    // 개별 쿼리 메서드 (각각 throws SQLException)
    public static Map<String, Object> querySysmetric(Connection conn)
    public static Map<String, Object> querySysstat(Connection conn)
    public static List<Map<String, Object>> queryWaits(Connection conn)
    public static List<Map<String, Object>> querySessions(Connection conn)
    public static List<Map<String, Object>> querySqlHotspots(Connection conn)
    public static Map<String, Object> queryInstanceInfo(Connection conn)
    public static String queryServerTime(Connection conn)

    // 집합 수집 (per-query 에러 격리)
    public static Map<String, Object> collectAll(Connection conn)

    // 메트릭 정규화
    public static Map<String, Object> mapMetrics(
        Map<String, Object> sysmetric, Map<String, Object> sysstat)
}
```

**Oracle V$ 뷰 쿼리 상세:**

| 메서드 | Oracle 뷰 | 주요 필터 | 반환 건수 |
|--------|-----------|----------|----------|
| `querySysmetric` | V$SYSMETRIC | `ROW_NUMBER()` 중복 제거, 16개 메트릭 | 최대 16 |
| `querySysstat` | V$SYSSTAT | 7개 카운터 | 최대 7 |
| `queryWaits` | V$SYSTEM_EVENT | `wait_class <> 'Idle'`, time_waited DESC | 12 |
| `querySessions` | V$SESSION + V$SQL | `type='USER'`, `wait_class<>'Idle'`, 자기세션 제외 | 30 |
| `querySqlHotspots` | V$SQL | 10분 이내, V$/X$ 제외, cpu_time DESC | 15 |
| `queryInstanceInfo` | V$INSTANCE | - | 1 |
| `queryServerTime` | DUAL | `SYSDATE` | 1 |

**V$SYSMETRIC 중복 제거:**
```sql
SELECT metric_name, value
FROM (
  SELECT metric_name, value, intsize_csec,
         ROW_NUMBER() OVER (PARTITION BY metric_name ORDER BY intsize_csec ASC) rn
  FROM v$sysmetric
) x
WHERE rn = 1 AND metric_name IN (...)
```
- `intsize_csec ASC`로 가장 짧은 인터벌(최신) 값을 선택

**세션 필터링:**
```sql
WHERE s.type = 'USER'
  AND s.wait_class <> 'Idle'
  AND s.sid <> SYS_CONTEXT('USERENV', 'SID')
  AND NVL(s.program, '-') <> 'dit-bridge'
```

**collectAll() 에러 격리 패턴:**
```java
try {
    data.put("sysmetric", querySysmetric(conn));
} catch (SQLException e) {
    data.put("sysmetric", new LinkedHashMap<>());
}
// ... 각 쿼리 반복
```

**mapMetrics() 정규화 매핑:**

| 출력 키 | 소스 | 변환 |
|---------|------|------|
| `active_sessions` | V$SYSMETRIC: Average Active Sessions | 직접 |
| `db_time_per_sec` | V$SYSMETRIC: Database Time Per Sec | 직접 |
| `cpu_time_per_sec` | V$SYSMETRIC: CPU Usage Per Sec | 직접 |
| `wait_time_per_sec` | 파생 | `max(0, db_time - cpu_time)` |
| `tran_per_sec` | V$SYSMETRIC | `User Commits + User Rollbacks` |
| `physical_read_mb_per_sec` | V$SYSMETRIC | `Physical Read Total Bytes / 1024^2` |
| `physical_write_mb_per_sec` | V$SYSMETRIC | `Physical Write Total Bytes / 1024^2` |
| `redo_mb_per_sec` | V$SYSMETRIC | `Redo Generated Per Sec / 1024^2` |
| `parse_total_per_sec` | V$SYSMETRIC: Total Parse Count Per Sec | 직접 |
| `hard_parses_per_sec` | V$SYSMETRIC: Hard Parse Count Per Sec | 직접 |

### 9.2.3 OracleMonitorTui

**역할:** Lanterna Screen 기반 인터랙티브 대시보드

```java
public final class OracleMonitorTui {
    public OracleMonitorTui(Map<String, String> options)
    public void run() throws IOException                // 메인 루프

    private void collectData(Connection conn)           // 데이터 수집 + 버퍼 push
    private void render(Screen screen, TerminalSize)    // 전체 화면 렌더링
    private void handleKey(KeyStroke key, Screen)       // 키보드 입력 처리
    private Connection openConnection()                 // JDBC 커넥션

    // 렌더링 헬퍼
    private void drawMetricRow(...)                     // 메트릭 라벨 + 값 + Sparkline
    private void drawMetricRowPct(...)                  // 메트릭 + 백분율 (CPU%/DB Time)
    private void drawBox(...)                           // 박스 테두리 (┌─┐│└┘)
    private void drawBar(...)                           // 타이틀/푸터 바
    private void drawText(...)                          // 텍스트 출력 (바운드 체크)
    private void setChar(...)                           // 단일 문자 출력
}
```

**TUI 레이아웃 (~160x45):**

```
Row 0:       ┌ Title Bar ─────────────────────────────────────────────┐
             │ DIT | DEV@dbhost | 19.3.0.0.0 | Collected: 14:23:05  │
Row 1-15:    ┌ Load Profile ──────────┐┌ Top Waits (Real-time) ─────┐
             │ Active Sessions  0.35  ▁▂▃ ││ db file sequential read     │
             │ DB Time/s        0.12  ▁▁▁ ││ log file sync               │
             │ CPU Time/s       0.08  ▁▁▁ ││ ...                         │
             │ Wait Time/s      0.04  ▁▁▁ ││                             │
             │ Logical Reads/s  1,234 ▃▅▇ ││                             │
             │ Tran/s           56    ▂▃▃ ││                             │
             │ ... (13개 메트릭)       ││                             │
             └────────────────────────┘└─────────────────────────────┘
Row 16-N:    ┌ Sessions (5) ──────────────────────────────────────────┐
             │ SID  Serial User     Status  Wait Event        ...    │
             │ 142  12345  APP      ACTIVE  db file seq read  ...    │
             └────────────────────────────────────────────────────────┘
Row N-M:     ┌ Top SQL (10 min) ──────────────────────────────────────┐
             │ SQL ID     Plan Hash  Elapsed  CPU   Execs  SQL Text  │
             └────────────────────────────────────────────────────────┘
Row H-1:     ┌ Footer ───────────────────────────────────────────────┐
             │ Q:Quit  Up/Down:Navigate  | Interval: 6s  Collect: 45ms │
             └────────────────────────────────────────────────────────┘
```

**키보드 바인딩:**

| 키 | 동작 |
|----|------|
| Q / Esc | 종료 |
| Up / Down | 세션 선택 이동 |
| PgUp / PgDn | 세션 10줄 점프 |
| Home / End | 세션 처음/끝 |
| Tab | SQL 패널 스크롤 |
| R | 강제 새로고침 (다음 주기 대기) |

**색상 팔레트:**

| 요소 | RGB | 용도 |
|------|-----|------|
| TITLE_BG | (30, 80, 160) | 타이틀 바 배경 |
| HEADER_FG | (100, 200, 255) | 헤더/라벨 텍스트 |
| VALUE_FG | (0, 255, 128) | 메트릭 값 |
| SPARK_FG | (0, 180, 255) | Sparkline |
| ACTIVE_FG | (0, 255, 0) | ACTIVE 세션 |
| INACTIVE_FG | (128, 128, 128) | INACTIVE 세션 |
| SELECT_BG | (40, 40, 80) | 선택된 행 배경 |

**Wait Class 색상:**

| Wait Class | RGB |
|------------|-----|
| User I/O | (0, 128, 255) |
| System I/O | (100, 149, 237) |
| Concurrency | (255, 100, 100) |
| Application | (255, 50, 50) |
| Commit | (255, 165, 0) |
| Configuration / Administrative | (200, 100, 200) |
| Network | (180, 180, 0) |
| CPU | (0, 200, 0) |

**스레드 안전성:**
- `Object dataLock`으로 `currentData`/`currentMetrics` 동기화
- `AtomicBoolean running`으로 종료 플래그
- 수집과 렌더링이 같은 스레드에서 순차 실행 (별도 스레드 없음)

### 9.2.4 MetricsBuffer

**역할:** 메트릭별 Ring Buffer + Unicode Sparkline 렌더링

```java
public final class MetricsBuffer {
    public MetricsBuffer(int capacity)                          // 기본 60
    public synchronized void push(String metric, double value)  // 값 추가
    public synchronized double[] getValues(String metric)       // 시간순 전체 값
    public synchronized double latest(String metric)            // 최신 값
    public synchronized String sparkline(String metric, int width) // Sparkline 생성
    public synchronized int size(String metric)                 // 저장된 값 수
}
```

**Ring Buffer 알고리즘:**

```
capacity = 60
buffers["active_sessions"] = double[60]
positions["active_sessions"] = 0  (쓰기 헤드)
counts["active_sessions"] = 0     (저장된 수, ≤ capacity)

push(value):
  buffer[position] = value
  position = (position + 1) % capacity
  if (count < capacity) count++

getValues() → 시간순 정렬:
  start = (position - count + capacity) % capacity
  for i in 0..count-1:
    result[i] = buffer[(start + i) % capacity]
```

**Sparkline 렌더링:**
- Unicode 블록 문자 9단계: `' ', '▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'`
- min/max 기반 자동 스케일링
- `index = (value - min) / range * 8` → 블록 문자 매핑
- range < 0.0001: max > 0이면 블록 4(▄), 아니면 공백

### 9.2.5 WaitEventDeltaTracker

**역할:** V$SYSTEM_EVENT 스냅샷 간 초당 델타 연산

```java
public final class WaitEventDeltaTracker {
    private Map<String, long[]> prevSnapshot   // event → [time_waited_micro, total_waits]
    private Map<String, String> prevWaitClass  // event → wait_class
    private long prevTimestampMs               // 이전 호출 시각

    public List<Map<String, Object>> queryDelta(Connection conn)
}
```

**델타 연산 알고리즘:**

```
1차 호출: prevSnapshot = null
  → V$SYSTEM_EVENT 스냅샷 저장, 빈 리스트 반환

2차+ 호출:
  current = V$SYSTEM_EVENT 현재 스냅샷
  elapsedSec = (nowMs - prevTimestampMs) / 1000.0

  if elapsedSec < 0.5: return []  (너무 짧은 간격 방지)

  for each event in current:
    prev = prevSnapshot[event] or [0, 0]
    dTimeMicro = current.timeMicro - prev.timeMicro
    dWaits     = current.totalWaits - prev.totalWaits

    if dTimeMicro <= 0 or dWaits < 0: skip  (카운터 래핑 방지)

    waitSecPerSec = (dTimeMicro / 1,000,000) / elapsedSec
    waitsPerSec   = dWaits / elapsedSec
    avgWaitMs     = dWaits > 0 ? (dTimeMicro / dWaits / 1000) : 0

  sort by waitSecPerSec DESC
  limit 12
  prevSnapshot = current
```

**V$EVENTMETRIC 대비 장점:**
- V$EVENTMETRIC: 60초 보고 주기, 부하 중단 후에도 잔여 값 유지
- V$SYSTEM_EVENT 델타: 수집 간격(6초) 맞춤, 부하 중단 시 즉시 0으로 하락

## 9.3 데이터 구조

### collectAll() 반환 구조

```json
{
  "server_time": "14:23:05",
  "instance": {
    "instance_name": "DEV",
    "host_name": "dbhost",
    "version": "19.3.0.0.0",
    "status": "OPEN",
    "startup_time": "2026-02-01 09:00:00"
  },
  "sysmetric": { "Average Active Sessions": 0.35, ... },
  "sysstat": { "execute count": 123456, ... },
  "waits": [
    { "wait_class": "User I/O", "event": "db file sequential read",
      "avg_wait_ms": 1.23, "wait_time_ms": 45678.9 }
  ],
  "sessions": [
    { "sid": "142", "serial": "12345", "username": "APP",
      "status": "ACTIVE", "event": "db file sequential read",
      "wait_class": "User I/O", "sql_id": "abc123def",
      "seconds_in_wait": 0.5, "sql_text": "SELECT ..." }
  ],
  "sql_hotspots": [
    { "sql_id": "abc123def", "plan_hash_value": "987654",
      "elapsed_time": 123456, "cpu_time": 98765,
      "executions": 1000, "buffer_gets": 50000,
      "sql_text": "SELECT ..." }
  ]
}
```

### Monitor JSONL 프레임 구조

```json
{
  "type": "frame",
  "timestamp": "2026-02-18T05:23:05.123Z",
  "db_type": "oracle",
  "instance_name": "DEV",
  "collector_state": "ON",
  "data_sources": {
    "metrics": "collector",
    "sessions": "collector",
    "wait_events": "collector",
    "sql_hotspots": "collector"
  },
  "metrics": { "active_sessions": 0.35, "db_time_per_sec": 0.12, ... },
  "sessions": [...],
  "wait_events": [...],
  "sql_hotspots": [...]
}
```

## 9.4 성능 특성

### 수집 주기

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `--interval` (TUI) | 6초 | 데이터 수집 간격 |
| `--interval-seconds` (monitor) | 1초 | JSONL 녹화 간격 |
| Poll loop | 100ms | 키보드 입력 폴링 |

### 메모리 사용

| 컴포넌트 | 메모리 | 산출 |
|----------|--------|------|
| MetricsBuffer | ~50 KB | 22 메트릭 x 60 포인트 x 8 bytes x 3 maps |
| 세션 데이터 | ~30 KB | 30 세션 x ~1 KB/세션 |
| SQL 데이터 | ~15 KB | 15 SQL x ~1 KB/SQL |
| Lanterna Screen | ~1 MB | 터미널 문자 버퍼 |
| JDBC 드라이버 | ~5 MB | ojdbc8 내부 버퍼 |

### DB 쿼리 부하

collectAll() 1회 호출 시 Oracle V$ 뷰 쿼리 7개:

| 쿼리 | 대상 뷰 | 예상 소요 |
|------|---------|----------|
| queryServerTime | DUAL | < 1ms |
| queryInstanceInfo | V$INSTANCE | < 1ms |
| querySysmetric | V$SYSMETRIC | 1-5ms |
| querySysstat | V$SYSSTAT | 1-3ms |
| queryWaits | V$SYSTEM_EVENT | 1-5ms |
| querySessions | V$SESSION + V$SQL | 5-20ms |
| querySqlHotspots | V$SQL | 5-20ms |

WaitEventDeltaTracker.queryDelta() 추가 1개:

| 쿼리 | 대상 뷰 | 예상 소요 |
|------|---------|----------|
| queryDelta | V$SYSTEM_EVENT | 1-5ms |

**총 수집 소요: 15-55ms/주기** (6초 간격 대비 < 1%)

## 9.5 확장 포인트

### Multi-DBMS 확장 (미구현)

```
io.dit/
├── oracle/     ← 현재 구현
│   ├── OracleCollector.java
│   └── OracleMonitorTui.java
├── mysql/      ← 향후
│   ├── MysqlCollector.java
│   └── MysqlMonitorTui.java
├── postgresql/
└── mssql/
```

### 향후 기능 (미구현)

| 기능 | 설명 |
|------|------|
| Alert Engine | 임계값 기반 경고 (thresholds.json 참조) |
| History Save/Replay | JSONL 녹화 파일 재생 |
| Multi-DBMS | MySQL, PostgreSQL, SQL Server 지원 |
