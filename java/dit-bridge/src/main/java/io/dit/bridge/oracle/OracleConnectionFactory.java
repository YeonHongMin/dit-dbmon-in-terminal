package io.dit.bridge.oracle;

import io.dit.bridge.api.DbmsConnectionFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

public final class OracleConnectionFactory implements DbmsConnectionFactory {

    public Connection create(Map<String, String> options) throws SQLException {
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

    private static int parseInt(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
