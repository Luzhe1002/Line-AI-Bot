package com.lineaibot;

import com.lineaibot.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
@SpringBootApplication
public class LineAiBotApplication {

    public static void main(String[] args) {
        DatabaseUrlProperties.apply(
                System.getenv("DATABASE_URL"), System.getenv("SPRING_DATASOURCE_URL"));
        SpringApplication.run(LineAiBotApplication.class, args);
    }
}
