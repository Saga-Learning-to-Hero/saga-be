package com.saga.be.dto.admin.dashboard;

import com.saga.be.entity.enums.IntegrationProvider;
import java.time.LocalDateTime;

/**
 * Platform-wide unique webhook delivery pulse for one supported provider.
 *
 * <p>{@code uniqueEventsReceived*} count {@code webhook_receipt} rows — one row per
 * {@code (provider, deliveryId)}. They are first-seen unique deliveries, not raw HTTP arrivals.
 * A later redelivery that marks the same row {@code DUPLICATE} does not increment either count.
 *
 * <p>{@code lastUniqueEventAt} is {@code max(createdAt)} for that provider across all time, not
 * only the last 7 days. {@code createdAt} is the first-seen unique delivery wall-clock; it is not
 * the latest HTTP request and is not {@code processedAt}.
 *
 * <p>Zero counts do not mean the provider is down. No health, latency, or success-rate fields.
 */
public record AdminDashboardIntegrationPulseResponse(
		IntegrationProvider service,
		long uniqueEventsReceived24h,
		long uniqueEventsReceived7d,
		LocalDateTime lastUniqueEventAt) {}
