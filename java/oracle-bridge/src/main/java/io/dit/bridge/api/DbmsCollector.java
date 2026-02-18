package io.dit.bridge.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

public interface DbmsCollector {
    Map<String, Object> collectAll(Connection conn);
    Map<String, Object> mapMetrics(Map<String, Object> raw1, Map<String, Object> raw2);
    Map<String, Object> queryInstanceInfo(Connection conn) throws SQLException;
    String queryServerTime(Connection conn) throws SQLException;
}
