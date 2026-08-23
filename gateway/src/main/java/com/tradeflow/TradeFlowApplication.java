package com.tradeflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * TradeFlow application entrypoint.
 *
 * <p>Lives in the root package {@code com.tradeflow} on purpose: {@code @SpringBootApplication}
 * component-scans this package and everything below it, so beans in every module
 * ({@code com.tradeflow.trade}, {@code com.tradeflow.risk}, ...) are discovered as we add them.
 *
 * <p>This is the single deployable of the modular monolith (DD-01).
 */
@SpringBootApplication
@EnableScheduling   // drives the outbox relay poller
public class TradeFlowApplication {

    public static void main(String[] args) {
        // TradeFlow operates entirely in UTC (spec §7). Pin the JVM default before any DB connection
        // so the Postgres JDBC driver reports a timezone the server recognises (host JVMs may default
        // to a legacy alias like "Asia/Calcutta", which Postgres 16 rejects).
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(TradeFlowApplication.class, args);
    }
}
