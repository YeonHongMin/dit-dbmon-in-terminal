package io.dit.oracle;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OracleBridgeMain {

    private OracleBridgeMain() {
    }

    public static void main(String[] args) {
        Map<String, String> options = parseArgs(args);
        String command = options.get("command");
        if (isBlank(command)) {
            printError("Missing required argument: --command");
            System.exit(2);
            return;
        }

        try {
            if ("health".equals(command)) {
                executeHealth(options);
                return;
            }
            if ("kill".equals(command)) {
                executeKill(options);
                return;
            }
            if ("metrics".equals(command)) {
                executeMetrics(options);
                return;
            }
            if ("sessions".equals(command)) {
                executeSessions(options);
                return;
            }
            if ("waits".equals(command)) {
                executeWaits(options);
                return;
            }
            if ("sql".equals(command)) {
                executeSqlHotspots(options);
                return;
            }
            if ("monitor".equals(command)) {
                executeMonitor(options);
                return;
            }
            if ("report".equals(command)) {
                executeReport(options);
                return;
            }
            if ("tui".equals(command)) {
                executeTui(options);
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

    private static void executeTui(Map<String, String> options) throws IOException {
        OracleMonitorTui tui = new OracleMonitorTui(options);
        tui.run();
    }

    // ── Existing CLI commands (delegating to OracleCollector where possible) ──

    private static void executeHealth(Map<String, String> options) throws SQLException {
        Connection connection = openConnection(options);
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

    private static void executeKill(Map<String, String> options) throws SQLException {
        String sid = trim(options.get("sid"));
        if (isBlank(sid) || !sid.matches("^\\d+,\\d+$")) {
            throw new RuntimeException("--sid must be numeric sid,serial# (e.g. 123,45678)");
        }

        Connection connection = openConnection(options);
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

    private static void executeMetrics(Map<String, String> options) throws SQLException {
        Connection connection = openConnection(options);
        try {
            Map<String, Object> sysmetric = OracleCollector.querySysmetric(connection);
            Map<String, Object> sysstat = OracleCollector.querySysstat(connection);

            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("sysmetric", sysmetric);
            out.put("sysstat", sysstat);
            printObject(out);
        } finally {
            connection.close();
        }
    }

    private static void executeSessions(Map<String, String> options) throws SQLException {
        Connection connection = openConnection(options);
        try {
            List<Map<String, Object>> sessions = OracleCollector.querySessions(connection);

            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("sessions", sessions);
            printObject(out);
        } finally {
            connection.close();
        }
    }

    private static void executeWaits(Map<String, String> options) throws SQLException {
        Connection connection = openConnection(options);
        try {
            List<Map<String, Object>> waits = OracleCollector.queryWaits(connection);

            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("wait_events", waits);
            printObject(out);
        } finally {
            connection.close();
        }
    }

    private static void executeSqlHotspots(Map<String, String> options) throws SQLException {
        Connection connection = openConnection(options);
        try {
            List<Map<String, Object>> sql = OracleCollector.querySqlHotspots(connection);

            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("sql_hotspots", sql);
            printObject(out);
        } finally {
            connection.close();
        }
    }

    private static void executeMonitor(Map<String, String> options) throws SQLException {
        String recordFile = required(options, "record-file");
        String captureFile = required(options, "capture-file");
        int intervalSeconds = Math.max(1, parseInt(options.get("interval-seconds"), 1));

        Connection connection = openConnection(options);
        WaitEventDeltaTracker waitDeltaTracker = new WaitEventDeltaTracker();
        String instanceName = "Oracle";
        try {
            Map<String, Object> instInfo = OracleCollector.queryInstanceInfo(connection);
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
                    sysmetric = OracleCollector.querySysmetric(connection);
                    sysstat = OracleCollector.querySysstat(connection);
                    sessions = OracleCollector.querySessions(connection);
                    waits = waitDeltaTracker.queryDelta(connection);
                    sql = OracleCollector.querySqlHotspots(connection);
                } catch (SQLException ex) {
                    collectorState = "ERR";
                    source = "synthetic";
                    System.err.println("Collector error: " + ex.getMessage());
                }

                Map<String, Object> frame = new LinkedHashMap<String, Object>();
                frame.put("type", "frame");
                frame.put("timestamp", Instant.now().toString());
                frame.put("db_type", "oracle");
                frame.put("instance_name", instanceName);
                frame.put("collector_state", collectorState);
                Map<String, Object> sources = new LinkedHashMap<String, Object>();
                sources.put("metrics", source);
                sources.put("sessions", source);
                sources.put("wait_events", source);
                sources.put("sql_hotspots", source);
                frame.put("data_sources", sources);

                Map<String, Object> metrics = OracleCollector.mapMetrics(sysmetric, sysstat);
                frame.put("metrics", metrics);
                frame.put("sessions", sessions);
                frame.put("wait_events", waits);
                frame.put("sql_hotspots", sql);

                appendLine(Paths.get(recordFile), toJson(frame));

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

    static Connection openConnection(Map<String, String> options) throws SQLException {
        String host = required(options, "host");
        String port = required(options, "port");
        String serviceName = required(options, "service-name");
        String user = required(options, "user");
        String password = required(options, "password");

        int callTimeoutMs = parseInt(options.get("call-timeout-ms"), 3000);
        int connectTimeoutSec = parseInt(options.get("tcp-connect-timeout-seconds"), 5);
        String jdbcUrl = "jdbc:oracle:thin:@//" + host + ":" + port + "/" + serviceName;

        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", Integer.toString(connectTimeoutSec * 1000));
        properties.setProperty("oracle.jdbc.ReadTimeout", Integer.toString(callTimeoutMs));
        properties.setProperty("v$session.program", "dit-bridge");

        return DriverManager.getConnection(jdbcUrl, properties);
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

    private static Map<String, String> parseArgs(String[] args) {
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
        System.out.println(toJson(out));
    }

    private static void printObject(Map<String, Object> payload) {
        System.out.println(toJson(payload));
    }

    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return quote((String) value);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) value;
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(String.valueOf(entry.getKey())));
                sb.append(':');
                sb.append(toJson(entry.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value;
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(toJson(item));
            }
            sb.append(']');
            return sb.toString();
        }
        return quote(String.valueOf(value));
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        String v = value == null ? "" : value;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
