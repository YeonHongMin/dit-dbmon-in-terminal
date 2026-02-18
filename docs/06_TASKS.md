# DOC-6: TASKS

## Progress Snapshot (2026-02-18)

### 완료
- Java Maven 프로젝트 구조 생성 (`java/oracle-bridge/`)
- Lanterna 3.1.3 TUI 라이브러리 통합
- Multi-DBMS 인터페이스 아키텍처 구현 (15파일, 5패키지):
  - `io.dit.bridge/` - DitMain (진입점), DbmsType (enum)
  - `io.dit.bridge.api/` - DbmsCollector, DbmsConnectionFactory, WaitDeltaTracker (인터페이스)
  - `io.dit.bridge.core/` - MetricsBuffer, JsonUtil (DBMS-agnostic)
  - `io.dit.bridge.oracle/` - OracleCollector, OracleConnectionFactory, OracleWaitDeltaTracker, OracleMonitorTui
  - `io.dit.bridge.tibero/` - TiberoCollector, TiberoConnectionFactory, TiberoWaitDeltaTracker, TiberoMonitorTui
- Oracle + Tibero DBMS 지원 (`--dbms-type oracle|tibero`)
- CLI 명령어: health, metrics, sessions, waits, sql, monitor, report, tui, kill
- TUI 대시보드 구현:
  - Load Profile sparkline (Active Sessions, DB Time/s, CPU/s, SQL Exec/s, Logical/Physical Reads/s, Redo MB/s)
  - Top Waits (Real-time): V$SYSTEM_EVENT 델타 기반, Avg(ms) + Wait Time(s)
  - Sessions: Non-idle, wait time 순, SQL text, 자기 세션 제외
  - Top SQL (10 min): elapsed_time 기준, V$/X$ 모니터링 쿼리 제외
  - 설정 가능 수집 주기 (`--interval N`, 기본 6초)
  - DELTA refresh 깜빡임 방지
  - 키 바인딩: Q/Esc, Up/Down, PgUp/PgDn, Home/End, Tab, R
- Fat JAR 빌드 (maven-shade-plugin)
- 원격 서버 배포 및 부하 테스트 검증
- 모니터링 유저 최소 권한 가이드 (DBA 권한 불요)

### 미완료 (향후 과제)
- 다중 DBMS 지원 (MySQL, PostgreSQL, SQL Server Collector)
- Alert 엔진 (WARNING/CRITICAL 상태 변화 기반)
- 히스토리 저장/재생 (recording/playback)

## 빌드 및 검증

```bash
# 빌드
mvn clean package -f java/oracle-bridge/pom.xml

# Oracle TUI
java -jar java/oracle-bridge/target/dit-dbms-monitor.jar \
  --dbms-type oracle --command tui \
  --host 192.168.0.172 --port 1521 --service-name DEV \
  --user <monitoring user> --password <user password> --interval 6

# Tibero TUI
java -jar java/oracle-bridge/target/dit-dbms-monitor.jar \
  --dbms-type tibero --command tui \
  --host 192.168.0.153 --port 8629 --service-name TPROD \
  --user <monitoring user> --password <user password> --interval 6

# CLI 테스트
java -jar dit-dbms-monitor.jar --dbms-type oracle --command health --host <host> --port 1521 --service-name <svc> --user <monitoring user> --password <user password>
```
