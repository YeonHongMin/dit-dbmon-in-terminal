# DIT (DBmon-In-Terminal)

SSH 터미널 환경에서 Oracle / Tibero DB를 실시간 모니터링하는 Java 기반 TUI 도구. 단일 fat JAR (Java 8+)로 배포.

## 지원 DBMS

| DBMS | 상태 | 비고 |
|------|------|------|
| Oracle | ✅ | 19c 이상 검증 |
| Tibero | ✅ | Tibero 7 검증 |

## 빠른 시작

```bash
# 빌드
mvn clean package -f java/oracle-bridge/pom.xml

# Oracle TUI
java -jar java/oracle-bridge/target/dit-dbms-monitor.jar \
  --dbms-type oracle --command tui \
  --host <db-host> --port 1521 \
  --service-name <service> \
  --user <monitoring user> --password <user password>

# Tibero TUI
java -jar java/oracle-bridge/target/dit-dbms-monitor.jar \
  --dbms-type tibero --command tui \
  --host <db-host> --port 8629 \
  --service-name <dbname> \
  --user <monitoring user> --password <user password>
```

## 모니터링 유저 생성

DBA 권한이 아닌 최소 권한으로 모니터링 전용 유저를 생성합니다.

### Oracle

```sql
-- system 유저로 실행
CREATE USER <monitoring user> IDENTIFIED BY <user password> DEFAULT TABLESPACE users TEMPORARY TABLESPACE temp;
ALTER USER <monitoring user> QUOTA 0 ON users;

GRANT CREATE SESSION TO <monitoring user>;
GRANT SELECT ANY DICTIONARY TO <monitoring user>;  -- V$ 뷰 조회
GRANT ALTER SYSTEM TO <monitoring user>;           -- kill session 용 (선택)
```

### Tibero

```sql
-- sys 유저로 실행
CREATE USER <monitoring user> IDENTIFIED BY <user password> DEFAULT TABLESPACE USR TEMPORARY TABLESPACE TEMP;

GRANT CREATE SESSION TO <monitoring user>;
GRANT SELECT ANY DICTIONARY TO <monitoring user>;      -- V$ 뷰 조회
GRANT EXECUTE ON DBMS_APPLICATION_INFO TO <monitoring user>;  -- 세션 식별
GRANT ALTER SYSTEM TO <monitoring user>;                -- kill session 용 (선택)
```

### 필요 V$ 뷰

| V$ 뷰 | Oracle | Tibero | 용도 |
|--------|--------|--------|------|
| V$SYSMETRIC | O | - | Load Profile (실시간 rate) |
| V$SYSSTAT | O | O | 누적 성능 카운터 |
| V$SYS_TIME_MODEL | - | O | DB Time, DB CPU |
| V$SESSION | O | O | 활성 세션 |
| V$SQL | O | O | Top SQL |
| V$SYSTEM_EVENT | O | O | Wait Event |
| V$INSTANCE | O | O | 인스턴스 정보 |
| V$EVENT_NAME | - | O | Wait Event 이름/클래스 |
| V$MYSTAT | - | O | 자기 SID 식별 |

## 모니터링 화면 (TUI)

Lanterna Screen 기반 대시보드. Load Profile 스파크라인, 실시간 Wait 델타, 활성 세션, Top SQL:

```text
DIT | DEV@single19cfs | 19.0.0.0.0 | Collected: 18:19:13
┌─ Load Profile ────────────────────────────────────────────────────────────────┐┌─ Top Waits (Real-time) ───────────────────────────────────────────────────────┐
│ Active Sessions   13.97           ▄▄▄▄▄▄▄▄▄▄▄▁▁   ▁▁▄▄▄▄▄▄▄▄▄▄▄▄▄▂▂   ▃▃██    ││ Wait Event                                             Avg(ms)    Wait Time(s)│
│ DB Time/s         1,397.42        ▄▄▄▄▄▄▄▄▄▄▄▁▁   ▁▁▄▄▄▄▄▄▄▄▄▄▄▄▄▂▂   ▃▃██    ││ log file sync                                          11.43      11.87       │
│ CPU Time/s        86.69 (6%)              ▆▆▆▆▆▆▆▆▆▆▄▄▄▄▄▄▄▄▄▄██████████▄▄    ││ log file parallel write                                6.82       1.63        │
│ Wait Time/s       1,310.73 (94%)  ▄▄▄▄▄▄▄▄▃▃▃▁▁     ▃▃▃▄▄▄▄▄▄▄▃▃▃▁▁   ▂▂██    ││ LGWR any worker group                                  3.81       0.72        │
│ Logical Reads/s   421,161         ▂▃▃▃▃▃▄▄▅▅▅▄▄   ▃▃▆▆▆▅▅▅▅▅▅▅▅▅▅▃▃   ▂▂██    ││ LGWR all worker groups                                 6.08       0.21        │
│ Tran/s            438                     ▅▅▅▅▅▅▅▅▅▅▄▄▄▄▄▄▄▄▄▄██████████▄▄    ││ db file scattered read                                 1.77       0.10        │
│ SQL Exec/s        28,670          ▂▃▃▃▃▃▄▄▅▅▅▄▄   ▃▃▆▆▆▅▅▅▅▅▅▅▅▅▅▃▃   ▂▂██    ││ db file sequential read                                0.62       0.07        │
│ Parse Total/s     886                     ▅▅▅▅▅▅▅▅▅▅▄▄▄▄▄▄▄▄▄▄██████████▄▄    ││ LGWR worker group ordering                             1.50       0.06        │
│ Hard Parse/s      0               ████████                                    ││ library cache: mutex X                                 4.83       0.01        │
│ Phy Reads/s       636             █▄▄▄▃▃▂▂▂▂▂▁▁     ▁▁▁                       ││ control file parallel write                            8.87       0.01        │
│ Phy Read MB/s     2.63            ▅▅▅▅▅▅▅▅██████████▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁      ││ buffer busy waits                                      0.52       0.00        │
│ Phy Write MB/s    2.63                    ▅▅▅▅▅▅▅▅▅▅▄▄▄▄▄▄▄▄▄▄██████████▄▄    ││ SQL*Net message to client                              0.00       0.00        │
│ Redo MB/s         5.74            ▂▂▂▂▃▃▄▄▅▅▅▄▄   ▃▃▆▆▆▅▅▅▅▅▅▅▅▅▅▃▃   ▂▂██    ││ cursor: pin S                                          1.19       0.00        │
└───────────────────────────────────────────────────────────────────────────────┘└───────────────────────────────────────────────────────────────────────────────┘
┌─ Sessions (16) ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ SID    Serial  User        Status    Wait Event                  WClass      Blk  SQL ID         Wait(s) Program         SQL Text                              │
│ 16     34533   SOE         ACTIVE    log file sync               Commit      5    -              0       JDBC Thin Clien BEGIN :1 := orderentry.neworder(:2 ,: │
│ 402    14785   SOE         ACTIVE    log file sync               Commit      5    -              0       JDBC Thin Clien BEGIN :1 := orderentry.updateCustomer │
│ 20     35420   SOE         ACTIVE    log file sync               Commit      5    -              0       JDBC Thin Clien BEGIN :1 := orderentry.neworder(:2 ,: │
│ 141    16042   SOE         ACTIVE    log file sync               Commit      5    -              0       JDBC Thin Clien BEGIN :1 := orderentry.neworder(:2 ,: │
│ 142    61017   SOE         ACTIVE    log file sync               Commit      5    -              0       JDBC Thin Clien BEGIN :1 := orderentry.browseandupdat │
│ 143    61174   SOE         ACTIVE    log file sync               Commit      5    -              0       JDBC Thin Clien BEGIN :1 := orderentry.neworder(:2 ,: │
│ 145    55422   SOE         ACTIVE    log file sync               Commit      5    -              0       JDBC Thin Clien BEGIN :1 := orderentry.neworder(:2 ,: │
│ 272    64022   SOE         ACTIVE    log file sync               Commit      5    -              0       JDBC Thin Clien BEGIN :1 := orderentry.neworder(:2 ,: │
└────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
┌─ Top SQL (10 min) ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ SQL ID         Plan Hash    Elapsed(ms)   CPU(ms)       Execs     Gets        SQL Text                                                                         │
│ 0w2qpuc6u2zsp  0            282,236       121,863       82,252    44,589,839  BEGIN :1 := orderentry.neworder(:2 ,:3 ,:4 ); END;                               │
│ f7rxuxzt64k87  0            89,645        14,309        248,668   3,926,949   INSERT INTO ORDER_ITEMS ( ORDER_ID, LINE_ITEM_ID, PRODUCT_ID, UNIT_PRICE, QUANTI │
│ 147a57cxq3w5y  0            84,673        65,239        102,194   12,428,470  BEGIN :1 := orderentry.browseproducts(:2 ,:3 ,:4 ); END;                         │
│ c13sma6rkr27c  1206466372   50,926        39,559        984,540   34,246,586  SELECT PRODUCTS.PRODUCT_ID, PRODUCT_NAME, PRODUCT_DESCRIPTION, CATEGORY_ID, WEIG │
│ apgb2g9q2zjh1  0            50,573        5,978         10,243    337,456     BEGIN :1 := orderentry.browseandupdateorders(:2 ,:3 ,:4 ); END;                  │
│ 0y1prvxqc2ra9  3686042051   48,594        37,766        1,230,754 12,426,686  SELECT PRODUCTS.PRODUCT_ID, PRODUCT_NAME, PRODUCT_DESCRIPTION, CATEGORY_ID, WEIG │
│ 01jzc2mg6cg92  0            47,948        13,394        30,749    1,478,655   BEGIN :1 := orderentry.newcustomer(:2 ,:3 ,:4 ,:5 ,:6 ,:7 ,:8 ,:9 ,:10 ); END;   │
│ 3fw75k1snsddx  494735477    47,083        10,376        82,240    1,535,106   INSERT INTO ORDERS ( ORDER_ID, ORDER_DATE, ORDER_MODE, CUSTOMER_ID, ORDER_STATUS │
│ azt6dq6t89u2w  0            26,609        19,496        93        1,605,313   BEGIN       DBMS_WORKLOAD_REPOSITORY.CREATE_SNAPSHOT('TYPICAL');         END;    │
└─gh2g2tynpcpv1  0            23,162        3,620         30,262    597,342     INSERT INTO CUSTOMERS ( CUSTOMER_ID , CUST_FIRST_NAME , CUST_LAST_NAME , NLS_LAN─┘

Q:Quit  Up/Down:Navigate  PgUp/PgDn:Scroll | Interval: 6s  Collect: 156ms
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
# 공통 인자: --dbms-type oracle|tibero --host --port --service-name --user --password

# 접속 확인
java -jar dit-dbms-monitor.jar --dbms-type oracle --command health ...

# 메트릭 스냅샷 (JSON)
java -jar dit-dbms-monitor.jar --dbms-type oracle --command metrics ...

# 활성 세션 목록 (JSON)
java -jar dit-dbms-monitor.jar --dbms-type oracle --command sessions ...

# 대기 이벤트 (JSON)
java -jar dit-dbms-monitor.jar --dbms-type oracle --command waits ...

# SQL 핫스팟 (JSON)
java -jar dit-dbms-monitor.jar --dbms-type oracle --command sql ...

# 연속 모니터링 + JSONL 녹화
java -jar dit-dbms-monitor.jar --dbms-type oracle --command monitor \
  --interval-seconds 5 --record-file run.jsonl --capture-file screen.txt ...

# TUI 대시보드
java -jar dit-dbms-monitor.jar --dbms-type oracle --command tui --interval 6 ...

# 세션 강제 종료
java -jar dit-dbms-monitor.jar --dbms-type oracle --command kill --sid <sid,serial#> ...

# 녹화 파일 리포트 (Markdown)
java -jar dit-dbms-monitor.jar --command report --record-file run.jsonl --output report.md
```

## 아키텍처

```
java/oracle-bridge/src/main/java/io/dit/bridge/
  DitMain.java                -- 진입점, CLI 라우팅, JSON 직렬화
  DbmsType.java               -- DBMS 타입 enum (ORACLE, TIBERO, ...)

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

## 빌드

```bash
mvn clean package -f java/oracle-bridge/pom.xml
```

출력: `java/oracle-bridge/target/dit-dbms-monitor.jar` (fat JAR, Oracle JDBC + Tibero JDBC + Lanterna 포함)

## 기술 스택

| 항목 | 기술 | 버전 |
|------|------|------|
| 언어 | Java | 8+ |
| 빌드 | Maven + maven-shade-plugin | 3.x |
| TUI | Lanterna (Screen layer) | 3.1.3 |
| Oracle 드라이버 | ojdbc8 | 23.3.0.23.09 |
| Tibero 드라이버 | tibero7-jdbc | 7.0 |
| 패키지 | `io.dit.bridge` | 0.1.0 |
