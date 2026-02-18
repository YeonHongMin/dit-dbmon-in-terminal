package io.dit.bridge.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public interface WaitDeltaTracker {
    List<Map<String, Object>> queryDelta(Connection conn) throws SQLException;
}
