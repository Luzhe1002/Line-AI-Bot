package com.lineaibot;

import com.lineaibot.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Map;

@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
@SpringBootApplication
public class LineAiBotApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(LineAiBotApplication.class);
        Map<String, Object> databaseProperties =
                DatabaseUrlProperties.from(System.getenv("DATABASE_URL"));
        if (!databaseProperties.isEmpty()) {
            application.setDefaultProperties(databaseProperties);
        }
        application.run(args);
    }
}
