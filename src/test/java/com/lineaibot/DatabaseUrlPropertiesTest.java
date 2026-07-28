package com.lineaibot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DatabaseUrlPropertiesTest {

    @Test
    void convertsRenderPostgresUrlToSpringDatasourceProperties() {
        Map<String, Object> properties = DatabaseUrlProperties.from(
                "postgresql://line%40bot:p%40ss%3Aword@db.internal:5433/line_ai_bot?sslmode=require");

        assertThat(properties)
                .containsEntry(
                        "spring.datasource.url",
                        "jdbc:postgresql://db.internal:5433/line_ai_bot?sslmode=require")
                .containsEntry("spring.datasource.username", "line@bot")
                .containsEntry("spring.datasource.password", "p@ss:word");
    }

    @Test
    void usesDefaultPostgresPort() {
        assertThat(DatabaseUrlProperties.from("postgres://user:secret@db.internal/app"))
                .containsEntry(
                        "spring.datasource.url",
                        "jdbc:postgresql://db.internal:5432/app");
    }

    @Test
    void ignoresMissingDatabaseUrl() {
        assertThat(DatabaseUrlProperties.from(null)).isEmpty();
        assertThat(DatabaseUrlProperties.from(" ")).isEmpty();
    }

    @Test
    void rejectsUnsupportedDatabaseUrls() {
        assertThatThrownBy(() -> DatabaseUrlProperties.from("mysql://user:secret@db/app"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postgres");
    }

    @Test
    void appliesRenderDatabaseUrlWithHigherPriorityThanApplicationYaml() {
        try {
            DatabaseUrlProperties.apply(
                    "postgres://render_user:render_password@db.internal/render_db", null);

            assertThat(System.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://db.internal:5432/render_db");
            assertThat(System.getProperty("spring.datasource.username")).isEqualTo("render_user");
            assertThat(System.getProperty("spring.datasource.password"))
                    .isEqualTo("render_password");
        } finally {
            System.clearProperty("spring.datasource.url");
            System.clearProperty("spring.datasource.username");
            System.clearProperty("spring.datasource.password");
        }
    }

    @Test
    void preservesExplicitSpringDatasourceConfiguration() {
        try {
            DatabaseUrlProperties.apply(
                    "postgres://render_user:render_password@db.internal/render_db",
                    "jdbc:postgresql://explicit/db");

            assertThat(System.getProperty("spring.datasource.url")).isNull();
        } finally {
            System.clearProperty("spring.datasource.url");
            System.clearProperty("spring.datasource.username");
            System.clearProperty("spring.datasource.password");
        }
    }
}
