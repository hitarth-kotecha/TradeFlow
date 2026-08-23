package com.tradeflow.gateway.security;

import com.tradeflow.gateway.admin.AdminProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Security support beans and bound config properties. */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, AdminProperties.class})
public class SecuritySupportConfig {

    /** BCrypt at default strength 10 (satisfies "cost >= 10", §11.3). Salts each hash automatically. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
