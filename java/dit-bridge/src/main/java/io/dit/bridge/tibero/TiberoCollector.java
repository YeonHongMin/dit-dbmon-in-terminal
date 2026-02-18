package io.dit.bridge.tibero;

import io.dit.bridge.api.DbmsCollector;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TiberoCollector implements DbmsCollector {

    // ── Internal V$SYSSTAT delta tracker (replaces V$SYSMETRIC which Tibero lacks) ──
    private Map<String, Double> prevSysstat;
    private Map<String, Double> prevTimeModel;
    private long prevTimestampMs;

    public TiberoCollector() {
    }

    // ── V$SYSSTAT snapshot ──

    public static Map<String, Double> querySysstatRaw(Connection conn) throws SQLException {
        String sql =
            "SELECT name, value FROM v$sysstat " +
            "WHERE name IN (" +
            "'logical reads', 'consistent block gets', 'current block gets', " +
            "'physical reads', 'redo log size', " +
            "'dbwr multi block writes - block count', " +
            "'the number of user commits performed', 'user rollbacks', " +
            "'parse count (total)', 'parse count (hard)', 'execute count'" +
            ")";

        Map<String, Double> out = new LinkedHashMap<String, Double>();
        Statement stmt = conn.createStatement();
        try {
            ResultSet rs = stmt.executeQuery(sql);
            try {
                while (rs.next()) {
                    String name = rs.getString(1);
                    double value = rs.getDouble(2);
                    if (!rs.wasNull() && name != null) {
                        out.put(name, value);
                    }
                }
            } finally {
                rs.close();
            }
        } finally {
            stmt.close();
        }
        return out;
    }

    // ── V$SYS_TIME_MODEL snapshot ──

    public static Map<String, Double> queryTimeModel(Connection conn) throws SQLException {
        String sql =
            "SELECT stat_name, value FROM v$sys_time_model " +
            "WHERE stat_name IN ('DB Time', 'DB CPU')";

        Map<String, Double> out = new LinkedHashMap<String, Double>();
        Statement stmt = conn.createStatement();
        try {
            ResultSet rs = stmt.executeQuery(sql);
            try {
                while (rs.next()) {
                    String name = rs.getString(1);
                    double value = rs.getDouble(2);
                    if (!rs.wasNull() && name != null) {
                        out.put(name, value);
                    }
                }
            } finally {
                rs.close();
            }
        } finally {
            stmt.close();
        }
        return out;
    }

    // ── Compute synthetic sysmetric from V$SYSSTAT + V$SYS_TIME_MODEL deltas ──

    public Map<String, Object> computeSyntheticSysmetric(Connection conn) throws SQLException {
        Map<String, Double> curSysstat = querySysstatRaw(conn);
        Map<String, Double> curTimeModel = queryTimeModel(conn);
        long nowMs = System.currentTimeMillis();

        Map<String, Object> synthetic = new LinkedHashMap<String, Object>();

        if (prevSysstat == null || prevTimeModel == null) {
            prevSysstat = curSysstat;
            prevTimeModel = curTimeModel;
            prevTimestampMs = nowMs;
            // First call: return zeros
            synthetic.put("Average Active Sessions", 0.0);
            synthetic.put("Executions Per Sec", 0.0);
            synthetic.put("Logical Reads Per Sec", 0.0);
            synthetic.put("Physical Reads Per Sec", 0.0);
            synthetic.put("Physical Writes Per Sec", 0.0);
            synthetic.put("Redo Generated Per Sec", 0.0);
            synthetic.put("Database Time Per Sec", 0.0);
            synthetic.put("CPU Usage Per Sec", 0.0);
            synthetic.put("User Commits Per Sec", 0.0);
            synthetic.put("User Rollbacks Per Sec", 0.0);
            synthetic.put("Total Parse Count Per Sec", 0.0);
            synthetic.put("Hard Parse Count Per Sec", 0.0);
            synthetic.put("Buffer Cache Hit Ratio", 0.0);
            synthetic.put("Physical Read Total Bytes Per Sec", 0.0);
            synthetic.put("Physical Write Total Bytes Per Sec", 0.0);
            return synthetic;
        }

        double elapsedSec = (nowMs - prevTimestampMs) / 1000.0;
        if (elapsedSec < 0.5) {
            elapsedSec = 1.0;
        }

        // Time model deltas (microseconds)
        double dDbTime = delta(curTimeModel, prevTimeModel, "DB Time");
        double dDbCpu = delta(curTimeModel, prevTimeModel, "DB CPU");

        // Sysstat deltas
        double dLogicalReads = delta(curSysstat, prevSysstat, "logical reads");
        double dPhysicalReads = delta(curSysstat, prevSysstat, "physical reads");
        double dPhysicalWrites = delta(curSysstat, prevSysstat, "dbwr multi block writes - block count");
        double dRedoSize = delta(curSysstat, prevSysstat, "redo log size");
        double dCommits = delta(curSysstat, prevSysstat, "the number of user commits performed");
        double dRollbacks = delta(curSysstat, prevSysstat, "user rollbacks");
        double dParseTotal = delta(curSysstat, prevSysstat, "parse count (total)");
        double dParseHard = delta(curSysstat, prevSysstat, "parse count (hard)");
        double dExecute = delta(curSysstat, prevSysstat, "execute count");

        // AAS = DB Time delta (microsec) / elapsed (sec) / 1_000_000
        double aas = dDbTime / elapsedSec / 1_000_000.0;
        // DB Time per sec = centiseconds per second
        double dbTimePerSec = dDbTime / elapsedSec / 1_000_000.0 * 100.0;
        double cpuPerSec = dDbCpu / elapsedSec / 1_000_000.0 * 100.0;

        // Buffer cache hit ratio
        double bufferHit = dLogicalReads > 0 ? (1.0 - dPhysicalReads / dLogicalReads) * 100.0 : 0.0;
        if (bufferHit < 0) bufferHit = 0.0;

        synthetic.put("Average Active Sessions", aas);
        synthetic.put("Executions Per Sec", dExecute / elapsedSec);
        synthetic.put("Logical Reads Per Sec", dLogicalReads / elapsedSec);
        synthetic.put("Physical Reads Per Sec", dPhysicalReads / elapsedSec);
        synthetic.put("Physical Writes Per Sec", dPhysicalWrites / elapsedSec);
        synthetic.put("Redo Generated Per Sec", dRedoSize / elapsedSec);
        synthetic.put("Database Time Per Sec", dbTimePerSec);
        synthetic.put("CPU Usage Per Sec", cpuPerSec);
        synthetic.put("User Commits Per Sec", dCommits / elapsedSec);
        synthetic.put("User Rollbacks Per Sec", dRollbacks / elapsedSec);
        synthetic.put("Total Parse Count Per Sec", dParseTotal / elapsedSec);
        synthetic.put("Hard Parse Count Per Sec", dParseHard / elapsedSec);
        synthetic.put("Buffer Cache Hit Ratio", bufferHit);
        synthetic.put("Physical Read Total Bytes Per Sec", dPhysicalReads * 8192.0 / elapsedSec);
        synthetic.put("Physical Write Total Bytes Per Sec", dPhysicalWrites * 8192.0 / elapsedSec);

        prevSysstat = curSysstat;
        prevTimeModel = curTimeModel;
        prevTimestampMs = nowMs;

        return synthetic;
    }

    // ── Cumulative sysstat for CLI "metrics" command ──

    public static Map<String, Object> querySysstat(Connection conn) throws SQLException {
        Map<String, Double> raw = querySysstatRaw(conn);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Double> e : raw.entrySet()) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    // ── Cumulative waits from V$SYSTEM_EVENT (Tibero column names) ──

    public static List<Map<String, Object>> queryWaits(Connection conn) throws SQLException {
        String sql =
            "SELECT * FROM (" +
            "  SELECT class, name, \"DESC\", time_waited, total_waits, " +
            "         CASE WHEN total_waits > 0 THEN time_waited * 10.0 / total_waits ELSE 0 END AS avg_wait_ms, " +
            "         time_waited * 10.0 AS wait_time_ms " +
            "  FROM v$system_event " +
            "  WHERE class <> 'STAT_CLASS_IDLE' " +
            "  AND total_waits > 0 " +
            "  ORDER BY time_waited DESC" +
            ") WHERE ROWNUM <= 12";

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Statement stmt = conn.createStatement();
        try {
            ResultSet rs = stmt.executeQuery(sql);
            try {
                while (rs.next()) {
                    String name = rs.getString(2);
                    String desc = rs.getString(3);
                    String eventLabel = (desc != null && !desc.isEmpty()) ? desc : defaultStr(name, "unknown");
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("wait_class", normalizeWaitClass(defaultStr(rs.getString(1), "Other")));
                    row.put("event", eventLabel);
                    row.put("time_waited_micro", rs.getDouble(4) * 10000.0); // centiseconds -> microseconds
                    row.put("total_waits", rs.getDouble(5));
                    row.put("avg_wait_ms", rs.getDouble(6));
                    row.put("wait_time_ms", rs.getDouble(7));
                    rows.add(row);
                }
            } finally {
                rs.close();
            }
        } finally {
            stmt.close();
        }
        return rows;
    }

    // ── Sessions (Tibero column name differences) ──

    public static List<Map<String, Object>> querySessions(Connection conn) throws SQLException {
        // Get my own SID first
        String mySid = queryMySid(conn);

        String sql =
            "SELECT * FROM (" +
            "  SELECT s.sid, s.serial#, s.username, s.status, " +
            "         CASE WHEN s.wait_event = -1 THEN 'On CPU' " +
            "              ELSE NVL((SELECT e.\"DESC\" FROM v$event_name e WHERE e.event# = s.wait_event AND ROWNUM = 1), " +
            "                       TO_CHAR(s.wait_event)) END AS wait_desc, " +
            "         CASE WHEN s.wait_event = -1 THEN 'CPU' " +
            "              ELSE NVL((SELECT e.class FROM v$event_name e WHERE e.event# = s.wait_event AND ROWNUM = 1), '') END AS wait_class, " +
            "         s.sql_id, s.prev_sql_id, " +
            "         s.sql_et, " +
            "         s.sql_et, " +
            "         s.machine, s.prog_name, " +
            "         (SELECT REPLACE(SUBSTR(q.sql_text, 1, 120), CHR(10), ' ') " +
            "          FROM v$sql q WHERE q.sql_id = COALESCE(s.sql_id, s.prev_sql_id) " +
            "          AND ROWNUM = 1) AS sql_text " +
            "  FROM v$session s " +
            "  WHERE s.type = 'WTHR' " +
            "  AND s.username IS NOT NULL " +
            "  AND s.sid <> " + mySid + " " +
            "  ORDER BY s.sql_et DESC, s.wait_time DESC" +
            ") WHERE ROWNUM <= 30";

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Statement stmt = conn.createStatement();
        try {
            ResultSet rs = stmt.executeQuery(sql);
            try {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    String rawStatus = objToStr(rs.getObject(4));
                    String status = "RUNNING".equals(rawStatus) ? "ACTIVE" : rawStatus;

                    row.put("sid", objToStr(rs.getObject(1)));
                    row.put("serial", objToStr(rs.getObject(2)));
                    row.put("username", defaultStr(rs.getString(3), "-"));
                    row.put("status", status);
                    row.put("event", defaultStr(rs.getString(5), "On CPU"));
                    row.put("blocking_sid", "");
                    row.put("wait_class", normalizeWaitClass(defaultStr(rs.getString(6), "CPU")));
                    row.put("sql_id", defaultStr(rs.getString(7), "-"));
                    row.put("prev_sql_id", defaultStr(rs.getString(8), "-"));
                    row.put("seconds_in_wait", objToDouble(rs.getObject(9)));
                    row.put("elapsed_s", objToDouble(rs.getObject(10)));
                    row.put("machine", defaultStr(rs.getString(11), "-"));
                    row.put("program", defaultStr(rs.getString(12), "-"));
                    row.put("sql_text", trimSql(rs.getString(13)));
                    rows.add(row);
                }
            } finally {
                rs.close();
            }
        } finally {
            stmt.close();
        }
        return rows;
    }

    // ── SQL hotspots from V$SQL (ROWNUM instead of FETCH FIRST) ──

    public static List<Map<String, Object>> querySqlHotspots(Connection conn) throws SQLException {
        String sql =
            "SELECT * FROM (" +
            "  SELECT sql_id, plan_hash_value, elapsed_time, cpu_time, executions, " +
            "         buffer_gets, disk_reads, rows_processed, sql_text " +
            "  FROM v$sql " +
            "  WHERE executions > 0 AND sql_id IS NOT NULL " +
            "  AND sql_text NOT LIKE '%v$%' AND sql_text NOT LIKE '%V$%' " +
            "  ORDER BY elapsed_time DESC" +
            ") WHERE ROWNUM <= 15";

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Statement stmt = conn.createStatement();
        try {
            ResultSet rs = stmt.executeQuery(sql);
            try {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("sql_id", objToStr(rs.getObject(1)));
                    row.put("plan_hash_value", defaultStr(objToStr(rs.getObject(2)), "-"));
                    row.put("elapsed_time", objToDouble(rs.getObject(3)));
                    row.put("cpu_time", objToDouble(rs.getObject(4)));
                    row.put("executions", objToDouble(rs.getObject(5)));
                    row.put("buffer_gets", objToDouble(rs.getObject(6)));
                    row.put("disk_reads", objToDouble(rs.getObject(7)));
                    row.put("rows_processed", objToDouble(rs.getObject(8)));
                    row.put("sql_text", trimSql(rs.getString(9)));
                    rows.add(row);
                }
            } finally {
                rs.close();
            }
        } finally {
            stmt.close();
        }
        return rows;
    }

    // ── Instance info ──

    public Map<String, Object> queryInstanceInfo(Connection conn) throws SQLException {
        return queryInstanceInfoStatic(conn);
    }

    public static Map<String, Object> queryInstanceInfoStatic(Connection conn) throws SQLException {
        String sql =
            "SELECT instance_name, host_name, version, status, " +
            "       TO_CHAR(startup_time, 'YYYY-MM-DD HH24:MI:SS') " +
            "FROM v$instance";

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Statement stmt = conn.createStatement();
        try {
            ResultSet rs = stmt.executeQuery(sql);
            try {
                if (rs.next()) {
                    out.put("instance_name", defaultStr(rs.getString(1), "unknown"));
                    out.put("host_name", defaultStr(rs.getString(2), "unknown"));
                    out.put("version", defaultStr(rs.getString(3), "unknown"));
                    out.put("status", defaultStr(rs.getString(4), "unknown"));
                    out.put("startup_time", defaultStr(rs.getString(5), "-"));
                }
            } finally {
                rs.close();
            }
        } finally {
            stmt.close();
        }
        return out;
    }

    // ── Server time (app time, since Tibero server time may be stale) ──

    public String queryServerTime(Connection conn) throws SQLException {
        // Use app time instead of server time (Tibero license may cause stale server clock)
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss");
        return sdf.format(new java.util.Date());
    }

    // ── My SID via V$MYSTAT ──

    public static String queryMySid(Connection conn) throws SQLException {
        Statement stmt = conn.createStatement();
        try {
            ResultSet rs = stmt.executeQuery("SELECT sid FROM v$mystat WHERE ROWNUM = 1");
            try {
                if (rs.next()) {
                    return String.valueOf(rs.getInt(1));
                }
            } finally {
                rs.close();
            }
        } finally {
            stmt.close();
        }
        return "0";
    }

    // ── Collect all data in one call ──

    public Map<String, Object> collectAll(Connection conn) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();

        try {
            data.put("server_time", queryServerTime(conn));
        } catch (SQLException e) {
            data.put("server_time", "");
        }

        try {
            data.put("instance", queryInstanceInfoStatic(conn));
        } catch (SQLException e) {
            data.put("instance", new LinkedHashMap<String, Object>());
        }

        try {
            data.put("sysmetric", computeSyntheticSysmetric(conn));
        } catch (SQLException e) {
            data.put("sysmetric", new LinkedHashMap<String, Object>());
        }

        try {
            data.put("sysstat", querySysstat(conn));
        } catch (SQLException e) {
            data.put("sysstat", new LinkedHashMap<String, Object>());
        }

        try {
            data.put("waits", queryWaits(conn));
        } catch (SQLException e) {
            data.put("waits", new ArrayList<Map<String, Object>>());
        }

        try {
            data.put("sessions", querySessions(conn));
        } catch (SQLException e) {
            data.put("sessions", new ArrayList<Map<String, Object>>());
        }

        try {
            data.put("sql_hotspots", querySqlHotspots(conn));
        } catch (SQLException e) {
            data.put("sql_hotspots", new ArrayList<Map<String, Object>>());
        }

        return data;
    }

    // ── Map sysmetric + sysstat into a flat metrics map (same keys as Oracle) ──

    public Map<String, Object> mapMetrics(Map<String, Object> sysmetric, Map<String, Object> sysstat) {
        return mapMetricsStatic(sysmetric, sysstat);
    }

    public static Map<String, Object> mapMetricsStatic(Map<String, Object> sysmetric, Map<String, Object> sysstat) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("active_sessions", dbl(sysmetric, "Average Active Sessions"));
        out.put("sql_exec_per_sec", dbl(sysmetric, "Executions Per Sec"));
        out.put("logical_reads_per_sec", dbl(sysmetric, "Logical Reads Per Sec"));
        out.put("physical_reads_per_sec", dbl(sysmetric, "Physical Reads Per Sec"));
        out.put("physical_read_mb_per_sec", dbl(sysmetric, "Physical Read Total Bytes Per Sec") / (1024.0 * 1024.0));
        out.put("physical_writes_per_sec", dbl(sysmetric, "Physical Writes Per Sec"));
        out.put("physical_write_mb_per_sec", dbl(sysmetric, "Physical Write Total Bytes Per Sec") / (1024.0 * 1024.0));
        out.put("redo_mb_per_sec", dbl(sysmetric, "Redo Generated Per Sec") / (1024.0 * 1024.0));
        double dbTimeSec = dbl(sysmetric, "Database Time Per Sec");
        double cpuTimeSec = dbl(sysmetric, "CPU Usage Per Sec");
        out.put("db_time_per_sec", dbTimeSec);
        out.put("cpu_time_per_sec", cpuTimeSec);
        out.put("wait_time_per_sec", Math.max(0.0, dbTimeSec - cpuTimeSec));
        out.put("wait_time_ratio", 0.0);
        out.put("commits_per_sec", dbl(sysmetric, "User Commits Per Sec"));
        out.put("rollbacks_per_sec", dbl(sysmetric, "User Rollbacks Per Sec"));
        out.put("tran_per_sec", dbl(sysmetric, "User Commits Per Sec") + dbl(sysmetric, "User Rollbacks Per Sec"));
        out.put("parse_total_per_sec", dbl(sysmetric, "Total Parse Count Per Sec"));
        out.put("hard_parses_per_sec", dbl(sysmetric, "Hard Parse Count Per Sec"));
        out.put("buffer_cache_hit", dbl(sysmetric, "Buffer Cache Hit Ratio"));
        out.put("execute_count", dbl(sysstat, "execute count"));
        out.put("session_logical_reads", dbl(sysstat, "logical reads"));
        out.put("redo_size", dbl(sysstat, "redo log size"));
        return out;
    }

    // ── Wait class normalization (Tibero uses STAT_CLASS_xxx) ──

    static String normalizeWaitClass(String raw) {
        if (raw == null) return "Other";
        if (raw.startsWith("STAT_CLASS_")) {
            String suffix = raw.substring("STAT_CLASS_".length());
            if (suffix.isEmpty()) return "Other";
            // "CONCURRENCY" -> "Concurrency", "USER_IO" -> "User I/O"
            if ("USER_IO".equals(suffix)) return "User I/O";
            if ("SYSTEM_IO".equals(suffix)) return "System I/O";
            // Generic: capitalize first letter, lowercase rest
            return suffix.substring(0, 1).toUpperCase() + suffix.substring(1).toLowerCase();
        }
        return raw;
    }

    // ── Helpers ──

    private static double delta(Map<String, Double> current, Map<String, Double> previous, String key) {
        Double cur = current.get(key);
        Double prev = previous.get(key);
        double c = cur != null ? cur : 0.0;
        double p = prev != null ? prev : 0.0;
        double d = c - p;
        return d >= 0 ? d : 0.0;
    }

    private static double dbl(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v == null) return 0.0;
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0.0; }
    }

    private static String objToStr(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static double objToDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v == null) return 0.0;
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0.0; }
    }

    private static String defaultStr(String v, String fallback) {
        return (v == null || v.trim().isEmpty()) ? fallback : v;
    }

    private static String trimSql(String raw) {
        if (raw == null) return "-";
        String compact = raw.replace('\n', ' ').trim();
        if (compact.isEmpty()) return "-";
        return compact.length() > 120 ? compact.substring(0, 120) : compact;
    }
}
