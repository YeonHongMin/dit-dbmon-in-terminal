package io.dit.bridge.tibero;

import io.dit.bridge.api.DbmsConnectionFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

public final class TiberoConnectionFactory implements DbmsConnectionFactory {

    public Connection create(Map<String, String> options) throws SQLException {
        String host = required(options, "host");
        String port = required(options, "port");
        String dbName = required(options, "service-name");
        String user = required(options, "user");
        String password = required(options, "password");

        String jdbcUrl = "jdbc:tibero:thin:@" + host + ":" + port + ":" + dbName;

        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);

        Connection conn = DriverManager.getConnection(jdbcUrl, properties);

        // Set program name for self-session filtering
        try {
            Statement stmt = conn.createStatement();
            try {
                stmt.execute("CALL DBMS_APPLICATION_INFO.SET_MODULE('dit-bridge', 'monitor')");
            } finally {
                stmt.close();
            }
        } catch (SQLException ignored) {
            // DBMS_APPLICATION_INFO may not be available
        }

        return conn;
    }

    public String selfFilterColumn() {
        return "dit-bridge";
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new RuntimeException("Missing required argument: --" + key);
        }
        return value.trim();
    }
}
