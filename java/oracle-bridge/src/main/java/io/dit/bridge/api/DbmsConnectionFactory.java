package io.dit.bridge.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

public interface DbmsConnectionFactory {
    Connection create(Map<String, String> options) throws SQLException;
    String selfFilterColumn();
}
