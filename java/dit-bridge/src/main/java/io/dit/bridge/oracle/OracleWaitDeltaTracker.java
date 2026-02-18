package io.dit.bridge.oracle;

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
 * Unlike V$EVENTMETRIC (60-second lag), this produces deltas that match the
 * collection interval and drop to zero immediately when workload stops.
 */
public final class OracleWaitDeltaTracker implements WaitDeltaTracker {

    private Map<String, long[]> prevSnapshot;  // event -> [time_waited_micro, total_waits]
    private Map<String, String> prevWaitClass;  // event -> wait_class
    private long prevTimestampMs;

    public OracleWaitDeltaTracker() {
    }

    /**
     * Query V$SYSTEM_EVENT and compute per-second delta since last call.
     * First call establishes baseline and returns empty list.
     * Returns list with keys: wait_class, event, wait_sec_per_sec, waits_per_sec, avg_wait_ms
     */
    public List<Map<String, Object>> queryDelta(Connection conn) throws SQLException {
        String sql =
            "SELECT wait_class, event, time_waited_micro, total_waits " +
            "FROM v$system_event " +
            "WHERE wait_class <> 'Idle'";

        Map<String, long[]> current = new LinkedHashMap<String, long[]>();
        Map<String, String> waitClassMap = new LinkedHashMap<String, String>();

        Statement stmt = conn.createStatement();
        try {
            ResultSet rs = stmt.executeQuery(sql);
            try {
                while (rs.next()) {
                    String waitClass = rs.getString(1);
                    String event = rs.getString(2);
                    long timeMicro = rs.getLong(3);
                    long waits = rs.getLong(4);
                    if (event != null) {
                        current.put(event, new long[]{timeMicro, waits});
                        waitClassMap.put(event, waitClass != null ? waitClass : "Other");
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

            long dTimeMicro = cur[0] - prev[0];
            long dWaits = cur[1] - prev[1];
            if (dTimeMicro <= 0 || dWaits < 0) continue;

            double waitSecPerSec = (dTimeMicro / 1_000_000.0) / elapsedSec;
            double waitsPerSec = dWaits / elapsedSec;
            double avgWaitMs = dWaits > 0 ? (dTimeMicro / (double) dWaits / 1000.0) : 0;

            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("wait_class", waitClassMap.containsKey(event) ? waitClassMap.get(event) : "Other");
            row.put("event", event);
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

        if (results.size() > 12) {
            results = new ArrayList<Map<String, Object>>(results.subList(0, 12));
        }

        prevSnapshot = current;
        prevWaitClass = waitClassMap;
        prevTimestampMs = nowMs;

        return results;
    }
}
