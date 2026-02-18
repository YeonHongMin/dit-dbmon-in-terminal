package io.dit.bridge;

import io.dit.bridge.api.DbmsCollector;
import io.dit.bridge.api.DbmsConnectionFactory;
import io.dit.bridge.api.WaitDeltaTracker;
import io.dit.bridge.core.JsonUtil;
import io.dit.bridge.oracle.OracleCollector;
import io.dit.bridge.oracle.OracleConnectionFactory;
import io.dit.bridge.oracle.OracleMonitorTui;
import io.dit.bridge.oracle.OracleWaitDeltaTracker;
import io.dit.bridge.tibero.TiberoCollector;
import io.dit.bridge.tibero.TiberoConnectionFactory;
import io.dit.bridge.tibero.TiberoMonitorTui;
import io.dit.bridge.tibero.TiberoWaitDeltaTracker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DitMain {

    private DitMain() {
    }

    public static void main(String[] args) {
        Map<String, String> options = parseArgs(args);

        // Parse --dbms-type (required)
        String dbmsTypeStr = trim(options.get("dbms-type"));
        if (isBlank(dbmsTypeStr)) {
            printError("Missing required argument: --dbms-type. Supported: " + DbmsType.supportedList());
            System.exit(2);
            return;
        }
        DbmsType dbmsType = DbmsType.fromString(dbmsTypeStr);
        if (dbmsType == null) {
            printError("Unsupported dbms-type: " + dbmsTypeStr + ". Supported: " + DbmsType.supportedList());
            System.exit(2);
            return;
        }

        String command = options.get("command");
        if (isBlank(command)) {
            printError("Missing required argument: --command");
            System.exit(2);
            return;
        }

        DbmsConnectionFactory connectionFactory;
        DbmsCollector collector;
        WaitDeltaTracker waitTracker;

        if (dbmsType == DbmsType.ORACLE) {
            connectionFactory = new OracleConnectionFactory();
            collector = new OracleCollector();
            waitTracker = new OracleWaitDeltaTracker();
        } else if (dbmsType == DbmsType.TIBERO) {
            connectionFactory = new TiberoConnectionFactory();
            collector = new TiberoCollector();
            waitTracker = new TiberoWaitDeltaTracker();
        } else {
            printError("DBMS type '" + dbmsType.name().toLowerCase(Locale.US)
                + "' is not yet implemented. Currently supported: oracle, tibero");
            System.exit(2);
            return;
        }

        try {
            if ("health".equals(command)) {
                executeHealth(options, connectionFactory);
                return;
            }
            if ("kill".equals(command)) {
                executeKill(options, connectionFactory);
                return;
            }
            if ("metrics".equals(command)) {
                executeMetrics(options, connectionFactory, collector, dbmsType);
                return;
            }
            if ("sessions".equals(command)) {
                executeSessions(options, connectionFactory, dbmsType);
                return;
            }
            if ("waits".equals(command)) {
                executeWaits(options, connectionFactory, dbmsType);
                return;
            }
            if ("sql".equals(command)) {
                executeSqlHotspots(options, connectionFactory, dbmsType);
                return;
            }
            if ("monitor".equals(command)) {
                executeMonitor(options, connectionFactory, collector, waitTracker, dbmsType);
                return;
            }
            if ("report".equals(command)) {
                executeReport(options);
                return;
            }
            if ("tui".equals(command)) {
                executeTui(options, connectionFactory, dbmsType);
                return;
            }

            printError("Unsupported command: " + command);
            System.exit(2);
        } catch (SQLException ex) {
            printError("SQL error: " + ex.getMessage());
            System.exit(1);
        } catch (IOException ex) {
            printError("IO error: " + ex.getMessage());
            System.exit(1);
        } catch (RuntimeException ex) {
            printError(ex.getMessage());
            System.exit(1);
        }
    }

    // ── TUI command ──

    private static void executeTui(Map<String, String> options, DbmsConnectionFactory connectionFactory, DbmsType dbmsType) throws IOException {
        if (dbmsType == DbmsType.TIBERO) {
            TiberoMonitorTui tui = new TiberoMonitorTui(options, connectionFactory);
            tui.run();
        } else {
            OracleMonitorTui tui = new OracleMonitorTui(options, connectionFactory);
            tui.run();
        }
    }

    // ── Existing CLI commands ──

    private static void executeHealth(Map<String, String> options, DbmsConnectionFactory connectionFactory) throws SQLException {
        Connection connection = connectionFactory.create(options);
        try {
            Statement statement = connection.createStatement();
            try {
                statement.execute("select 1 from dual");
            } finally {
                statement.close();
            }
            printObject(singleKey("ok", Boolean.TRUE));
        } finally {
            connection.close();
        }
    }

    private static void executeKill(Map<String, String> options, DbmsConnectionFactory connectionFactory) throws SQLException {
        String sid = trim(options.get("sid"));
        if (isBlank(sid) || !sid.matches("^\\d+,\\d+$")) {
            throw new RuntimeException("--sid must be numeric sid,serial# (e.g. 123,45678)");
        }

        Connection connection = connectionFactory.create(options);
        try {
            Statement statement = connection.createStatement();
            try {
                statement.execute("alter system kill session '" + sid + "' immediate");
            } finally {
                statement.close();
            }
            printObject(singleKey("ok", Boolean.TRUE));
        } finally {
            connection.close();
        }
    }

    private static void executeMetrics(Map<String, String> options, DbmsConnectionFactory connectionFactory,
                                       DbmsCollector collector, DbmsType dbmsType) throws SQLException {
        Connection connection = connectionFactory.create(options);
        try {
            Map<String, Object> sysmetric;
            Map<String, Object> sysstat;
            if (dbmsType == DbmsType.TIBERO) {
                sysmetric = ((TiberoCollector) collector).computeSyntheticSysmetric(connection);
                sysstat = TiberoCollector.querySysstat(connection);
            } else {
                sysmetric = OracleCollector.querySysmetric(connection);
                sysstat = OracleCollector.querySysstat(connection);
            }

            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("sysmetric", sysmetric);
            out.put("sysstat", sysstat);
            printObject(out);
        } finally {
            connection.close();
        }
    }

    private static void executeSessions(Map<String, String> options, DbmsConnectionFactory connectionFactory, DbmsType dbmsType) throws SQLException {
        Connection connection = connectionFactory.create(options);
        try {
            List<Map<String, Object>> sessions;
            if (dbmsType == DbmsType.TIBERO) {
                sessions = TiberoCollector.querySessions(connection);
            } else {
                sessions = OracleCollector.querySessions(connection);
            }

            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("sessions", sessions);
            printObject(out);
        } finally {
            connection.close();
        }
    }

    private static void executeWaits(Map<String, String> options, DbmsConnectionFactory connectionFactory, DbmsType dbmsType) throws SQLException {
        Connection connection = connectionFactory.create(options);
        try {
            List<Map<String, Object>> waits;
            if (dbmsType == DbmsType.TIBERO) {
                waits = TiberoCollector.queryWaits(connection);
            } else {
                waits = OracleCollector.queryWaits(connection);
            }

            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("wait_events", waits);
            printObject(out);
        } finally {
            connection.close();
        }
    }

    private static void executeSqlHotspots(Map<String, String> options, DbmsConnectionFactory connectionFactory, DbmsType dbmsType) throws SQLException {
        Connection connection = connectionFactory.create(options);
        try {
            List<Map<String, Object>> sql;
            if (dbmsType == DbmsType.TIBERO) {
                sql = TiberoCollector.querySqlHotspots(connection);
            } else {
                sql = OracleCollector.querySqlHotspots(connection);
            }

            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("sql_hotspots", sql);
            printObject(out);
        } finally {
            connection.close();
        }
    }

    private static void executeMonitor(Map<String, String> options, DbmsConnectionFactory connectionFactory,
                                       DbmsCollector collector, WaitDeltaTracker waitTracker, DbmsType dbmsType) throws SQLException {
        String recordFile = required(options, "record-file");
        String captureFile = required(options, "capture-file");
        int intervalSeconds = Math.max(1, parseInt(options.get("interval-seconds"), 1));

        Connection connection = connectionFactory.create(options);
        String dbTypeLabel = dbmsType == DbmsType.TIBERO ? "tibero" : "oracle";
        String instanceName = dbTypeLabel.substring(0, 1).toUpperCase() + dbTypeLabel.substring(1);
        try {
            Map<String, Object> instInfo = collector.queryInstanceInfo(connection);
            Object name = instInfo.get("instance_name");
            if (name != null && !String.valueOf(name).isEmpty()) {
                instanceName = String.valueOf(name);
            }
        } catch (SQLException ignored) {
        }
        try {
            while (true) {
                String collectorState = "ON";
                String source = "collector";
                Map<String, Object> sysmetric = new LinkedHashMap<String, Object>();
                Map<String, Object> sysstat = new LinkedHashMap<String, Object>();
                List<Map<String, Object>> sessions = new ArrayList<Map<String, Object>>();
                List<Map<String, Object>> waits = new ArrayList<Map<String, Object>>();
                List<Map<String, Object>> sql = new ArrayList<Map<String, Object>>();

                try {
                    if (dbmsType == DbmsType.TIBERO) {
                        sysmetric = ((TiberoCollector) collector).computeSyntheticSysmetric(connection);
                        sysstat = TiberoCollector.querySysstat(connection);
                        sessions = TiberoCollector.querySessions(connection);
                        waits = waitTracker.queryDelta(connection);
                        sql = TiberoCollector.querySqlHotspots(connection);
                    } else {
                        sysmetric = OracleCollector.querySysmetric(connection);
                        sysstat = OracleCollector.querySysstat(connection);
                        sessions = OracleCollector.querySessions(connection);
                        waits = waitTracker.queryDelta(connection);
                        sql = OracleCollector.querySqlHotspots(connection);
                    }
                } catch (SQLException ex) {
                    collectorState = "ERR";
                    source = "synthetic";
                    System.err.println("Collector error: " + ex.getMessage());
                }

                Map<String, Object> frame = new LinkedHashMap<String, Object>();
                frame.put("type", "frame");
                frame.put("timestamp", Instant.now().toString());
                frame.put("db_type", dbTypeLabel);
                frame.put("instance_name", instanceName);
                frame.put("collector_state", collectorState);
                Map<String, Object> sources = new LinkedHashMap<String, Object>();
                sources.put("metrics", source);
                sources.put("sessions", source);
                sources.put("wait_events", source);
                sources.put("sql_hotspots", source);
                frame.put("data_sources", sources);

                Map<String, Object> metrics = collector.mapMetrics(sysmetric, sysstat);
                frame.put("metrics", metrics);
                frame.put("sessions", sessions);
                frame.put("wait_events", waits);
                frame.put("sql_hotspots", sql);

                appendLine(Paths.get(recordFile), JsonUtil.toJson(frame));

                String screen = renderScreen(metrics, sessions, waits, sql);
                writeText(Paths.get(captureFile), screen + "\n");
                System.out.println(screen);

                try {
                    Thread.sleep(intervalSeconds * 1000L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            connection.close();
        }
    }

    private static void executeReport(Map<String, String> options) {
        String recordFile = required(options, "record-file");
        String workloadResult = trim(options.get("workload-result"));
        String workloadLog = trim(options.get("workload-log"));
        String monitorLog = trim(options.get("monitor-log"));
        String output = required(options, "output");

        int frames = 0;
        int on = 0;
        int err = 0;
        int metricsCollector = 0;
        int sessionsCollector = 0;
        int waitsCollector = 0;
        int sqlCollector = 0;

        try {
            List<String> lines = Files.readAllLines(Paths.get(recordFile), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (!line.contains("\"type\":\"frame\"")) {
                    continue;
                }
                frames += 1;
                if (line.contains("\"collector_state\":\"ON\"")) {
                    on += 1;
                }
                if (line.contains("\"collector_state\":\"ERR\"")) {
                    err += 1;
                }
                if (line.contains("\"metrics\":\"collector\"")) {
                    metricsCollector += 1;
                }
                if (line.contains("\"sessions\":\"collector\"")) {
                    sessionsCollector += 1;
                }
                if (line.contains("\"wait_events\":\"collector\"")) {
                    waitsCollector += 1;
                }
                if (line.contains("\"sql_hotspots\":\"collector\"")) {
                    sqlCollector += 1;
                }
            }
        } catch (Exception ex) {
            System.err.println("Failed to read record file: " + ex.getMessage());
        }

        String totalTx = extractNumberFromJson(workloadResult, "totalTransactions", "0");
        String totalErrors = extractNumberFromJson(workloadResult, "totalErrors", "0");
        String avgTps = extractNumberFromJson(workloadResult, "postWarmupTps", "0");
        String p95 = extractNumberFromJson(workloadResult, "p95", "0");

        StringBuilder md = new StringBuilder();
        md.append("# DIT Oracle Monitoring Run Report\n\n");
        md.append("## Recording\n");
        md.append("- File: `").append(recordFile).append("`\n");
        md.append("- Frames: ").append(frames).append("\n\n");

        md.append("## Workload Summary\n");
        md.append("- Log: `").append(workloadLog).append("`\n");
        md.append("- Total Transactions: ").append(totalTx).append("\n");
        md.append("- Total Errors: ").append(totalErrors).append("\n");
        md.append("- Post-warmup TPS: ").append(avgTps).append("\n");
        md.append("- P95 Latency: ").append(p95).append("ms\n\n");

        md.append("## Monitor Log\n");
        md.append("- Log: `").append(monitorLog).append("`\n\n");

        md.append("## Collector Provenance\n");
        md.append("- Collector states: ON=").append(on).append(", ERR=").append(err).append("\n");
        md.append("- Collector-backed frames (metrics/sessions/waits/sql): ")
            .append(metricsCollector).append("/")
            .append(sessionsCollector).append("/")
            .append(waitsCollector).append("/")
            .append(sqlCollector).append("\n");

        writeText(Paths.get(output), md.toString());
    }

    private static String extractNumberFromJson(String path, String key, String fallback) {
        if (isBlank(path)) {
            return fallback;
        }
        try {
            String text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            Pattern p = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*([0-9.]+)");
            Matcher m = p.matcher(text);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception ex) {
            System.err.println("Failed to extract " + key + " from " + path + ": " + ex.getMessage());
        }
        return fallback;
    }

    // ── Screen rendering for monitor command (plain text) ──

    private static String renderScreen(
        Map<String, Object> metrics,
        List<Map<String, Object>> sessions,
        List<Map<String, Object>> waits,
        List<Map<String, Object>> sql
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("DIT | ").append(Instant.now().toString()).append("\n");

        double dbTimeSec = numberOrZero(metrics.get("db_time_per_sec"));
        double cpuTimeSec = numberOrZero(metrics.get("cpu_time_per_sec"));
        double waitTimeSec = numberOrZero(metrics.get("wait_time_per_sec"));
        String cpuPct = dbTimeSec > 0.001 ? String.format(Locale.US, "%.0f%%", cpuTimeSec / dbTimeSec * 100) : "-";
        String waitPct = dbTimeSec > 0.001 ? String.format(Locale.US, "%.0f%%", waitTimeSec / dbTimeSec * 100) : "-";

        sb.append("Active Sessions: ").append(format(numberOrZero(metrics.get("active_sessions"))));
        sb.append("  DB Time/s: ").append(format(dbTimeSec)).append("\n");
        sb.append("CPU Time/s: ").append(format(cpuTimeSec)).append(" (").append(cpuPct).append(")");
        sb.append("  Wait Time/s: ").append(format(waitTimeSec)).append(" (").append(waitPct).append(")\n");
        sb.append("Tran/s: ").append(format(numberOrZero(metrics.get("tran_per_sec"))));
        sb.append("  SQL Exec/s: ").append(format(numberOrZero(metrics.get("sql_exec_per_sec"))));
        sb.append("  Parse Total/s: ").append(format(numberOrZero(metrics.get("parse_total_per_sec"))));
        sb.append("  Hard Parse/s: ").append(format(numberOrZero(metrics.get("hard_parses_per_sec"))));
        sb.append("  Logical Reads/s: ").append(format(numberOrZero(metrics.get("logical_reads_per_sec")))).append("\n");
        sb.append("Phy Reads/s: ").append(format(numberOrZero(metrics.get("physical_reads_per_sec"))));
        sb.append("  Phy Read MB/s: ").append(format(numberOrZero(metrics.get("physical_read_mb_per_sec"))));
        sb.append("  Phy Write MB/s: ").append(format(numberOrZero(metrics.get("physical_write_mb_per_sec")))).append("\n");
        sb.append("Redo MB/s: ").append(format(numberOrZero(metrics.get("redo_mb_per_sec")))).append("\n");

        sb.append("Top Waits:\n");
        for (int i = 0; i < Math.min(3, waits.size()); i++) {
            Map<String, Object> row = waits.get(i);
            sb.append("- ").append(stringOrEmpty(row.get("event"))).append(" (")
                .append(stringOrEmpty(row.get("wait_class"))).append(") ")
                .append(format(numberOrZero(row.get("wait_sec_per_sec")))).append("s/s\n");
        }

        sb.append("Top SQL:\n");
        for (int i = 0; i < Math.min(3, sql.size()); i++) {
            Map<String, Object> row = sql.get(i);
            sb.append("- ").append(stringOrEmpty(row.get("sql_id"))).append(" phv=")
                .append(stringOrEmpty(row.get("plan_hash_value"))).append("\n");
        }

        sb.append("Sessions: ").append(sessions.size()).append("\n");
        return sb.toString();
    }

    // ── Utility methods ──

    private static String format(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static String required(Map<String, String> options, String key) {
        String value = trim(options.get(key));
        if (isBlank(value)) {
            throw new RuntimeException("Missing required argument: --" + key);
        }
        return value;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(trim(value));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    static Map<String, String> parseArgs(String[] args) {
        if (args == null || args.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String current = args[i];
            if (current == null || !current.startsWith("--")) {
                continue;
            }
            String key = current.substring(2).trim();
            if (key.isEmpty()) {
                continue;
            }
            String value = "";
            if (i + 1 < args.length && args[i + 1] != null && !args[i + 1].startsWith("--")) {
                value = args[i + 1];
                i += 1;
            }
            out.put(key, value);
        }
        return out;
    }

    private static void appendLine(Path path, String line) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, (line + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ex) {
            throw new RuntimeException("Failed writing frame file: " + ex.getMessage());
        }
    }

    private static void writeText(Path path, String text) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ex) {
            throw new RuntimeException("Failed writing text file: " + ex.getMessage());
        }
    }

    private static Map<String, Object> singleKey(String key, Object value) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put(key, value);
        return out;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return trim(value).isEmpty();
    }

    private static String stringOrEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double numberOrZero(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (RuntimeException ex) {
            return 0.0;
        }
    }

    private static void printError(String message) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("error", message == null || message.trim().isEmpty() ? "unknown error" : message);
        System.out.println(JsonUtil.toJson(out));
    }

    private static void printObject(Map<String, Object> payload) {
        System.out.println(JsonUtil.toJson(payload));
    }
}
