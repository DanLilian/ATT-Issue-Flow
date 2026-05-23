package com.att.tdp.issueflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCrypt password encoder configured here so the user service can depend
 * on it before Spring Security is added in Phase 5.
 *
 * Strength 10 (the default) gives ~100ms per hash on commodity hardware —
 * the standard target for interactive login flows. Raise to 12+ in
 * production environments with stronger CPUs.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}