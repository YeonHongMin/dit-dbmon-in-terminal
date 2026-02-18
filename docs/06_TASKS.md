# DOC-6: TASKS

## Progress Snapshot (2026-02-17)

### 완료
- Java Maven 프로젝트 구조 생성 (`java/oracle-bridge/`)
- Lanterna 3.1.3 TUI 라이브러리 통합
- 4파일 아키텍처 구현:
  - `OracleBridgeMain.java` - CLI entry point, 명령어 라우팅
  - `OracleCollector.java` - 모든 JDBC 쿼리 (V$SYSMETRIC, V$SESSION, V$SQL, V$EVENTMETRIC 등)
  - `MetricsBuffer.java` - Ring buffer (capacity=60), sparkline
  - `OracleMonitorTui.java` - Lanterna Screen 기반 TUI
- CLI 명령어: health, metrics, sessions, waits, sql, monitor, report, tui, kill
- TUI 대시보드 구현:
  - Load Profile sparkline (Active Sessions, DB Time/s, CPU/s, SQL Exec/s, Logical/Physical Reads/s, Redo MB/s)
  - Top Waits (Real-time): V$EVENTMETRIC, Avg(ms) + Wait Time(ms)
  - Sessions: Non-idle, wait time 순, SQL text, 자기 세션 제외
  - Top SQL (10 min): CPU time 기준, V$/X$ 모니터링 쿼리 제외
  - 설정 가능 수집 주기 (`--interval N`, 기본 6초)
  - DB 서버 시간(SYSDATE) 표시
  - DELTA refresh 깜빡임 방지
  - 키 바인딩: Q/Esc, Up/Down, PgUp/PgDn, Tab
- Fat JAR 빌드 (maven-shade-plugin)
- 원격 서버 배포 및 부하 테스트 검증

### 미완료 (향후 과제)
- 다중 DBMS 지원 (MySQL, PostgreSQL, SQL Server Collector)
- Alert 엔진 (WARNING/CRITICAL 상태 변화 기반)
- 히스토리 저장/재생 (recording/playback)
- 설정 관리 시스템 (config.yaml, keyring)
- SQLite 스토리지 (히스토리 DB)

## 빌드 및 검증

```bash
# 빌드
mvn clean package -f java/oracle-bridge/pom.xml

# TUI 실행
java -jar java/oracle-bridge/target/dit-dbms-bridge.jar \
  --command tui \
  --host 192.168.0.172 --port 1521 --service-name DEV \
  --user system --password manager --interval 6

# CLI 테스트
java -jar dit-dbms-bridge.jar --command health --host <host> --port 1521 --service-name <svc> --user <user> --password <pwd>
```
