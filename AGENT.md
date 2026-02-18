# AGENT.md

## 목적
새 채팅 창/새 에이전트에서 프로젝트를 즉시 이어받기 위한 운영 기준 문서.

## 프로젝트 한 줄 요약
`DIT (DBmon-In-Terminal)`: GUI/Web 없이 SSH 터미널(TUI)에서 Oracle / Tibero DB를 실시간 모니터링하는 Java 기반 도구.

## 현재 합의된 요구사항
1. GUI 또는 웹서버 기동 불가(보안 정책).
2. Oracle + Tibero 모니터링 (인터페이스 기반 Multi-DBMS 아키텍처).
3. 원격/로컬 DB 모두 지원, 터미널 기반 실행 유지.
4. Java 8+ fat JAR 단일 파일 배포.
5. 그래프는 lab128 스타일(터미널 sparkline) 제공.
6. Lanterna 라이브러리 기반 인터랙티브 TUI 대시보드.
7. 모니터링 전용 유저 — DBA 권한 없이 최소 권한.

## 기술 스택
- **언어**: Java 8+ (source/target 1.8)
- **빌드**: Maven, maven-shade-plugin (fat JAR)
- **TUI**: Lanterna 3.1.3 (Screen layer)
- **DB 드라이버**: Oracle JDBC (ojdbc8), Tibero 7 JDBC (tibero7-jdbc)
- **패키지**: `io.dit.bridge`

## 아키텍처 (15 Java 파일, 5 패키지)

```
java/oracle-bridge/src/main/java/io/dit/bridge/
  DitMain.java                -- 진입점, CLI 라우팅, --dbms-type 분기
  DbmsType.java               -- DBMS 타입 enum (ORACLE, TIBERO)

  api/
    DbmsCollector.java         -- DBMS별 수집기 인터페이스
    DbmsConnectionFactory.java -- DBMS별 접속 팩토리 인터페이스
    WaitDeltaTracker.java      -- Wait Event 델타 추적 인터페이스

  core/
    MetricsBuffer.java         -- Ring Buffer (capacity=60) + Unicode Sparkline
    JsonUtil.java              -- 경량 JSON 직렬화 (외부 라이브러리 없음)

  oracle/
    OracleCollector.java       -- Oracle V$ 뷰 JDBC 쿼리
    OracleConnectionFactory.java -- Oracle JDBC 접속
    OracleWaitDeltaTracker.java  -- V$SYSTEM_EVENT 델타 연산
    OracleMonitorTui.java      -- Lanterna TUI 대시보드

  tibero/
    TiberoCollector.java       -- Tibero V$ 뷰 쿼리 + V$SYSSTAT 델타
    TiberoConnectionFactory.java -- Tibero JDBC 접속
    TiberoWaitDeltaTracker.java  -- V$SYSTEM_EVENT 델타 연산
    TiberoMonitorTui.java      -- Lanterna TUI 대시보드
```

## 모니터링 유저

DBA 권한 없이 최소 권한으로 운영:

| DBMS | 권한 |
|------|------|
| Oracle | `CREATE SESSION` + `SELECT ANY DICTIONARY` + `ALTER SYSTEM` |
| Tibero | 위 + `EXECUTE ON DBMS_APPLICATION_INFO` |

## 현재 구현 상태 (2026-02-18 기준)

### 완료
- Multi-DBMS 인터페이스 아키텍처 (Oracle + Tibero)
- Fat JAR 빌드 및 배포 파이프라인
- CLI 명령어: health, metrics, sessions, waits, sql, monitor, report, tui, kill
- TUI 대시보드:
  - Load Profile (sparkline 히스토리): Active Sessions, DB Time/s, CPU/s, SQL Exec/s, Logical/Physical Reads/s, Redo MB/s
  - Top Waits (Real-time): V$SYSTEM_EVENT 델타 기반, Avg(ms) + Wait Time(s)
  - Sessions: Non-idle 세션, wait time 순 정렬, SQL text 포함, 자기 세션 제외
  - Top SQL (10 min): elapsed_time 기준, V$/X$ 모니터링 쿼리 제외
  - 설정 가능 수집 주기: `--interval N` (기본 6초)
  - DELTA refresh로 깜빡임 방지
- 키 바인딩: Q/Esc(종료), Up/Down(세션 선택), PgUp/PgDn(스크롤), Tab(SQL 스크롤), R(새로고침)
- 부하 테스트 검증 (Oracle SOE workload, Tibero literal SQL)
- 모니터링 유저 최소 권한 가이드

### 미완료
- 다중 DBMS 지원 (MySQL, PostgreSQL, SQL Server)
- Alert 엔진
- 히스토리 저장/재생

## 실행 명령

```bash
# 빌드
mvn clean package -f java/oracle-bridge/pom.xml

# Oracle TUI
java -jar java/oracle-bridge/target/dit-dbms-monitor.jar \
  --dbms-type oracle --command tui \
  --host <host> --port 1521 --service-name <service> \
  --user <monitoring user> --password <user password> --interval 6

# Tibero TUI
java -jar java/oracle-bridge/target/dit-dbms-monitor.jar \
  --dbms-type tibero --command tui \
  --host <host> --port 8629 --service-name <dbname> \
  --user <monitoring user> --password <user password> --interval 6

# CLI 명령어 (공통)
java -jar dit-dbms-monitor.jar --dbms-type oracle --command health ...
java -jar dit-dbms-monitor.jar --dbms-type oracle --command metrics ...
java -jar dit-dbms-monitor.jar --dbms-type oracle --command sessions ...
java -jar dit-dbms-monitor.jar --dbms-type oracle --command waits ...
java -jar dit-dbms-monitor.jar --dbms-type oracle --command sql ...
java -jar dit-dbms-monitor.jar --dbms-type oracle --command monitor --interval-seconds 5 ...
```

## 테스트 환경
- Oracle DB: 192.168.0.172:1521 (DEV)
- Tibero DB: 192.168.0.153:8629 (TPROD)
- 터미널: 160x45 (1280x768, font 10)

## 구현 규칙
1. 비밀번호/토큰 평문 저장 금지.
2. Java 8 호환 유지 (diamond operator OK, lambda OK, var 불가).
3. 외부 JSON 라이브러리 미사용 (자체 `JsonUtil.toJson()`/`JsonUtil.quote()`).
4. CLI args: `--key value` pairs → `Map<String, String>`.
5. per-query error isolation: `collectAll()` 내 각 쿼리 독립 try/catch.
6. Lanterna Screen layer 사용 (GUI toolkit X).
7. `--dbms-type oracle|tibero`로 DBMS 구현체 선택.
8. 새 DBMS 추가: `DbmsCollector`, `DbmsConnectionFactory`, `WaitDeltaTracker` 인터페이스 구현.

## 새 에이전트 시작 체크리스트
1. `java/oracle-bridge/` 디렉터리 확인.
2. `mvn clean package -f java/oracle-bridge/pom.xml` 빌드 확인.
3. 수정 후 반드시 빌드 → 배포 → 테스트 사이클 수행.
4. 새 DBMS 추가 시 `api/` 인터페이스 준수, `DbmsType` enum 확장, `DitMain` 분기 추가.
