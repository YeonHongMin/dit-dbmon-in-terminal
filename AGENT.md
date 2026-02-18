# AGENT.md

## 목적
새 채팅 창/새 에이전트에서 프로젝트를 즉시 이어받기 위한 운영 기준 문서.

## 프로젝트 한 줄 요약
`DIT (DBmon-In-Terminal)`: GUI/Web 없이 SSH 터미널(TUI)에서 Oracle DB 모니터링을 수행하는 Java 기반 도구.

## 현재 합의된 요구사항
1. GUI 또는 웹서버 기동 불가(보안 정책).
2. 단일 Oracle DB 인스턴스 모니터링 (추후 다중 DBMS 확장 가능).
3. 원격/로컬 DB 모두 지원, 터미널 기반 실행 유지.
4. Java 8+ fat JAR 단일 파일 배포.
5. 그래프는 lab128 스타일(터미널 sparkline) 제공.
6. Lanterna 라이브러리 기반 인터랙티브 TUI 대시보드.

## 기술 스택
- **언어**: Java 8+ (source/target 1.8)
- **빌드**: Maven, maven-shade-plugin (fat JAR)
- **TUI**: Lanterna 3.1.3 (Screen layer)
- **DB 드라이버**: Oracle JDBC (ojdbc8)
- **패키지**: `io.dit.oracle`

## 아키텍처 (4 Java 파일)

```
java/oracle-bridge/src/main/java/io/dit/oracle/
  OracleBridgeMain.java   -- Entry point, CLI 라우팅 (~600줄)
  OracleCollector.java    -- 모든 JDBC 쿼리 (V$SYSMETRIC, V$SESSION, V$SQL 등)
  MetricsBuffer.java      -- Ring buffer (capacity=60), sparkline (Unicode block chars)
  OracleMonitorTui.java   -- Lanterna Screen 기반 TUI 렌더링
```

## 현재 구현 상태 (2026-02-17 기준)

### 완료
- Fat JAR 빌드 및 배포 파이프라인
- CLI 명령어: health, metrics, sessions, waits, sql, monitor, report, tui, kill
- TUI 대시보드:
  - Load Profile (sparkline 히스토리): Active Sessions, DB Time/s, CPU/s, SQL Exec/s, Logical/Physical Reads/s, Redo MB/s
  - Top Waits (Real-time): V$EVENTMETRIC 기반, Avg(ms) + Wait Time(ms)
  - Sessions: Non-idle 세션, wait time 순 정렬, SQL text 포함, 자기 세션 제외
  - Top SQL (10 min): CPU time 기준, V$/X$ 모니터링 쿼리 제외
  - 설정 가능 수집 주기: `--interval N` (기본 6초)
  - DB 서버 시간 표시 (SYSDATE)
  - DELTA refresh로 깜빡임 방지
- 키 바인딩: Q/Esc(종료), Up/Down(세션 선택), PgUp/PgDn(스크롤), Tab(SQL 스크롤)
- 부하 테스트 검증 (multi-db-load-tester-0.2.4.jar)

### 미완료
- 다중 DBMS 지원 (MySQL, PostgreSQL, SQL Server)
- Alert 엔진
- 히스토리 저장/재생

## 실행 명령

```bash
# 빌드
mvn clean package -f java/oracle-bridge/pom.xml

# TUI 모니터
java -jar java/oracle-bridge/target/dit-dbms-bridge.jar \
  --command tui \
  --host <host> --port 1521 --service-name <service> \
  --user system --password <password> \
  --interval 6

# CLI 명령어
java -jar dit-dbms-bridge.jar --command health ...
java -jar dit-dbms-bridge.jar --command metrics ...
java -jar dit-dbms-bridge.jar --command sessions ...
java -jar dit-dbms-bridge.jar --command waits ...
java -jar dit-dbms-bridge.jar --command sql ...
java -jar dit-dbms-bridge.jar --command monitor --interval 5 ...
```

## 테스트 환경
- SSH: 192.168.0.172 (oracle/oracle)
- Oracle DB: DEV, port 1521
- DB 유저: app/app (부하), system/manager (모니터링)
- 터미널: 160x45 (1280x768, font 10)
- 부하 도구: multi-db-load-tester-0.2.4.jar

## 구현 규칙
1. 비밀번호/토큰 평문 저장 금지.
2. Java 8 호환 유지 (diamond operator OK, lambda OK, var 불가).
3. 외부 JSON 라이브러리 미사용 (자체 `toJson()`/`quote()`).
4. CLI args: `--key value` pairs → `Map<String, String>`.
5. per-query error isolation: `collectAll()` 내 각 쿼리 독립 try/catch.
6. Lanterna Screen layer 사용 (GUI toolkit X).

## 새 에이전트 시작 체크리스트
1. `java/oracle-bridge/` 디렉터리 확인.
2. `mvn clean package -f java/oracle-bridge/pom.xml` 빌드 확인.
3. 수정 후 반드시 빌드 → 배포 → 테스트 사이클 수행.
