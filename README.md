# DIT (DBmon-In-Terminal)

SSH 터미널 환경에서 Oracle DB를 실시간 모니터링하는 Java 기반 TUI 도구. 단일 fat JAR (Java 8+)로 배포.

## 빠른 시작

```bash
# 빌드
mvn clean package -f java/oracle-bridge/pom.xml

# TUI 모니터 실행
java -jar java/oracle-bridge/target/dit-dbms-bridge.jar \
  --command tui \
  --host <db-host> --port 1521 \
  --service-name <service> \
  --user system --password <password> \
  --interval 6
```

## 모니터링 화면 (TUI)

Lanterna Screen 기반 Oracle TUI 대시보드. Load Profile 스파크라인, 실시간 Wait 델타, 활성 세션, Top SQL:

```text
 DIT | DEV@single19cfs | 19.0.0.0.0 | Collected: 11:40:29
┌─ Load Profile ───────────────────────────────────────────────────────────────┐┌─ Top Waits (Real-time) ──────────────────────────────────────────────────────┐
│ Active Sessions   9.66          ▂▂▃▃▃▃▃   ██▂▂▂▆▆▂                           ││ Wait Event                                            Avg(ms)    Wait Time(ms)
│ DB Time/s         966.04        ▂▂▃▃▃▃▃   ██▂▂▂▆▆▂                           ││ log file sync                                         7.17       13.0M       │
│ CPU/s             36.49                   ████████                           ││ log file parallel write                               3.90       2.6M        │
│ SQL Exec/s        2201          ▆▆███▄▄▇▇▇  ▁▁▁▂▂▃                           ││ LGWR any worker group                                 2.42       980.1K      │
│ Logical Reads/s   13069         ▅▅▅▅▅▂▂▅▅▅  ▁▁▁██▂                           ││ LGWR all worker groups                                2.98       293.9K      │
│ Phy Reads/s       0                            ██                            ││ db file async I/O submit                              2.94       245.4K      │
│ Redo MB/s         1.68          ▃▃▃▃▃▂▂▃▃▃  ▁▁▁██▁                           ││ control file parallel write                           8.72       244.7K      │
└──────────────────────────────────────────────────────────────────────────────┘└──────────────────────────────────────────────────────────────────────────────┘
┌─ Sessions (10) ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ SID    Serial  User        Status    Wait Event                  WClass      Blk  SQL ID         Wait(s) Program         SQL Text                            │
│ 25     33702   APP         ACTIVE    log file sync               Commit      5    -              0       JDBC Thin Clien SELECT LOAD_TEST_SEQ.CURRVAL FROM D │
│ 59     39879   APP         ACTIVE    log file sync               Commit      5    -              0       JDBC Thin Clien DELETE FROM LOAD_TEST WHERE ID = :1 │
│ 178    10757   APP         ACTIVE    log file sync               Commit      5    -              0       JDBC Thin Clien SELECT LOAD_TEST_SEQ.CURRVAL FROM D │
│ 179    20878   APP         ACTIVE    log file sync               Commit      5    -              0       JDBC Thin Clien DELETE FROM LOAD_TEST WHERE ID = :1 │
│ 455    63991   APP         ACTIVE    log file sync               Commit      5    -              0       JDBC Thin Clien DELETE FROM LOAD_TEST WHERE ID = :1 │
│ 379    35093   APP         ACTIVE    log file sync               Commit      5    -              0       JDBC Thin Clien DELETE FROM LOAD_TEST WHERE ID = :1 │
│ 394    39113   APP         ACTIVE    log file sync               Commit      5    -              0       JDBC Thin Clien UPDATE LOAD_TEST SET VALUE_COL = :1 │
│ 412    33665   APP         ACTIVE    log file sync               Commit      5    -              0       JDBC Thin Clien SELECT LOAD_TEST_SEQ.CURRVAL FROM D │
│ 415    64548   APP         ACTIVE    log file sync               Commit      5    -              0       JDBC Thin Clien DELETE FROM LOAD_TEST WHERE ID = :1 │
│ 277    2280    APP         ACTIVE    log file sync               Commit      5    -              0       JDBC Thin Clien DELETE FROM LOAD_TEST WHERE ID = :1 │
└──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
┌─ Top SQL (10 min) ───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ SQL ID         Plan Hash    Elapsed(ms)   CPU(ms)       Execs     Gets        SQL Text                                                                       │
│ azt6dq6t89u2w  0            60.2K         54.9K         226       3.9M        BEGIN  DBMS_WORKLOAD_REPOSITORY.CREATE_SNAPSHOT('TYPICAL');  END;               │
│ gmh99f26fu48c  2432944345   48.3K         30.6K         571.9K    4.6M        INSERT INTO LOAD_TEST (ID, THREAD_ID, VALUE_COL, RANDOM_DATA, CREATED_AT) VALU │
│ 530u0m36cx23p  3418750106   48.0K         15.2K         571.3K    5.7M        DELETE FROM LOAD_TEST WHERE ID = :1                                            │
│ 0d1xmhavvz7td  3371350541   28.6K         13.5K         571.2K    2.9M        UPDATE LOAD_TEST SET VALUE_COL = :1 , UPDATED_AT = SYSTIMESTAMP WHERE ID = :2  │
│ cfp05p0vvtc3s  4257653928   7.7K          6.4K          571.9K    4           SELECT LOAD_TEST_SEQ.CURRVAL FROM DUAL                                         │
│ 2hd8pmd24x9w2  1234739299   20.8K         5.6K          571.1K    2.0M        SELECT ID, THREAD_ID, VALUE_COL FROM LOAD_TEST WHERE ID = :1                   │
│ 6ajkhukk78nsr  0            4.7K          4.5K          226       302.5K      begin prvt_hdm.auto_execute( :dbid, :inst_num , :end_snap_id ); end;           │
└──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
 Q:Quit  Up/Down:Navigate  PgUp/PgDn:Scroll | Interval: 6s  Collect: 225ms
```

## 키 바인딩

| 키 | 동작 |
|----|------|
| `Q` / `Esc` | 종료 |
| `Up` / `Down` | 세션 선택 |
| `PgUp` / `PgDn` | 세션 스크롤 (10행) |
| `Home` / `End` | 첫/마지막 세션 이동 |
| `Tab` | SQL 패널 스크롤 |
| `R` | 강제 새로고침 |

## CLI 명령어

```bash
# 접속 확인
java -jar dit-dbms-bridge.jar --command health --host <host> --port 1521 --service-name <svc> --user <user> --password <pwd>

# 메트릭 스냅샷 (JSON)
java -jar dit-dbms-bridge.jar --command metrics ...

# 활성 세션 목록 (JSON)
java -jar dit-dbms-bridge.jar --command sessions ...

# 대기 이벤트 (JSON)
java -jar dit-dbms-bridge.jar --command waits ...

# SQL 핫스팟 (JSON)
java -jar dit-dbms-bridge.jar --command sql ...

# 연속 모니터링 + JSONL 녹화
java -jar dit-dbms-bridge.jar --command monitor --interval 5 --record-file run.jsonl ...

# 녹화 파일 리포트 (Markdown)
java -jar dit-dbms-bridge.jar --command report --record-file run.jsonl --output report.md

# 세션 강제 종료
java -jar dit-dbms-bridge.jar --command kill --sid <sid,serial#> ...
```

## 아키텍처

```
java/oracle-bridge/src/main/java/io/dit/oracle/
  OracleBridgeMain.java      -- 진입점, CLI 라우팅, JSON 직렬화
  OracleCollector.java       -- Oracle V$ 뷰 JDBC 쿼리 (7개 쿼리 메서드)
  OracleMonitorTui.java      -- Lanterna Screen 기반 TUI 대시보드 렌더링
  MetricsBuffer.java         -- Ring Buffer (capacity=60) + Unicode Sparkline
  WaitEventDeltaTracker.java -- V$SYSTEM_EVENT 델타 연산 (실시간 Wait)
```

## TUI 기능

- **Load Profile**: AAS, DB Time/s, CPU/s, SQL Exec/s, Logical/Physical R/W/s, Redo MB/s 등 13개 메트릭 + 스파크라인 이력
- **Top Waits (Real-time)**: V$SYSTEM_EVENT 델타 기반, 수집 주기(6초)에 맞는 실시간 반영
- **Sessions**: 비유휴 활성 세션, wait time 기준 정렬, blocker SID 표시, SQL text 포함
- **Top SQL (10 min)**: CPU time 기준 상위 SQL, sql_id + plan_hash_value, V$/X$ 쿼리 제외
- **자기 세션 필터링**: 모니터링 도구 자체 세션이 대시보드에 표시되지 않음
- **수집 주기 설정**: `--interval N` (기본 6초)
- **DB 서버 시각**: 타이틀바에 DB SYSDATE 수집 시각 표시

## 빌드

```bash
mvn clean package -f java/oracle-bridge/pom.xml
```

출력: `java/oracle-bridge/target/dit-dbms-bridge.jar` (fat JAR, Oracle JDBC + Lanterna 포함, ~7.5 MB)

## 기술 스택

| 항목 | 기술 | 버전 |
|------|------|------|
| 언어 | Java | 8+ |
| 빌드 | Maven + maven-shade-plugin | 3.x |
| TUI | Lanterna (Screen layer) | 3.1.3 |
| DB 드라이버 | Oracle JDBC (ojdbc8) | 23.3.0.23.09 |
| 패키지 | `io.dit.oracle` | 0.1.0 |
