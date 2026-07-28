package com.lineaibot;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class DatabaseUrlProperties {

    private DatabaseUrlProperties() {}

    static void apply(String databaseUrl, String explicitDatasourceUrl) {
        if (explicitDatasourceUrl != null && !explicitDatasourceUrl.isBlank()) {
            return;
        }
        from(databaseUrl).forEach((key, value) -> System.setProperty(key, value.toString()));
    }

    static Map<String, Object> from(String databaseUrl) {
        if (databaseUrl == null || databaseUrl.isBlank()) {
            return Map.of();
        }

        URI uri = URI.create(databaseUrl);
        if (!"postgres".equals(uri.getScheme()) && !"postgresql".equals(uri.getScheme())) {
            throw new IllegalArgumentException("DATABASE_URL must use postgres or postgresql");
        }
        if (uri.getHost() == null || uri.getRawPath() == null || uri.getRawPath().length() < 2) {
            throw new IllegalArgumentException("DATABASE_URL must include a host and database");
        }

        String[] credentials = rawCredentials(uri);
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://")
                .append(uri.getHost())
                .append(':')
                .append(port)
                .append(uri.getRawPath());
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            jdbcUrl.append('?').append(uri.getRawQuery());
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", jdbcUrl.toString());
        properties.put("spring.datasource.username", decode(credentials[0]));
        properties.put("spring.datasource.password", decode(credentials[1]));
        return properties;
    }

    private static String[] rawCredentials(URI uri) {
        String userInfo = uri.getRawUserInfo();
        if (userInfo == null) {
            throw new IllegalArgumentException("DATABASE_URL must include database credentials");
        }
        int separator = userInfo.indexOf(':');
        if (separator < 1) {
            throw new IllegalArgumentException("DATABASE_URL must include a username and password");
        }
        return new String[] {
            userInfo.substring(0, separator),
            userInfo.substring(separator + 1)
        };
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
