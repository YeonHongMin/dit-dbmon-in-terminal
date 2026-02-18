# DOC-8: STD (Software Technical Document)

## 8.1 개요

DIT (DBmon-In-Terminal)는 SSH 터미널 환경에서 Oracle DB를 실시간 모니터링하는 Java 기반 TUI 도구이다. 단일 fat JAR로 배포되며, 외부 에이전트/웹서버 없이 JDBC 직접 쿼리로 동작한다.

### 기술 스택

| 항목 | 기술 | 버전 |
|------|------|------|
| 언어 | Java | 8+ (source/target 1.8) |
| 빌드 | Maven + maven-shade-plugin | 3.x |
| TUI | Lanterna (Screen layer) | 3.1.3 |
| DB 드라이버 | Oracle JDBC (ojdbc8) | 23.3.0.23.09 |
| 패키지 | `io.dit.oracle` | 0.1.0 |
| 배포 형태 | Fat JAR (의존성 포함) | ~7.5 MB |

### 설계 제약

- GUI/웹서버 기동 금지 (보안 정책)
- 외부 JSON 라이브러리 사용 금지 (자체 `toJson()`/`quote()`)
- Java 8 호환 (`var` 사용 금지, diamond/lambda 허용)
- 단일 JAR 파일 배포 (maven-shade-plugin)

## 8.2 시스템 아키텍처

```
┌─────────────────────────────────────────────────────────┐
│  CLI / TUI                                              │
│  OracleBridgeMain (진입점, 명령어 라우팅)                    │
│  OracleMonitorTui (Lanterna Screen 대시보드)               │
├─────────────────────────────────────────────────────────┤
│  데이터 처리                                              │
│  MetricsBuffer (Ring Buffer + Sparkline)                 │
│  WaitEventDeltaTracker (V$SYSTEM_EVENT 델타 연산)          │
├─────────────────────────────────────────────────────────┤
│  데이터 수집                                              │
│  OracleCollector (V$ 뷰 JDBC 쿼리)                       │
├─────────────────────────────────────────────────────────┤
│  인프라                                                  │
│  Oracle JDBC (ojdbc8) + Lanterna Terminal                │
└─────────────────────────────────────────────────────────┘
```

### 클래스 의존 관계

```
OracleBridgeMain (진입점)
├── OracleCollector          (CLI 명령어에서 직접 호출)
├── WaitEventDeltaTracker    (monitor 명령어)
└── OracleMonitorTui         (tui 명령어)
    ├── OracleCollector      (collectAll)
    ├── WaitEventDeltaTracker (실시간 Wait 델타)
    ├── MetricsBuffer        (Sparkline 이력)
    └── Lanterna Screen      (터미널 렌더링)
```

## 8.3 소스 파일 구조

```
java/oracle-bridge/
├── pom.xml
└── src/main/java/io/dit/oracle/
    ├── OracleBridgeMain.java      # 628줄 - 진입점, CLI 라우팅, JSON 직렬화
    ├── OracleCollector.java       # 382줄 - Oracle V$ 뷰 JDBC 쿼리 계층
    ├── OracleMonitorTui.java      # 580줄 - Lanterna TUI 대시보드 렌더링
    ├── MetricsBuffer.java         # 117줄 - Ring Buffer + Unicode Sparkline
    └── WaitEventDeltaTracker.java # 121줄 - V$SYSTEM_EVENT 델타 연산
                            합계: 1,828줄
```

## 8.4 명령어 체계

`--command` 파라미터로 9개 명령어 분기:

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
java -jar dit-dbms-bridge.jar \
    --command tui \
    --host 192.168.0.172 --port 1521 \
    --service-name DEV \
    --user system --password manager \
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
  → openConnection() → java.sql.Connection
  → OracleCollector.queryX() → Map/List
  → toJson() → JSON 문자열
  → System.out.println()
```

### TUI 수집-렌더링 파이프라인

```
Main Loop (100ms poll):
  ├─ pollInput() → handleKey() → 스크롤/선택 상태 변경
  │
  ├─ collectData() (intervalMs마다)
  │   ├─ OracleCollector.collectAll()     ← per-query 에러 격리
  │   ├─ WaitEventDeltaTracker.queryDelta() ← 실시간 Wait 델타
  │   ├─ OracleCollector.mapMetrics()     ← 메트릭 정규화
  │   ├─ MetricsBuffer.push()            ← Sparkline 이력 저장
  │   └─ synchronized(dataLock) { 데이터 저장 }
  │
  ├─ doResizeIfNecessary() → 터미널 리사이즈 감지
  │
  └─ render() (변경 시에만)
      ├─ Title Bar → 인스턴스@호스트 | 버전 | 수집시각
      ├─ Load Profile (좌 50%) → 13개 메트릭 + Sparkline
      ├─ Top Waits (우 50%) → WaitEventDelta 12개
      ├─ Sessions → 스크롤 가능한 세션 테이블
      ├─ Top SQL → CPU time 기준 SQL 목록
      └─ Footer → 키 안내, 수집 소요시간, 에러
      → screen.refresh(DELTA) → 변경분만 업데이트
```

### Monitor 명령어 파이프라인

```
While running:
  ├─ OracleCollector.querySysmetric/Sysstat/Sessions/SqlHotspots
  ├─ WaitEventDeltaTracker.queryDelta()
  ├─ OracleCollector.mapMetrics()
  ├─ JSON Frame → JSONL 파일 append (1줄/주기)
  ├─ 텍스트 스크린 → capture 파일 + stdout
  └─ sleep(intervalSeconds)
```

## 8.6 에러 처리

| 상황 | 처리 | 사용자 표시 |
|------|------|------------|
| DB 접속 실패 | JSON 에러 출력 후 exit(1) | `{"error":"SQL error: ..."}` |
| TUI 접속 실패 | stderr 출력 후 종료 | `Connection failed: ...` |
| TUI 수집 중 쿼리 실패 | 해당 메트릭 스킵, 다음 주기 재시도 | Footer ERR 표시 |
| TUI 연결 끊김 | 자동 재접속 시도 | Footer `Reconnect failed: ...` |
| collectAll() 개별 쿼리 실패 | 빈 데이터 반환, 나머지 쿼리 계속 | 해당 패널 비어있음 |
| 터미널 크기 부족 (40x10 미만) | 렌더링 스킵 | 빈 화면 |

## 8.7 빌드 및 배포

### 빌드

```bash
mvn clean package -f java/oracle-bridge/pom.xml
# 출력: java/oracle-bridge/target/dit-dbms-bridge.jar (~7.5 MB)
```

### 배포

Fat JAR 단일 파일 복사:

```bash
scp java/oracle-bridge/target/dit-dbms-bridge.jar oracle@192.168.0.172:~/
ssh oracle@192.168.0.172 'java -jar dit-dbms-bridge.jar --command tui ...'
```

### 런타임 디렉토리

```
.dit/
├── bin/
│   └── dit-dbms-bridge.jar     # 배포 JAR
└── cycles/                      # 모니터링 사이클 아카이브
```

## 8.8 설계 결정 기록 (ADR)

### ADR-1: Lanterna Screen vs Lanterna GUI vs Textual(Python)

| | Lanterna Screen (채택) | Lanterna GUI | Python Textual |
|--|----------------------|-------------|----------------|
| 장점 | 문자 단위 제어, 경량, Java 8 호환 | 위젯 추상화 | 풍부한 위젯, async |
| 단점 | 수동 레이아웃 | Java 11+ 필요 | Python 런타임 필요 |
| 결정 | Java 8 호환 + SSH 환경 + 최소 의존성 |

### ADR-2: 외부 JSON 라이브러리 미사용

- 장점: 의존성 최소화, JAR 크기 절약, 빌드 단순
- 구현: 재귀적 `toJson()` + `quote()` (Map, List, String, Number, Boolean, null 처리)

### ADR-3: V$EVENTMETRIC → V$SYSTEM_EVENT 델타

- V$EVENTMETRIC: 60초 보고 주기, 부하 중단 후에도 값 유지
- V$SYSTEM_EVENT 델타: 수집 주기(6초)에 맞는 실시간 반영, 즉시 0으로 하락
- WaitEventDeltaTracker 구현으로 해결

### ADR-4: Per-query 에러 격리

- `collectAll()`에서 각 쿼리를 개별 try-catch로 감싸서 하나의 실패가 전체를 중단하지 않음
- 실패한 메트릭은 빈 Map/List로 대체
- TUI는 null-safe하게 렌더링

### ADR-5: 자기 세션 필터링

- JDBC 프로퍼티 `v$session.program = "dit-bridge"` 설정
- Sessions 쿼리에서 `NVL(s.program, '-') <> 'dit-bridge'` 조건
- 모니터링 도구 자체 세션이 대시보드에 표시되지 않음
