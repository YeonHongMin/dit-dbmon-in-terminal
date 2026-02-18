package io.dit.oracle;

import java.util.HashMap;
import java.util.Map;

/**
 * Ring buffer for metric time-series data. Stores the last N values per metric
 * and renders sparkline strings using Unicode block characters.
 */
public final class MetricsBuffer {

    private static final char[] BLOCKS = {' ', '\u2581', '\u2582', '\u2583', '\u2584', '\u2585', '\u2586', '\u2587', '\u2588'};
    private static final int DEFAULT_CAPACITY = 60;

    private final int capacity;
    private final Map<String, double[]> buffers = new HashMap<String, double[]>();
    private final Map<String, Integer> positions = new HashMap<String, Integer>();
    private final Map<String, Integer> counts = new HashMap<String, Integer>();

    public MetricsBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public MetricsBuffer(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void push(String metric, double value) {
        double[] buf = buffers.get(metric);
        if (buf == null) {
            buf = new double[capacity];
            buffers.put(metric, buf);
            positions.put(metric, 0);
            counts.put(metric, 0);
        }
        int pos = positions.get(metric);
        buf[pos] = value;
        positions.put(metric, (pos + 1) % capacity);
        int cnt = counts.get(metric);
        if (cnt < capacity) {
            counts.put(metric, cnt + 1);
        }
    }

    public synchronized double[] getValues(String metric) {
        double[] buf = buffers.get(metric);
        if (buf == null) {
            return new double[0];
        }
        int cnt = counts.get(metric);
        int pos = positions.get(metric);
        double[] result = new double[cnt];
        for (int i = 0; i < cnt; i++) {
            int idx = (pos - cnt + i + capacity) % capacity;
            result[i] = buf[idx];
        }
        return result;
    }

    public synchronized double latest(String metric) {
        double[] buf = buffers.get(metric);
        if (buf == null) {
            return 0.0;
        }
        int cnt = counts.get(metric);
        if (cnt == 0) {
            return 0.0;
        }
        int pos = positions.get(metric);
        return buf[(pos - 1 + capacity) % capacity];
    }

    public synchronized String sparkline(String metric, int width) {
        double[] values = getValues(metric);
        if (values.length == 0) {
            StringBuilder sb = new StringBuilder(width);
            for (int i = 0; i < width; i++) {
                sb.append(BLOCKS[0]);
            }
            return sb.toString();
        }

        int start = Math.max(0, values.length - width);
        int len = Math.min(values.length, width);

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = start; i < values.length; i++) {
            if (values[i] < min) min = values[i];
            if (values[i] > max) max = values[i];
        }

        double range = max - min;
        StringBuilder sb = new StringBuilder(width);
        int pad = width - len;
        for (int i = 0; i < pad; i++) {
            sb.append(BLOCKS[0]);
        }
        for (int i = start; i < values.length; i++) {
            int level;
            if (range < 0.0001) {
                level = (max > 0.0001) ? 4 : 0;
            } else {
                level = (int) ((values[i] - min) / range * 8);
                if (level > 8) level = 8;
                if (level < 0) level = 0;
            }
            sb.append(BLOCKS[level]);
        }
        return sb.toString();
    }

    public synchronized int size(String metric) {
        Integer cnt = counts.get(metric);
        return cnt == null ? 0 : cnt;
    }
}
