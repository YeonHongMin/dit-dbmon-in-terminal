package io.dit.bridge;

import java.util.Locale;

public enum DbmsType {
    ORACLE,
    TIBERO,
    MYSQL,
    POSTGRES,
    SQLSERVER;

    public static DbmsType fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return ORACLE;
        }
        String upper = value.trim().toUpperCase(Locale.US);
        for (DbmsType t : values()) {
            if (t.name().equals(upper)) {
                return t;
            }
        }
        return null;
    }

    public static String supportedList() {
        StringBuilder sb = new StringBuilder();
        for (DbmsType t : values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(t.name().toLowerCase(Locale.US));
        }
        return sb.toString();
    }
}
