# DOC-8: STD (Software Technical Document)

## 8.1 개요

DIT (DBmon-In-Terminal)는 SSH 터미널 환경에서 Oracle / Tibero DB를 실시간 모니터링하는 Java 기반 TUI 도구이다. 단일 fat JAR로 배포되며, 외부 에이전트/웹서버 없이 JDBC 직접 쿼리로 동작한다.

### 기술 스택

| 항목 | 기술 | 버전 |
|------|------|------|
| 언어 | Java | 8+ (source/target 1.8) |
| 빌드 | Maven + maven-shade-plugin | 3.x |
| TUI | Lanterna (Screen layer) | 3.1.3 |
| Oracle 드라이버 | ojdbc8 | 23.3.0.23.09 |
| Tibero 드라이버 | tibero7-jdbc | 7.0 |
| 패키지 | `io.dit.bridge` | 0.1.2 |
| 배포 형태 | Fat JAR (의존성 포함) | - |

### 설계 제약

- GUI/웹서버 기동 금지 (보안 정책)
- 외부 JSON 라이브러리 사용 금지 (자체 `JsonUtil.toJson()`/`JsonUtil.quote()`)
- Java 8 호환 (`var` 사용 금지, diamond/lambda 허용)
- 단일 JAR 파일 배포 (maven-shade-plugin)

## 8.2 시스템 아키텍처

```
┌─────────────────────────────────────────────────────────┐
│  CLI / TUI                                              │
│  DitMain (진입점, --dbms-type 분기, 명령어 라우팅)          │
│  OracleMonitorTui / TiberoMonitorTui (Lanterna 대시보드)  │
├─────────────────────────────────────────────────────────┤
│  API (인터페이스)                                         │
│  DbmsCollector / DbmsConnectionFactory / WaitDeltaTracker│
├─────────────────────────────────────────────────────────┤
│  데이터 처리 (DBMS-agnostic)                              │
│  MetricsBuffer (Ring Buffer + Sparkline)                 │
│  JsonUtil (경량 JSON 직렬화)                              │
├─────────────────────────────────────────────────────────┤
│  Oracle 구현                    │  Tibero 구현            │
│  OracleCollector                │  TiberoCollector        │
│  OracleConnectionFactory        │  TiberoConnectionFactory│
│  OracleWaitDeltaTracker         │  TiberoWaitDeltaTracker │
├─────────────────────────────────────────────────────────┤
│  인프라                                                  │
│  Oracle JDBC (ojdbc8) + Tibero JDBC + Lanterna Terminal  │
└─────────────────────────────────────────────────────────┘
```

### 클래스 의존 관계

```
DitMain (진입점, --dbms-type 분기)
├── DbmsType                    (ORACLE | TIBERO enum)
├── DbmsCollector               (인터페이스 → Oracle/Tibero Collector)
├── DbmsConnectionFactory       (인터페이스 → Oracle/Tibero ConnectionFactory)
├── WaitDeltaTracker            (인터페이스 → Oracle/Tibero WaitDeltaTracker)
├── OracleMonitorTui            (tui 명령어, Oracle)
│   ├── OracleCollector         (collectAll)
│   ├── OracleWaitDeltaTracker  (실시간 Wait 델타)
│   ├── MetricsBuffer           (Sparkline 이력)
│   └── Lanterna Screen         (터미널 렌더링)
└── TiberoMonitorTui            (tui 명령어, Tibero)
    ├── TiberoCollector         (collectAll + V$SYSSTAT 내부 delta)
    ├── TiberoWaitDeltaTracker  (실시간 Wait 델타)
    ├── MetricsBuffer           (Sparkline 이력)
    └── Lanterna Screen         (터미널 렌더링)
```

## 8.3 소스 파일 구조

```
java/dit-bridge/
├── pom.xml
├── lib/
│   └── tibero7-jdbc.jar           # Tibero JDBC 드라이버
└── src/main/java/io/dit/bridge/
    ├── DitMain.java               # 진입점, CLI 라우팅, JSON 직렬화
    ├── DbmsType.java              # DBMS 타입 enum (ORACLE, TIBERO)
    ├── api/
    │   ├── DbmsCollector.java     # DBMS별 수집기 인터페이스
    │   ├── DbmsConnectionFactory.java # DBMS별 접속 팩토리 인터페이스
    │   └── WaitDeltaTracker.java  # Wait Event 델타 추적 인터페이스
    ├── core/
    │   ├── MetricsBuffer.java     # Ring Buffer + Unicode Sparkline
    │   └── JsonUtil.java          # 경량 JSON 직렬화
    ├── oracle/
    │   ├── OracleCollector.java   # Oracle V$ 뷰 JDBC 쿼리
    │   ├── OracleConnectionFactory.java # Oracle JDBC 접속
    │   ├── OracleWaitDeltaTracker.java  # V$SYSTEM_EVENT 델타 연산
    │   └── OracleMonitorTui.java  # Lanterna TUI 대시보드
    └── tibero/
        ├── TiberoCollector.java   # Tibero V$ 뷰 쿼리 + V$SYSSTAT 델타
        ├── TiberoConnectionFactory.java # Tibero JDBC 접속
        ├── TiberoWaitDeltaTracker.java  # V$SYSTEM_EVENT 델타 연산
        └── TiberoMonitorTui.java  # Lanterna TUI 대시보드
```

## 8.4 명령어 체계

`--dbms-type oracle|tibero` + `--command` 파라미터로 분기:

| 명령어 | 용도 | 입력 | 출력 |
|--------|------|------|------|
| `health` | DB 연결 확인 | 접속 정보 | `{"ok":true}` |
| `kill` | 세션 강제 종료 | `--sid sid,serial#` | `{"ok":true}` |
| `metrics` | 성능 메트릭 스냅샷 | 접속 정보 | JSON (sysmetric + sysstat) |
| `sessions` | 활성 세션 목록 | 접속 정보 | JSON (sessions[]) |
| `waits` | 대기 이벤트 | 접속 정보 | JSON (wait_events[]) |
| `sql` | SQL 핫스팟 | 접속 정보 | JSON (sql_hotspots[]) |
| `monitor` | 연속 수집 + 녹화 | `--record-file`, `--capture-file` | JSONL 파일 + 텍스트 |
| `report` | 녹화 파일 분석 리포트 | `--record-file`, `--output` | Markdown |
| `tui` | 인터랙티브 대시보드 | 접속 정보 | Lanterna Screen |

### CLI 인자 형식

```bash
java -jar dit-dbms-monitor.jar \
    --dbms-type oracle \
    --command tui \
    --host 192.168.0.172 --port 1521 \
    --service-name DEV \
    --user <monitoring user> --password <user password> \
    --interval 6 \
    --tcp-connect-timeout-seconds 5 \
    --call-timeout-ms 5000
```

모든 인자는 `--key value` 형식, `parseArgs()`에서 `Map<String, String>`으로 변환.

## 8.5 데이터 흐름

### CLI 명령어 파이프라인

```
String[] args
  → parseArgs() → Map<String, String>
  → DbmsConnectionFactory.openConnection() → java.sql.Connection
  → DbmsCollector.queryX() → Map/List
  → JsonUtil.toJson() → JSON 문자열
  → System.out.println()
```

### TUI 수집-렌더링 파이프라인

```
Main Loop (100ms poll):
  ├─ pollInput() → handleKey() → 스크롤/선택 상태 변경
  │
  ├─ collectData() (intervalMs마다)
  │   ├─ DbmsCollector.collectAll()       ← per-query 에러 격리
  │   ├─ WaitDeltaTracker.queryDelta()    ← 실시간 Wait 델타
  │   ├─ DbmsCollector.mapMetrics()       ← 메트릭 정규화
  │   ├─ MetricsBuffer.push()            ← Sparkline 이력 저장
  │   └─ synchronized(dataLock) { 데이터 저장 }
  │
  ├─ doResizeIfNecessary() → 터미널 리사이즈 감지
  │
  └─ render() (변경 시에만)
      ├─ Title Bar → 인스턴스@호스트 | 버전 | 수집시각
      ├─ Load Profile (좌 50%) → 14개 메트릭 + Sparkline (Host CPU % 포함)
      ├─ Top Waits (우 50%) → WaitDelta 13개
      ├─ Sessions → 스크롤 가능한 세션 테이블
      ├─ Top SQL → elapsed_time 기준 SQL 목록
      └─ Footer → 키 안내, 수집 소요시간, 에러
      → screen.refresh(DELTA) → 변경분만 업데이트
```

## 8.6 Oracle vs Tibero 차이점

| 항목 | Oracle | Tibero |
|------|--------|--------|
| Load Profile 소스 | V$SYSMETRIC (AAS/DB Time/CPU/Wait/Host CPU) + V$SYSSTAT delta (나머지) | V$SYSSTAT delta (앱 시간 기반) |
| Host CPU % | V$SYSMETRIC `Host CPU Utilization (%)` | V$OSSTAT2 `BUSY_TIME`/`IDLE_TIME` delta |
| DB Time / CPU | V$SYSMETRIC | V$SYS_TIME_MODEL delta |
| Wait Event 컬럼 | EVENT, WAIT_CLASS | NAME, CLASS (STAT_CLASS_xxx) |
| Sessions 필터 | `type='USER'`, SYS_CONTEXT | `type='WTHR'`, V$MYSTAT SID |
| Sessions Wait(s) | SECONDS_IN_WAIT | SQL_ET (SQL 경과 시간) |
| 세션 프로그램 | PROGRAM | PROG_NAME |
| Physical Writes | `physical writes` stat | `dbwr multi block writes - block count` |
| Top N 제한 | FETCH FIRST N ROWS ONLY | ROWNUM |
| 서버 시간 | SYSDATE (정상) | 신뢰 불가 → 앱 시간 사용 |
| JDBC URL | `jdbc:oracle:thin:@//host:port/svc` | `jdbc:tibero:thin:@host:port:db` |

## 8.7 에러 처리

| 상황 | 처리 | 사용자 표시 |
|------|------|------------|
| DB 접속 실패 | JSON 에러 출력 후 exit(1) | `{"error":"SQL error: ..."}` |
| TUI 접속 실패 | stderr 출력 후 종료 | `Connection failed: ...` |
| TUI 수집 중 쿼리 실패 | 해당 메트릭 스킵, 다음 주기 재시도 | Footer ERR 표시 |
| TUI 연결 끊김 | 자동 재접속 시도 | Footer `Reconnect failed: ...` |
| collectAll() 개별 쿼리 실패 | 빈 데이터 반환, 나머지 쿼리 계속 | 해당 패널 비어있음 |
| 터미널 크기 부족 (40x10 미만) | 렌더링 스킵 | 빈 화면 |

## 8.8 빌드 및 배포

### 빌드

```bash
mvn clean package -f java/dit-bridge/pom.xml
# 출력: java/dit-bridge/target/dit-dbms-monitor.jar (fat JAR)
```

### 배포

Fat JAR 단일 파일 복사:

```bash
scp java/dit-bridge/target/dit-dbms-monitor.jar user@server:~/
ssh user@server 'java -jar dit-dbms-monitor.jar --dbms-type oracle --command tui ...'
```

### 런타임 디렉토리

```
.dit/
├── bin/
│   └── dit-dbms-monitor.jar     # 배포 JAR
└── cycles/                      # 모니터링 사이클 아카이브
```

## 8.9 설계 결정 기록 (ADR)

### ADR-1: Lanterna Screen vs Lanterna GUI vs Textual(Python)

| | Lanterna Screen (채택) | Lanterna GUI | Python Textual |
|--|----------------------|-------------|----------------|
| 장점 | 문자 단위 제어, 경량, Java 8 호환 | 위젯 추상화 | 풍부한 위젯, async |
| 단점 | 수동 레이아웃 | Java 11+ 필요 | Python 런타임 필요 |
| 결정 | Java 8 호환 + SSH 환경 + 최소 의존성 |

### ADR-2: 외부 JSON 라이브러리 미사용

- 장점: 의존성 최소화, JAR 크기 절약, 빌드 단순
- 구현: `JsonUtil.toJson()` + `JsonUtil.quote()` (Map, List, String, Number, Boolean, null 처리)

### ADR-3: V$EVENTMETRIC → V$SYSTEM_EVENT 델타

- V$EVENTMETRIC: 60초 보고 주기, 부하 중단 후에도 값 유지
- V$SYSTEM_EVENT 델타: 수집 주기(6초)에 맞는 실시간 반영, 즉시 0으로 하락
- WaitDeltaTracker 인터페이스 + Oracle/Tibero 구현으로 해결

### ADR-4: Per-query 에러 격리

- `collectAll()`에서 각 쿼리를 개별 try-catch로 감싸서 하나의 실패가 전체를 중단하지 않음
- 실패한 메트릭은 빈 Map/List로 대체
- TUI는 null-safe하게 렌더링

### ADR-5: Multi-DBMS 인터페이스 아키텍처

- `DbmsCollector`, `DbmsConnectionFactory`, `WaitDeltaTracker` 인터페이스 정의
- DBMS별 구현체를 별도 패키지로 분리 (oracle/, tibero/)
- `--dbms-type` CLI 인자로 런타임 구현체 선택
- 새 DBMS 추가 시 인터페이스 구현 + DitMain 분기 추가만 필요

### ADR-6: V$SYSSTAT 내부 델타 (Oracle + Tibero 공통)

- Oracle: V$SYSMETRIC은 AAS, DB Time, CPU Time, Wait Time, Host CPU %만 사용. 나머지 메트릭(Exec, Logical Reads, Phy Reads/Writes, Redo, Parse, Tran)은 V$SYSSTAT delta로 전환
- Tibero: V$SYSMETRIC이 없으므로 V$SYSSTAT + V$SYS_TIME_MODEL delta로 대체
- 양쪽 Collector 내부에 prevSysstat/prevTimestamp 상태 보유
- Oracle은 V$SYSMETRIC과 V$SYSSTAT delta를 `collectAll()`에서 merge
- Tibero 서버 시간 신뢰 불가 → System.currentTimeMillis() 기반 delta 계산
- 첫 번째 호출은 baseline (rate = 0)

### ADR-7: Host CPU % (V$SYSMETRIC / V$OSSTAT2)

- Oracle: V$SYSMETRIC `Host CPU Utilization (%)`로 직접 조회
- Tibero: V$OSSTAT2 `BUSY_TIME`/`IDLE_TIME` delta로 계산 (`busy_delta / (busy_delta + idle_delta) * 100`)
- Tibero에는 V$OSSTAT이 없고 V$OSSTAT2를 사용 (Tibero 6 FS06+)
- V$OSSTAT2 미존재 시 graceful skip (try-catch)

### ADR-8: Top SQL K/M/G 단위 포맷

- Elapsed(s), CPU(s), Execs, Gets, Gets/Exec에 `fmtHuman()` 적용
- 1,000 이상 K, 1,000,000 이상 M, 1,000,000,000 이상 G 단위 표시
- 대량 부하 환경에서 숫자 가독성 개선
