package io.dit.bridge.tibero;

import io.dit.bridge.api.WaitDeltaTracker;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes real-time per-second wait event deltas using V$SYSTEM_EVENT snapshots.
 * Tibero uses different column names: NAME (event), CLASS (wait_class), TIME_WAITED (centiseconds).
 */
public final class TiberoWaitDeltaTracker implements WaitDeltaTracker {

    private Map<String, long[]> prevSnapshot;  // event -> [time_waited_cs, total_waits]
    private Map<String, String> prevWaitClass;
    private long prevTimestampMs;

    public TiberoWaitDeltaTracker() {
    }

    public List<Map<String, Object>> queryDelta(Connection conn) throws SQLException {
        String sql =
            "SELECT class, name, time_waited, total_waits, \"DESC\" " +
            "FROM v$system_event " +
            "WHERE class <> 'STAT_CLASS_IDLE' " +
            "AND total_waits > 0";

        Map<String, long[]> current = new LinkedHashMap<String, long[]>();
        Map<String, String> waitClassMap = new LinkedHashMap<String, String>();
        Map<String, String> descMap = new LinkedHashMap<String, String>();

        Statement stmt = conn.createStatement();
        try {
            ResultSet rs = stmt.executeQuery(sql);
            try {
                while (rs.next()) {
                    String waitClass = rs.getString(1);
                    String name = rs.getString(2);
                    long timeWaitedCs = rs.getLong(3);  // centiseconds
                    long waits = rs.getLong(4);
                    String desc = rs.getString(5);
                    if (name != null) {
                        current.put(name, new long[]{timeWaitedCs, waits});
                        waitClassMap.put(name, TiberoCollector.normalizeWaitClass(waitClass));
                        descMap.put(name, desc != null && !desc.isEmpty() ? desc : name);
                    }
                }
            } finally {
                rs.close();
            }
        } finally {
            stmt.close();
        }

        long nowMs = System.currentTimeMillis();

        if (prevSnapshot == null) {
            prevSnapshot = current;
            prevWaitClass = waitClassMap;
            prevTimestampMs = nowMs;
            return Collections.emptyList();
        }

        double elapsedSec = (nowMs - prevTimestampMs) / 1000.0;
        if (elapsedSec < 0.5) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, long[]> entry : current.entrySet()) {
            String event = entry.getKey();
            long[] cur = entry.getValue();
            long[] prev = prevSnapshot.get(event);
            if (prev == null) {
                prev = new long[]{0, 0};
            }

            long dTimeCs = cur[0] - prev[0];  // centiseconds delta
            long dWaits = cur[1] - prev[1];
            if (dTimeCs <= 0 || dWaits < 0) continue;

            // centiseconds -> seconds per second
            double waitSecPerSec = (dTimeCs / 100.0) / elapsedSec;
            double waitsPerSec = dWaits / elapsedSec;
            // centiseconds -> milliseconds for avg
            double avgWaitMs = dWaits > 0 ? (dTimeCs * 10.0 / (double) dWaits) : 0;

            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("wait_class", waitClassMap.containsKey(event) ? waitClassMap.get(event) : "Other");
            row.put("event", descMap.containsKey(event) ? descMap.get(event) : event);
            row.put("wait_sec_per_sec", waitSecPerSec);
            row.put("waits_per_sec", waitsPerSec);
            row.put("avg_wait_ms", avgWaitMs);
            results.add(row);
        }

        Collections.sort(results, new Comparator<Map<String, Object>>() {
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                double va = ((Number) a.get("wait_sec_per_sec")).doubleValue();
                double vb = ((Number) b.get("wait_sec_per_sec")).doubleValue();
                return Double.compare(vb, va);
            }
        });

        if (results.size() > 13) {
            results = new ArrayList<Map<String, Object>>(results.subList(0, 13));
        }

        prevSnapshot = current;
        prevWaitClass = waitClassMap;
        prevTimestampMs = nowMs;

        return results;
    }
}
