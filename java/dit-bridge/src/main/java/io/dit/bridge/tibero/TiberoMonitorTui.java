package io.dit.bridge.tibero;

import io.dit.bridge.api.DbmsConnectionFactory;
import io.dit.bridge.core.MetricsBuffer;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TiberoMonitorTui {

    private static final char BOX_H = '\u2500';
    private static final char BOX_V = '\u2502';
    private static final char BOX_TL = '\u250C';
    private static final char BOX_TR = '\u2510';
    private static final char BOX_BL = '\u2514';
    private static final char BOX_BR = '\u2518';

    // ── Colors (Light Theme) ──
    private static final TextColor BG = TextColor.ANSI.WHITE;
    private static final TextColor FG = TextColor.ANSI.BLACK;
    private static final TextColor TITLE_BG = new TextColor.RGB(30, 80, 160);
    private static final TextColor TITLE_FG = TextColor.ANSI.WHITE;
    private static final TextColor HEADER_FG = new TextColor.RGB(0, 100, 200);
    private static final TextColor BORDER_FG = new TextColor.RGB(150, 150, 150);
    private static final TextColor VALUE_FG = new TextColor.RGB(0, 128, 0);
    private static final TextColor SPARK_FG = new TextColor.RGB(0, 100, 255);
    private static final TextColor ACTIVE_FG = new TextColor.RGB(0, 128, 0);
    private static final TextColor INACTIVE_FG = new TextColor.RGB(128, 128, 128);
    private static final TextColor SELECT_BG = new TextColor.RGB(220, 230, 255);
    private static final TextColor FOOTER_BG = new TextColor.RGB(230, 230, 230);

    private final Map<String, String> options;
    private final DbmsConnectionFactory connectionFactory;
    private final int intervalMs;

    private final MetricsBuffer metricsBuffer = new MetricsBuffer(60);
    private final TiberoWaitDeltaTracker waitDeltaTracker = new TiberoWaitDeltaTracker();
    private final TiberoCollector collector = new TiberoCollector();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object dataLock = new Object();

    private int sessionScroll = 0;
    private int sessionSelect = 0;
    private int sqlScroll = 0;
    private String lastError = "";
    private long collectMs = 0;
    private String lastCollectTime = "";

    private Map<String, Object> currentData;
    private Map<String, Object> currentMetrics;

    public TiberoMonitorTui(Map<String, String> options, DbmsConnectionFactory connectionFactory) {
        this.options = options;
        this.connectionFactory = connectionFactory;
        this.intervalMs = Math.max(1, intVal(options.get("interval"), 6)) * 1000;
    }

    public void run() throws IOException {
        // Connect BEFORE starting screen so errors are visible on terminal
        Connection conn = null;
        try {
            conn = connectionFactory.create(options);
        } catch (SQLException e) {
            System.err.println("Connection failed: " + e.getMessage());
            return;
        }

        Terminal terminal = new DefaultTerminalFactory().createTerminal();
        Screen screen = new TerminalScreen(terminal);
        screen.startScreen();
        screen.setCursorPosition(null);

        try {
            lastError = "";

            collectData(conn);
            TerminalSize size = screen.getTerminalSize();
            render(screen, size);
            screen.refresh(Screen.RefreshType.COMPLETE);

            long lastCollect = System.currentTimeMillis();
            boolean needsRender = false;

            while (running.get()) {
                KeyStroke key = screen.pollInput();
                if (key != null) {
                    handleKey(key, screen);
                    needsRender = true;
                }

                long now = System.currentTimeMillis();
                if (now - lastCollect >= intervalMs) {
                    try {
                        collectData(conn);
                        lastError = "";
                    } catch (SQLException e) {
                        lastError = e.getMessage();
                        if (conn != null) {
                            try {
                                conn.close();
                            } catch (Exception ignored) {
                            }
                        }
                        conn = null;
                        try {
                            conn = connectionFactory.create(options);
                        } catch (SQLException reconnectErr) {
                            lastError = "Reconnect failed: " + reconnectErr.getMessage();
                        }
                    }
                    lastCollect = now;
                    needsRender = true;
                }

                TerminalSize newSize = screen.doResizeIfNecessary();
                if (newSize != null) {
                    size = newSize;
                    needsRender = true;
                }

                if (needsRender) {
                    size = screen.getTerminalSize();
                    render(screen, size);
                    screen.refresh(Screen.RefreshType.DELTA);
                    needsRender = false;
                }

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (SQLException e) {
            System.err.println("Connection failed: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                }
            }
            screen.stopScreen();
        }
    }

    private void collectData(Connection conn) throws SQLException {
        long t0 = System.currentTimeMillis();
        Map<String, Object> data = collector.collectAll(conn);

        try {
            List<Map<String, Object>> waitDelta = waitDeltaTracker.queryDelta(conn);
            data.put("event_metric", waitDelta);
        } catch (SQLException e) {
            // keep whatever collectAll returned
        }

        collectMs = System.currentTimeMillis() - t0;
        lastCollectTime = str(data.get("server_time"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sysmetric = (Map<String, Object>) data.get("sysmetric");
        @SuppressWarnings("unchecked")
        Map<String, Object> sysstat = (Map<String, Object>) data.get("sysstat");

        Map<String, Object> metrics = TiberoCollector.mapMetricsStatic(
                sysmetric != null ? sysmetric : new LinkedHashMap<String, Object>(),
                sysstat != null ? sysstat : new LinkedHashMap<String, Object>());

        synchronized (dataLock) {
            currentData = data;
            currentMetrics = metrics;
        }

        for (Map.Entry<String, Object> e : metrics.entrySet()) {
            if (e.getValue() instanceof Number) {
                metricsBuffer.push(e.getKey(), ((Number) e.getValue()).doubleValue());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void render(Screen screen, TerminalSize size) {
        int w = size.getColumns();
        int h = size.getRows();
        if (w < 40 || h < 10)
            return;
        // Fill background
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                setChar(screen, y, x, ' ', FG, BG);
            }
        }

        Map<String, Object> data;
        Map<String, Object> metrics;
        synchronized (dataLock) {
            data = currentData;
            metrics = currentMetrics;
        }
        if (data == null || metrics == null) {
            drawText(screen, 0, 0, "Connecting...", FG, BG);
            return;
        }

        int row = 0;

        // ── Title Bar ──
        Map<String, Object> inst = (Map<String, Object>) data.get("instance");
        String instanceName = inst != null ? str(inst.get("instance_name")) : "Tibero";
        String hostName = inst != null ? str(inst.get("host_name")) : "";
        String version = inst != null ? str(inst.get("version")) : "";
        String title = String.format(" DIT | %s@%s | Tibero %s | Collected: %s ",
                instanceName, hostName, version, lastCollectTime);
        drawBar(screen, row, w, title, TITLE_FG, TITLE_BG);
        row++;

        // ── Load Profile Panel + Top Waits Panel ──
        int leftW = w / 2;
        int rightW = w - leftW;

        drawBox(screen, row, 0, leftW, 15, "Load Profile", BORDER_FG);
        drawBox(screen, row, leftW, rightW, 15, "Top Waits (Real-time)", BORDER_FG);

        int pr = row + 1;
        int sparkW = 40;
        double dbTimePerSec = dbl(metrics.get("db_time_per_sec"));
        drawMetricRow(screen, pr++, 2, sparkW, "Active Sessions", "active_sessions", metrics, "%,.2f");
        drawMetricRow(screen, pr++, 2, sparkW, "DB Time/s", "db_time_per_sec", metrics, "%,.2f");
        drawMetricRowPct(screen, pr++, 2, sparkW, "CPU Time/s", "cpu_time_per_sec", metrics, "%,.2f", dbTimePerSec);
        drawMetricRowPct(screen, pr++, 2, sparkW, "Wait Time/s", "wait_time_per_sec", metrics, "%,.2f", dbTimePerSec);
        drawMetricRow(screen, pr++, 2, sparkW, "Logical Reads/s", "logical_reads_per_sec", metrics, "%,.0f");
        drawMetricRow(screen, pr++, 2, sparkW, "Tran/s", "tran_per_sec", metrics, "%,.0f");
        drawMetricRow(screen, pr++, 2, sparkW, "SQL Exec/s", "sql_exec_per_sec", metrics, "%,.0f");
        drawMetricRow(screen, pr++, 2, sparkW, "Parse Total/s", "parse_total_per_sec", metrics, "%,.0f");
        drawMetricRow(screen, pr++, 2, sparkW, "Hard Parse/s", "hard_parses_per_sec", metrics, "%,.0f");
        drawMetricRow(screen, pr++, 2, sparkW, "Phy Reads/s", "physical_reads_per_sec", metrics, "%,.0f");
        drawMetricRow(screen, pr++, 2, sparkW, "Phy Read MB/s", "physical_read_mb_per_sec", metrics, "%,.2f");
        drawMetricRow(screen, pr++, 2, sparkW, "Phy Write MB/s", "physical_write_mb_per_sec", metrics, "%,.2f");
        drawMetricRow(screen, pr++, 2, sparkW, "Redo MB/s", "redo_mb_per_sec", metrics, "%,.2f");

        // Top Waits content
        pr = row + 1;
        int waitColStart = leftW + 2;
        int waitAreaW = rightW - 4;
        List<Map<String, Object>> eventMetric = (List<Map<String, Object>>) data.get("event_metric");
        if (eventMetric != null && !eventMetric.isEmpty()) {
            int evNameW = Math.max(20, waitAreaW - 22);
            drawText(screen, pr, waitColStart,
                    padRight("Wait Event", evNameW) + padRight("Avg(ms)", 11) + "Wait Time(s)", HEADER_FG, BG);
            pr++;
            for (int i = 0; i < Math.min(12, eventMetric.size()); i++) {
                Map<String, Object> ev = eventMetric.get(i);
                String evName = truncate(str(ev.get("event")), evNameW - 1);
                double avgMs = dbl(ev.get("avg_wait_ms"));
                double waitSecPerSec = dbl(ev.get("wait_sec_per_sec"));
                TextColor evColor = waitClassColor(str(ev.get("wait_class")));
                String avgStr = avgMs >= 1000 ? fmt("%,.1f", avgMs) : fmt("%.2f", avgMs);
                drawText(screen, pr + i, waitColStart,
                        padRight(evName, evNameW) + padRight(avgStr, 11) + fmt("%,.2f", waitSecPerSec),
                        evColor, BG);
            }
        } else {
            List<Map<String, Object>> waits = (List<Map<String, Object>>) data.get("waits");
            if (waits != null) {
                int evNameW = Math.max(20, waitAreaW - 22);
                drawText(screen, pr, waitColStart,
                        padRight("Wait Event", evNameW) + padRight("Avg(ms)", 11) + "Wait Time(s)", HEADER_FG, BG);
                pr++;
                for (int i = 0; i < Math.min(12, waits.size()); i++) {
                    Map<String, Object> ev = waits.get(i);
                    String evName = truncate(str(ev.get("event")), evNameW - 1);
                    double avgMs = dbl(ev.get("avg_wait_ms"));
                    double waitTimeSec = dbl(ev.get("wait_time_ms")) / 1000.0;
                    TextColor evColor = waitClassColor(str(ev.get("wait_class")));
                    String avgStr = avgMs >= 1000 ? fmt("%,.1f", avgMs) : fmt("%.2f", avgMs);
                    drawText(screen, pr + i, waitColStart,
                            padRight(evName, evNameW) + padRight(avgStr, 11) + fmt("%,.2f", waitTimeSec),
                            evColor, BG);
                }
            }
        }
        row += 15;

        // ── Sessions Panel ──
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) data.get("sessions");
        int sessRows = Math.max(6, h - row - 16);
        drawBox(screen, row, 0, w, sessRows + 2, "Sessions (" + (sessions != null ? sessions.size() : 0) + ")",
                BORDER_FG);

        int sr = row + 1;
        int progW = 16;
        String sessHeader = padRight("SID", 7) + padRight("Serial", 8) + padRight("User", 12) +
                padRight("Status", 10) + padRight("Wait Event", 28) + padRight("WClass", 12) +
                padRight("Blk", 5) + padRight("SQL ID", 15) + padRight("Wait(s)", 8) +
                padRight("Program", progW) + "SQL Text";
        drawText(screen, sr, 2, truncate(sessHeader, w - 4), HEADER_FG, BG);
        sr++;

        if (sessions != null) {
            int maxVisible = sessRows - 1;
            if (sessionSelect >= sessions.size())
                sessionSelect = Math.max(0, sessions.size() - 1);
            if (sessionScroll > sessionSelect)
                sessionScroll = sessionSelect;
            if (sessionSelect >= sessionScroll + maxVisible)
                sessionScroll = sessionSelect - maxVisible + 1;

            for (int i = 0; i < maxVisible && (sessionScroll + i) < sessions.size(); i++) {
                int idx = sessionScroll + i;
                Map<String, Object> s = sessions.get(idx);
                boolean selected = (idx == sessionSelect);
                TextColor rowBg = selected ? SELECT_BG : BG;
                TextColor statusColor = "ACTIVE".equals(str(s.get("status"))) ? ACTIVE_FG : INACTIVE_FG;

                String progStr = truncate(str(s.get("program")), progW - 1);
                String sqlText = str(s.get("sql_text"));
                String line = padRight(str(s.get("sid")), 7) +
                        padRight(str(s.get("serial")), 8) +
                        padRight(truncate(str(s.get("username")), 11), 12) +
                        padRight(str(s.get("status")), 10) +
                        padRight(truncate(str(s.get("event")), 27), 28) +
                        padRight(truncate(str(s.get("wait_class")), 11), 12) +
                        padRight(str(s.get("blocking_sid")), 5) +
                        padRight(str(s.get("sql_id")), 15) +
                        padRight(fmt("%.0f", dbl(s.get("seconds_in_wait"))), 8) +
                        padRight(progStr, progW) + sqlText;

                drawText(screen, sr + i, 2, truncate(line, w - 4), statusColor, rowBg);
            }
        }
        row += sessRows + 2;

        // ── SQL Detail Panel ──
        List<Map<String, Object>> sqlList = (List<Map<String, Object>>) data.get("sql_hotspots");
        int sqlPanelH = Math.max(4, h - row - 2);
        drawBox(screen, row, 0, w, sqlPanelH, "Top SQL", BORDER_FG);

        int sqlR = row + 1;
        String sqlHeader = padRight("SQL ID", 15) + padRight("Plan Hash", 13) +
                padRight("Elapsed(s)", 13) + padRight("Ela(s)/Exec", 13) +
                padRight("CPU(s)", 11) + padRight("Execs", 10) + padRight("Gets", 11) +
                padRight("Gets/Exec", 11) + "SQL Text";
        drawText(screen, sqlR, 2, truncate(sqlHeader, w - 4), HEADER_FG, BG);
        sqlR++;

        if (sqlList != null) {
            int maxSqlVisible = sqlPanelH - 2;
            for (int i = 0; i < maxSqlVisible && (sqlScroll + i) < sqlList.size(); i++) {
                Map<String, Object> sq = sqlList.get(sqlScroll + i);
                double elapsedSec = dbl(sq.get("elapsed_time")) / 1000000.0;
                double cpuSec = dbl(sq.get("cpu_time")) / 1000000.0;
                double execs = dbl(sq.get("executions"));
                double bufferGets = dbl(sq.get("buffer_gets"));
                double elaPerExec = execs > 0 ? elapsedSec / execs : 0;
                double getsPerExec = execs > 0 ? bufferGets / execs : 0;
                String sqlLine = padRight(str(sq.get("sql_id")), 15) +
                        padRight(str(sq.get("plan_hash_value")), 13) +
                        padRight(fmtLong(elapsedSec), 13) +
                        padRight(String.format("%.3f", elaPerExec), 13) +
                        padRight(fmtLong(cpuSec), 11) +
                        padRight(fmtLong(execs), 10) +
                        padRight(fmtLong(bufferGets), 11) +
                        padRight(fmtLong(getsPerExec), 11) +
                        str(sq.get("sql_text"));
                drawText(screen, sqlR + i, 2, truncate(sqlLine, w - 4), FG, BG);
            }
        }
        row += sqlPanelH;

        // ── Footer ──
        if (row < h) {
            String errStr = lastError.isEmpty() ? "" : " | ERR: " + truncate(lastError, 40);
            String footer = String.format(
                    " Q:Quit  Up/Down:Navigate  PgUp/PgDn:Scroll | Interval: %ds  Collect: %dms%s",
                    intervalMs / 1000, collectMs, errStr);
            drawBar(screen, h - 1, w, truncate(footer, w), FG, FOOTER_BG);
        }
    }

    // ── Draw helpers ──

    private void drawMetricRow(Screen screen, int row, int col, int sparkW,
            String label, String key, Map<String, Object> metrics, String valFmt) {
        double val = dbl(metrics.get(key));
        String valStr = String.format(Locale.US, valFmt, val);
        String spark = metricsBuffer.sparkline(key, sparkW);

        int labelW = 18;
        int valW = 16;
        drawText(screen, row, col, padRight(label, labelW), HEADER_FG, BG);
        drawText(screen, row, col + labelW, padRight(valStr, valW), VALUE_FG, BG);
        drawText(screen, row, col + labelW + valW, spark, SPARK_FG, BG);
    }

    private void drawMetricRowPct(Screen screen, int row, int col, int sparkW,
            String label, String key, Map<String, Object> metrics,
            String valFmt, double baseValue) {
        double val = dbl(metrics.get(key));
        String pct = baseValue > 0.001 ? fmt("%.0f%%", val / baseValue * 100) : "-";
        String valStr = String.format(Locale.US, valFmt, val) + " (" + pct + ")";
        String spark = metricsBuffer.sparkline(key, sparkW);

        int labelW = 18;
        int valW = 16;
        drawText(screen, row, col, padRight(label, labelW), HEADER_FG, BG);
        drawText(screen, row, col + labelW, padRight(valStr, valW), VALUE_FG, BG);
        drawText(screen, row, col + labelW + valW, spark, SPARK_FG, BG);
    }

    private void drawBox(Screen screen, int row, int col, int width, int height, String title, TextColor borderColor) {
        setChar(screen, row, col, BOX_TL, borderColor, BG);
        for (int x = 1; x < width - 1; x++) {
            setChar(screen, row, col + x, BOX_H, borderColor, BG);
        }
        setChar(screen, row, col + width - 1, BOX_TR, borderColor, BG);

        if (title != null && !title.isEmpty()) {
            String t = " " + title + " ";
            drawText(screen, row, col + 2, t, HEADER_FG, BG);
        }

        for (int y = 1; y < height - 1; y++) {
            setChar(screen, row + y, col, BOX_V, borderColor, BG);
            setChar(screen, row + y, col + width - 1, BOX_V, borderColor, BG);
        }

        setChar(screen, row + height - 1, col, BOX_BL, borderColor, BG);
        for (int x = 1; x < width - 1; x++) {
            setChar(screen, row + height - 1, col + x, BOX_H, borderColor, BG);
        }
        setChar(screen, row + height - 1, col + width - 1, BOX_BR, borderColor, BG);
    }

    private void drawBar(Screen screen, int row, int width, String text, TextColor fg, TextColor bg) {
        for (int x = 0; x < width; x++) {
            char c = (x < text.length()) ? text.charAt(x) : ' ';
            setChar(screen, row, x, c, fg, bg);
        }
    }

    private void drawText(Screen screen, int row, int col, String text, TextColor fg, TextColor bg) {
        TerminalSize size = screen.getTerminalSize();
        for (int i = 0; i < text.length() && (col + i) < size.getColumns(); i++) {
            setChar(screen, row, col + i, text.charAt(i), fg, bg);
        }
    }

    private void setChar(Screen screen, int row, int col, char c, TextColor fg, TextColor bg) {
        TerminalSize size = screen.getTerminalSize();
        if (row >= 0 && row < size.getRows() && col >= 0 && col < size.getColumns()) {
            screen.setCharacter(col, row, new TextCharacter(c, fg, bg));
        }
    }

    private void handleKey(KeyStroke key, Screen screen) throws IOException {
        if (key.getKeyType() == KeyType.Character) {
            char c = key.getCharacter();
            if (c == 'q' || c == 'Q') {
                running.set(false);
            }
        } else if (key.getKeyType() == KeyType.Escape) {
            running.set(false);
        } else if (key.getKeyType() == KeyType.ArrowUp) {
            if (sessionSelect > 0)
                sessionSelect--;
        } else if (key.getKeyType() == KeyType.ArrowDown) {
            sessionSelect++;
        } else if (key.getKeyType() == KeyType.PageUp) {
            sessionSelect = Math.max(0, sessionSelect - 10);
        } else if (key.getKeyType() == KeyType.PageDown) {
            sessionSelect = Math.min(sessionSelect + 10, 10000);
        } else if (key.getKeyType() == KeyType.Home) {
            sessionSelect = 0;
            sessionScroll = 0;
        } else if (key.getKeyType() == KeyType.End) {
            sessionSelect = Integer.MAX_VALUE;
        } else if (key.getKeyType() == KeyType.Tab) {
            sqlScroll = Math.min(sqlScroll + 1, 14);
        }
    }

    // ── Formatting helpers ──

    private static TextColor waitClassColor(String waitClass) {
        if (waitClass == null)
            return FG;
        switch (waitClass) {
            case "User I/O":
                return new TextColor.RGB(0, 50, 200);
            case "System I/O":
                return new TextColor.RGB(50, 100, 200);
            case "Concurrency":
                return new TextColor.RGB(200, 50, 50);
            case "Application":
                return new TextColor.RGB(180, 0, 0);
            case "Commit":
                return new TextColor.RGB(200, 100, 0);
            case "Configuration":
                return new TextColor.RGB(150, 50, 150);
            case "Administrative":
                return new TextColor.RGB(150, 50, 150);
            case "Network":
                return new TextColor.RGB(120, 120, 0);
            case "CPU":
                return new TextColor.RGB(0, 150, 0);
            default:
                return FG;
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static double dbl(Object v) {
        if (v instanceof Number)
            return ((Number) v).doubleValue();
        if (v == null)
            return 0.0;
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static String fmt(String format, double value) {
        return String.format(Locale.US, format, value);
    }

    private static String fmtLong(double value) {
        return fmt("%,.0f", value);
    }

    private static String padRight(String s, int width) {
        if (s == null)
            s = "";
        if (s.length() >= width)
            return s.substring(0, width);
        StringBuilder sb = new StringBuilder(width);
        sb.append(s);
        for (int i = s.length(); i < width; i++)
            sb.append(' ');
        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null)
            return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    private static int intVal(String v, int fallback) {
        if (v == null || v.trim().isEmpty())
            return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
