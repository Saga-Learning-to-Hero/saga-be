package com.saga.be.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Admin dashboard binds {@code saga.dashboard.*}. Clock/zone logic lives on {@link
 * DashboardProperties} so student and admin dashboards share one parser. Academic {@code today} /
 * current-week identity uses this zone; persisted naive timestamps are not converted.
 */
@ConfigurationProperties(prefix = "saga.dashboard")
public class AdminDashboardProperties extends DashboardProperties {}
