package io.dit.bridge.oracle;

import io.dit.bridge.api.DbmsCollector;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All Oracle JDBC queries extracted from OracleBridgeMain, plus enhanced queries
 * for real-time TUI monitoring (V$OSSTAT, V$EVENTMETRIC, V$RESOURCE_LIMIT, blocker chains).
 */
public final class OracleCollector implements DbmsCollector {

    // ── V$SYSSTAT delta tracker ──
    private Map<String, Double> prevSysstat;
    private long prevSysstatTimestampMs;

    public OracleCollector() {
    }

    // ── Load Profile metrics from V$SYSMETRIC ──

    public static Map<String, Object> querySysmetric(Connection conn) throws SQLException {
        String sql =
            "SELECT metric_name, value " +
            "FROM (" +
            "  SELECT metric_name, value, intsize_csec, " +
            "         ROW_NUMBER() OVER (PARTITION BY metric_name ORDER BY intsize_csec ASC) rn " +
            "  FROM v$sysmetric" +
            ") x " +
            "WHERE rn = 1 AND metric_name IN (" +
            "  'Host CPU Utilization (%)'," +
            "  'Average Active Sessions'," +
            "  'Database Time Per Sec'," +
            "  'CPU Usage Per Sec'," +
            "  'Database Wait Time Ratio'" +
            ")";

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Statement stmt = conn.createStatement();
        try {
            ResultSet rs = stmt.executeQuery(sql);
            try {
                while (rs.next()) {
                    String name = rs.getString(1);
                    double value = rs.getDouble(2);
                    if (!rs.wasNull()) {
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

    // ── Cumulative counters from V$SYSSTAT ──

    public static Map<String, Object> querySysstat(Connection conn) throws SQLException {
        String sql =
            "SELECT name, value FROM v$sysstat " +
            "WHERE name IN ('execute count','session logical reads','physical reads','physical writes'," +
            "'redo size','user commits','user rollbacks'," +
            "'parse count (total)','parse count (hard)'," +
            "'physical read total bytes','physical write total bytes')";

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Statement stmt = conn.createStatement();
        try {
            ResultSet rs = stmt.executeQuery(sql);
            try {
                while (rs.next()) {
                    String name = rs.getString(1);
                    double value = rs.getDouble(2);
                    if (!rs.wasNull()) {
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

    // ── Compute delta rates from V$SYSSTAT snapshots ──

    public Map<String, Object> computeSysstatDelta(Map<String, Object> curSysstat) {
        Map<String, Double> cur = toDoubleMap(curSysstat);
        long nowMs = System.currentTimeMillis();
        Map<String, Object> rates = new LinkedHashMap<String, Object>();

        if (prevSysstat == null) {
            prevSysstat = cur;
            prevSysstatTimestampMs = nowMs;
            rates.put("Executions Per Sec", 0.0);
            rates.put("Logical Reads Per Sec", 0.0);
            rates.put("Physical Reads Per Sec", 0.0);
            rates.put("Physical Writes Per Sec", 0.0);
            rates.put("Redo Generated Per Sec", 0.0);
            rates.put("User Commits Per Sec", 0.0);
            rates.put("User Rollbacks Per Sec", 0.0);
            rates.put("Total Parse Count Per Sec", 0.0);
            rates.put("Hard Parse Count Per Sec", 0.0);
            rates.put("Buffer Cache Hit Ratio", 0.0);
            rates.put("Physical Read Total Bytes Per Sec", 0.0);
            rates.put("Physical Write Total Bytes Per Sec", 0.0);
            return rates;
        }

        double elapsedSec = (nowMs - prevSysstatTimestampMs) / 1000.0;
        if (elapsedSec < 0.5) elapsedSec = 1.0;

        double dExecute = delta(cur, prevSysstat, "execute count");
        double dLogicalReads = delta(cur, prevSysstat, "session logical reads");
        double dPhysicalReads = delta(cur, prevSysstat, "physical reads");
        double dPhysicalWrites = delta(cur, prevSysstat, "physical writes");
        double dRedoSize = delta(cur, prevSysstat, "redo size");
        double dCommits = delta(cur, prevSysstat, "user commits");
        double dRollbacks = delta(cur, prevSysstat, "user rollbacks");
        double dParseTotal = delta(cur, prevSysstat, "parse count (total)");
        double dParseHard = delta(cur, prevSysstat, "parse count (hard)");
        double dReadBytes = delta(cur, prevSysstat, "physical read total bytes");
        double dWriteBytes = delta(cur, prevSysstat, "physical write total bytes");

        double bufferHit = dLogicalReads > 0 ? (1.0 - dPhysicalReads / dLogicalReads) * 100.0 : 0.0;
        if (bufferHit < 0) bufferHit = 0.0;

        rates.put("Executions Per Sec", dExecute / elapsedSec);
        rates.put("Logical Reads Per Sec", dLogicalReads / elapsedSec);
        rates.put("Physical Reads Per Sec", dPhysicalReads / elapsedSec);
        rates.put("Physical Writes Per Sec", dPhysicalWrites / elapsedSec);
        rates.put("Redo Generated Per Sec", dRedoSize / elapsedSec);
        rates.put("User Commits Per Sec", dCommits / elapsedSec);
        rates.put("User Rollbacks Per Sec", dRollbacks / elapsedSec);
        rates.put("Total Parse Count Per Sec", dParseTotal / elapsedSec);
        rates.put("Hard Parse Count Per Sec", dParseHard / elapsedSec);
        rates.put("Buffer Cache Hit Ratio", bufferHit);
        rates.put("Physical Read Total Bytes Per Sec", dReadBytes / elapsedSec);
        rates.put("Physical Write Total Bytes Per Sec", dWriteBytes / elapsedSec);

        prevSysstat = cur;
        prevSysstatTimestampMs = nowMs;

        return rates;
    }

    private static double delta(Map<String, Double> current, Map<String, Double> previous, String key) {
        Double cur = current.get(key);
        Double prev = previous.get(key);
        double c = cur != null ? cur : 0.0;
        double p = prev != null ? prev : 0.0;
        double d = c - p;
        return d >= 0 ? d : 0.0;
    }

    private static Map<String, Double> toDoubleMap(Map<String, Object> src) {
        Map<String, Double> out = new LinkedHashMap<String, Double>();
        for (Map.Entry<String, Object> e : src.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Number) {
                out.put(e.getKey(), ((Number) v).doubleValue());
            }
        }
        return out;
    }

    // ── Cumulative waits from V$SYSTEM_EVENT (fallback) ──

    public static List<Map<String, Object>> queryWaits(Connection conn) throws SQLException {
        String sql =
            "SELECT wait_class, event, time_waited_micro, total_waits, " +
            "       CASE WHEN total_waits > 0 THEN time_waited_micro / total_waits / 1000.0 ELSE 0 END AS avg_wait_ms, " +
            "       time_waited_micro / 1000.0 AS wait_time_ms " +
            "FROM v$system_event " +
            "WHERE wait_class <> 'Idle' ORDER BY time_waited_micro DESC FETCH FIRST 13 ROWS ONLY";

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Statement stmt = conn.createStatement();
        try {
            ResultSet rs = stmt.executeQuery(sql);
            try {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("wait_class", defaultStr(rs.getString(1), "Other"));
                    row.put("event", defaultStr(rs.getString(2), "unknown"));
                    row.put("time_waited_micro", rs.getDouble(3));
                    row.put("total_waits", rs.getDouble(4));
                    row.put("avg_wait_ms", rs.getDouble(5));
                    row.put("wait_time_ms", rs.getDouble(6));
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

    // ── Sessions with blocker chain info ──

    public static List<Map<String, Object>> querySessions(Connection conn) throws SQLException {
        String sql =
            "SELECT s.sid, s.serial#, s.username, s.status, s.event, " +
            "       s.blocking_session, s.sql_id, s.prev_sql_id, " +
            "       s.wait_class, s.seconds_in_wait, s.last_call_et, " +
            "       s.machine, s.program, " +
            "       (SELECT REPLACE(SUBSTR(q.sql_text, 1, 120), CHR(10), ' ') " +
            "        FROM v$sql q WHERE q.sql_id = COALESCE(s.sql_id, s.prev_sql_id) " +
            "        AND ROWNUM = 1) AS sql_text " +
            "FROM v$session s " +
            "WHERE s.type = 'USER' AND s.wait_class <> 'Idle' " +
            "AND s.sid <> SYS_CONTEXT('USERENV', 'SID') " +
            "AND NVL(s.program, '-') <> 'dit-bridge' " +
            "ORDER BY s.seconds_in_wait DESC, s.last_call_et DESC " +
            "FETCH FIRST 30 ROWS ONLY";

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Statement stmt = conn.createStatement();
        try {
            ResultSet rs = stmt.executeQuery(sql);
            try {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("sid", objToStr(rs.getObject(1)));
                    row.put("serial", objToStr(rs.getObject(2)));
                    row.put("username", defaultStr(rs.getString(3), "-"));
                    row.put("status", objToStr(rs.getObject(4)));
                    row.put("event", defaultStr(rs.getString(5), "CPU"));
                    row.put("blocking_sid", objToStr(rs.getObject(6)));
                    row.put("sql_id", defaultStr(rs.getString(7), "-"));
                    row.put("prev_sql_id", defaultStr(rs.getString(8), "-"));
                    row.put("wait_class", defaultStr(rs.getString(9), "CPU"));
                    row.put("seconds_in_wait", objToDouble(rs.getObject(10)));
                    row.put("elapsed_s", objToDouble(rs.getObject(11)));
                    row.put("machine", defaultStr(rs.getString(12), "-"));
                    row.put("program", defaultStr(rs.getString(13), "-"));
                    row.put("sql_text", trimSql(rs.getString(14)));
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

    // ── SQL hotspots from V$SQL ──

    public static List<Map<String, Object>> querySqlHotspots(Connection conn) throws SQLException {
        String sql =
            "SELECT sql_id, plan_hash_value, elapsed_time, cpu_time, executions, " +
            "       buffer_gets, disk_reads, rows_processed, sql_text " +
            "FROM v$sql " +
            "WHERE executions > 0 AND sql_id IS NOT NULL " +
            "AND last_active_time > SYSDATE - 10/(24*60) " +
            "AND sql_text NOT LIKE '%v$%' AND sql_text NOT LIKE '%V$%' " +
            "AND sql_text NOT LIKE '%x$%' AND sql_text NOT LIKE '%X$%' " +
            "ORDER BY elapsed_time DESC FETCH FIRST 15 ROWS ONLY";

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

    // ── DB server time ──

    public String queryServerTime(Connection conn) throws SQLException {
        return queryServerTimeStatic(conn);
    }

    public static String queryServerTimeStatic(Connection conn) throws SQLException {
        Statement stmt = conn.createStatement();
        try {
            ResultSet rs = stmt.executeQuery("SELECT TO_CHAR(SYSDATE, 'HH24:MI:SS') FROM DUAL");
            try {
                if (rs.next()) {
                    return rs.getString(1);
                }
            } finally {
                rs.close();
            }
        } finally {
            stmt.close();
        }
        return "";
    }

    // ── Collect all data in one call with per-query error isolation ──

    public Map<String, Object> collectAll(Connection conn) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();

        try {
            data.put("server_time", queryServerTimeStatic(conn));
        } catch (SQLException e) {
            data.put("server_time", "");
        }

        try {
            data.put("instance", queryInstanceInfoStatic(conn));
        } catch (SQLException e) {
            data.put("instance", new LinkedHashMap<String, Object>());
        }

        Map<String, Object> sysmetric;
        try {
            sysmetric = querySysmetric(conn);
        } catch (SQLException e) {
            sysmetric = new LinkedHashMap<String, Object>();
        }

        Map<String, Object> sysstat;
        try {
            sysstat = querySysstat(conn);
        } catch (SQLException e) {
            sysstat = new LinkedHashMap<String, Object>();
        }

        // Compute delta rates from V$SYSSTAT and merge into sysmetric
        Map<String, Object> deltaRates = computeSysstatDelta(sysstat);
        sysmetric.putAll(deltaRates);

        data.put("sysmetric", sysmetric);
        data.put("sysstat", sysstat);

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

    // ── Map sysmetric + sysstat into a flat metrics map ──

    public Map<String, Object> mapMetrics(Map<String, Object> sysmetric, Map<String, Object> sysstat) {
        return mapMetricsStatic(sysmetric, sysstat);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapMetricsStatic(Map<String, Object> sysmetric, Map<String, Object> sysstat) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("host_cpu_util", dbl(sysmetric, "Host CPU Utilization (%)"));
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
        out.put("wait_time_ratio", dbl(sysmetric, "Database Wait Time Ratio"));
        out.put("commits_per_sec", dbl(sysmetric, "User Commits Per Sec"));
        out.put("rollbacks_per_sec", dbl(sysmetric, "User Rollbacks Per Sec"));
        out.put("tran_per_sec", dbl(sysmetric, "User Commits Per Sec") + dbl(sysmetric, "User Rollbacks Per Sec"));
        out.put("parse_total_per_sec", dbl(sysmetric, "Total Parse Count Per Sec"));
        out.put("hard_parses_per_sec", dbl(sysmetric, "Hard Parse Count Per Sec"));
        out.put("buffer_cache_hit", dbl(sysmetric, "Buffer Cache Hit Ratio"));
        return out;
    }

    // ── Helpers ──

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
