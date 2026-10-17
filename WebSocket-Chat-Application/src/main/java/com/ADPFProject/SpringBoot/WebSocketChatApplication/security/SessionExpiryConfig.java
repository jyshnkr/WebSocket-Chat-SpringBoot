package com.ADPFProject.SpringBoot.WebSocketChatApplication.security;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SessionExpiryConfig {

    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler sessionExpiryScheduler(Clock clock) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("session-expiry-");
        scheduler.setClock(clock);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
