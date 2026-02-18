# DOC-9: TDD (Technical Design Document)

## 9.1 개요

DIT Oracle / Tibero 모니터링의 기술 구현 상세. Java 8+ fat JAR, Lanterna TUI, JDBC 직접 쿼리 방식.
Multi-DBMS 인터페이스 아키텍처: `api/` 인터페이스 → `oracle/`, `tibero/` 구현체.

## 9.2 API 인터페이스

### 9.2.1 DbmsCollector

```java
public interface DbmsCollector {
    Map<String, Object> collectAll(Connection conn);
    Map<String, Object> mapMetrics(Map<String, Object> sysmetric, Map<String, Object> sysstat);
    List<Map<String, Object>> querySessions(Connection conn) throws SQLException;
    List<Map<String, Object>> querySqlHotspots(Connection conn) throws SQLException;
    Map<String, Object> queryInstanceInfo(Connection conn) throws SQLException;
}
```

### 9.2.2 DbmsConnectionFactory

```java
public interface DbmsConnectionFactory {
    Connection openConnection(Map<String, String> options) throws SQLException;
    Connection openTuiConnection(Map<String, String> options) throws SQLException;
}
```

### 9.2.3 WaitDeltaTracker

```java
public interface WaitDeltaTracker {
    List<Map<String, Object>> queryDelta(Connection conn) throws SQLException;
}
```

## 9.3 공통 클래스

### 9.3.1 DitMain

**역할:** 진입점, `--dbms-type` 분기, CLI 명령어 라우팅, JSON 직렬화

```java
public final class DitMain {
    public static void main(String[] args)
    // --dbms-type → DbmsType enum → 해당 Factory/Collector/Tracker 인스턴스 생성
    // --command → health/kill/metrics/sessions/waits/sql/monitor/report/tui 분기
}
```

**CLI 인자 파싱:** `--key value` → `Map<String, String>`, `parseArgs()` 메서드.

### 9.3.2 MetricsBuffer

**역할:** 메트릭별 Ring Buffer + Unicode Sparkline 렌더링

```java
public final class MetricsBuffer {
    public MetricsBuffer(int capacity)                          // 기본 60
    public synchronized void push(String metric, double value)
    public synchronized double[] getValues(String metric)
    public synchronized double latest(String metric)
    public synchronized String sparkline(String metric, int width)
    public synchronized int size(String metric)
}
```

**Sparkline 렌더링:**
- Unicode 블록 문자 9단계: `' ', '▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'`
- min/max 기반 자동 스케일링

### 9.3.3 JsonUtil

**역할:** 외부 라이브러리 없는 경량 JSON 직렬화

```java
public final class JsonUtil {
    public static String toJson(Object value)  // 재귀 JSON 직렬화 (Map, List, String, Number, Boolean, null)
    public static String quote(String value)   // JSON 문자열 이스케이프
}
```

## 9.4 Oracle 구현

### 9.4.1 OracleCollector

**역할:** Oracle V$ 뷰 JDBC 쿼리 계층. `DbmsCollector` 인터페이스 + static 쿼리 메서드.

| 메서드 | Oracle 뷰 | 주요 필터 | 반환 건수 |
|--------|-----------|----------|----------|
| `querySysmetric` | V$SYSMETRIC | `ROW_NUMBER()` 중복 제거, 16개 메트릭 | 최대 16 |
| `querySysstat` | V$SYSSTAT | 7개 카운터 | 최대 7 |
| `queryWaits` | V$SYSTEM_EVENT | `wait_class <> 'Idle'`, time_waited DESC | 12 |
| `querySessions` | V$SESSION + V$SQL | `type='USER'`, `wait_class<>'Idle'`, 자기세션 제외 | 30 |
| `querySqlHotspots` | V$SQL | 10분 이내, V$/X$ 제외, elapsed_time DESC | 15 |
| `queryInstanceInfo` | V$INSTANCE | - | 1 |
| `queryServerTime` | DUAL | `SYSDATE` | 1 |

**자기 세션 필터링:**
```sql
WHERE s.type = 'USER'
  AND s.wait_class <> 'Idle'
  AND s.sid <> SYS_CONTEXT('USERENV', 'SID')
  AND NVL(s.program, '-') <> 'dit-bridge'
```

### 9.4.2 OracleWaitDeltaTracker

**역할:** V$SYSTEM_EVENT 스냅샷 간 초당 델타 연산. `WaitDeltaTracker` 인터페이스 구현.

**델타 연산:** `(current.time_waited_micro - prev.time_waited_micro) / elapsed_sec`

### 9.4.3 OracleConnectionFactory

**JDBC 커넥션 설정:**

| 프로퍼티 | 기본값 | 용도 |
|----------|--------|------|
| `oracle.net.CONNECT_TIMEOUT` | 5000ms | TCP 연결 타임아웃 |
| `oracle.jdbc.ReadTimeout` | 3000ms (CLI) / 5000ms (TUI) | 쿼리 읽기 타임아웃 |
| `v$session.program` | `"dit-bridge"` | 자기 세션 식별/필터링 |

### 9.4.4 OracleMonitorTui

**역할:** Lanterna Screen 기반 Oracle 대시보드. 서버 시간(SYSDATE) 표시.

## 9.5 Tibero 구현

### 9.5.1 TiberoCollector

**역할:** Tibero V$ 뷰 쿼리. V$SYSMETRIC 대체를 위한 **V$SYSSTAT 내부 delta** 보유.

**Oracle과의 차이:**

| 항목 | Oracle | Tibero |
|------|--------|--------|
| Load Profile | V$SYSMETRIC (즉시 rate) | V$SYSSTAT + V$SYS_TIME_MODEL delta |
| Sessions 필터 | `type='USER'` | `type='WTHR'` |
| Wait(s) 컬럼 | `SECONDS_IN_WAIT` | `SQL_ET` (SQL 경과 시간) |
| 프로그램 컬럼 | `PROGRAM` | `PROG_NAME` |
| 자기 세션 | `SYS_CONTEXT('USERENV','SID')` | `V$MYSTAT` SID |
| Physical Writes | `physical writes` | `dbwr multi block writes - block count` |
| Top N | `FETCH FIRST N ROWS ONLY` | `ROWNUM` |
| 서버 시간 | 정상 | 신뢰 불가 → `System.currentTimeMillis()` |

**V$SYSSTAT → Synthetic Sysmetric 매핑:**

| 출력 키 | Tibero 소스 |
|---------|------------|
| Average Active Sessions | V$SYS_TIME_MODEL "DB Time" delta / elapsed / 1_000_000 |
| Database Time Per Sec | V$SYS_TIME_MODEL "DB Time" delta * 100 / elapsed / 1_000_000 |
| CPU Usage Per Sec | V$SYS_TIME_MODEL "DB CPU" delta * 100 / elapsed / 1_000_000 |
| Executions Per Sec | "execute count" delta / elapsed |
| Logical Reads Per Sec | "logical reads" delta / elapsed |
| Physical Reads Per Sec | "physical reads" delta / elapsed |
| Physical Writes Per Sec | "dbwr multi block writes - block count" delta / elapsed |
| Redo Generated Per Sec | "redo log size" delta / elapsed |
| User Commits Per Sec | "the number of user commits performed" delta / elapsed |
| Total Parse Count Per Sec | "parse count (total)" delta / elapsed |
| Hard Parse Count Per Sec | "parse count (hard)" delta / elapsed |

### 9.5.2 TiberoWaitDeltaTracker

**역할:** V$SYSTEM_EVENT 델타. `WaitDeltaTracker` 인터페이스 구현.

**컬럼명 차이:** `NAME` (event), `CLASS` (wait_class), `TIME_WAITED` (centiseconds)
**CLASS 정규화:** `STAT_CLASS_CONCURRENCY` → `Concurrency`, `STAT_CLASS_USER_IO` → `User I/O` 등

### 9.5.3 TiberoConnectionFactory

**JDBC URL:** `jdbc:tibero:thin:@host:port:dbname` (`--service-name`으로 dbname 전달)

### 9.5.4 TiberoMonitorTui

**역할:** Lanterna Screen 기반 Tibero 대시보드. 앱 시간 기반 수집 시각 표시.

## 9.6 TUI 레이아웃 (~160x45)

```
Row 0:       ┌ Title Bar ─────────────────────────────────────────────┐
             │ DIT | DEV@dbhost | 19.0.0.0.0 | Collected: 14:23:05  │
Row 1-15:    ┌ Load Profile ──────────┐┌ Top Waits (Real-time) ─────┐
             │ Active Sessions  0.35  ▁▂▃ ││ db file sequential read     │
             │ DB Time/s        0.12  ▁▁▁ ││ log file sync               │
             │ ...                        ││                             │
             └────────────────────────┘└─────────────────────────────┘
Row 16-N:    ┌ Sessions (5) ──────────────────────────────────────────┐
             │ SID  Serial User     Status  Wait Event        ...    │
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
| R | 강제 새로고침 |

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

## 9.7 성능 특성

### 수집 주기

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `--interval` (TUI) | 6초 | 데이터 수집 간격 |
| `--interval-seconds` (monitor) | 1초 | JSONL 녹화 간격 |
| Poll loop | 100ms | 키보드 입력 폴링 |

### DB 쿼리 부하

collectAll() 1회 호출 시 V$ 뷰 쿼리 6-8개:

**총 수집 소요: 15-55ms/주기** (6초 간격 대비 < 1%)

## 9.8 확장 포인트

### Multi-DBMS 확장 (구현됨)

```
io.dit.bridge/
├── api/          ← 인터페이스 (DbmsCollector, DbmsConnectionFactory, WaitDeltaTracker)
├── core/         ← DBMS-agnostic (MetricsBuffer, JsonUtil)
├── oracle/       ← Oracle 구현 (완료)
├── tibero/       ← Tibero 구현 (완료)
├── mysql/        ← 향후
├── postgresql/   ← 향후
└── mssql/        ← 향후
```

새 DBMS 추가 절차:
1. `{dbms}/` 패키지 생성
2. `DbmsCollector`, `DbmsConnectionFactory`, `WaitDeltaTracker` 구현
3. `{Dbms}MonitorTui` 구현
4. `DbmsType` enum에 추가
5. `DitMain`에 분기 추가
