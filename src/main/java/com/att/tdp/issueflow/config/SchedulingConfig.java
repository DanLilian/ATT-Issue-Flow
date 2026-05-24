package com.att.tdp.issueflow.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's @Scheduled support. Kept in its own @Configuration
 * so it can be excluded from test contexts that import a curated subset
 * of configuration (avoiding spontaneous job execution during tests).
 *
 * Job cron expressions are externalized to application.yaml under the
 * issueflow.schedule.* prefix; tests override to "-" to disable per-job.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}