package com.lineaibot.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LineWorkerConfiguration {

    @Bean(name = "lineEventExecutor", destroyMethod = "close")
    ExecutorService lineEventExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(name = "lineEventPermits")
    Semaphore lineEventPermits(AppProperties properties) {
        return new Semaphore(properties.getLineWorkerConcurrency());
    }
}
