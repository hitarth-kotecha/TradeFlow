package com.tradeflow.gateway.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Platform-admin credential (bound from {@code app.admin.*}); from env in real deploys. */
@ConfigurationProperties(prefix = "app.admin")
public record AdminProperties(String apiKey) {
}
