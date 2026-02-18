# DIT (DBmon-In-Terminal)

Terminal-native Oracle database monitoring tool. Single fat JAR (Java 8+) with interactive TUI dashboard.

## Quick start

```bash
# Build
mvn clean package -f java/oracle-bridge/pom.xml

# Run TUI monitor
java -jar java/oracle-bridge/target/dit-dbms-bridge.jar \
  --command tui \
  --host <db-host> --port 1521 \
  --service-name <service> \
  --user system --password <password> \
  --interval 6
```

## Monitoring Screen (TUI)

Oracle TUI monitor with Lanterna (Java). Load profile sparklines, real-time waits, active sessions, top SQL:

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

## Key bindings

- `Q` / `Esc`: quit
- `Up` / `Down`: select session
- `PgUp` / `PgDn`: scroll sessions (10 rows)
- `Home` / `End`: jump to first/last session
- `Tab`: scroll SQL panel
- `R`: force refresh

## CLI commands

```bash
# Health check
java -jar dit-dbms-bridge.jar --command health --host <host> --port 1521 --service-name <svc> --user <user> --password <pwd>

# Metrics snapshot
java -jar dit-dbms-bridge.jar --command metrics ...

# Active sessions
java -jar dit-dbms-bridge.jar --command sessions ...

# Wait events
java -jar dit-dbms-bridge.jar --command waits ...

# SQL hotspots
java -jar dit-dbms-bridge.jar --command sql ...

# Monitor (continuous JSON output)
java -jar dit-dbms-bridge.jar --command monitor --interval 5 ...
```

## Architecture

```
java/oracle-bridge/src/main/java/io/dit/oracle/
  OracleBridgeMain.java   -- Entry point, CLI routing
  OracleCollector.java    -- All JDBC queries (V$SYSMETRIC, V$SESSION, V$SQL, V$EVENTMETRIC, etc.)
  MetricsBuffer.java      -- Ring buffer (capacity=60) for sparkline history
  OracleMonitorTui.java   -- Lanterna Screen-based TUI rendering
```

## TUI features

- **Load Profile**: Active Sessions, DB Time/s, CPU/s, SQL Exec/s, Logical/Physical Reads/s, Redo MB/s with sparkline history
- **Top Waits (Real-time)**: V$EVENTMETRIC based, Avg(ms) + Wait Time(ms)
- **Sessions**: Non-idle sessions sorted by wait time, with SQL text, excludes monitoring session
- **Top SQL (10 min)**: Recent SQL by CPU time, excludes V$/X$ monitoring queries
- **Configurable interval**: `--interval N` (default 6 seconds)
- **DB server time**: Title bar shows DB SYSDATE collection timestamp

## Build

```bash
mvn clean package -f java/oracle-bridge/pom.xml
```

Output: `java/oracle-bridge/target/dit-dbms-bridge.jar` (fat JAR with Oracle JDBC + Lanterna bundled)
