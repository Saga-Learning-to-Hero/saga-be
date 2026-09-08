package com.saga.be.web;

/**
 * Request-scoped timing attributes for production-safe phase diagnosis.
 * Values are durations/nanos only — never session ids, cookies, or tokens.
 */
public final class RequestPhaseAttrs {

	public static final String FILTER_START_NANOS = "saga.timing.filterStartNanos";
	public static final String SERVICE_START_NANOS = "saga.timing.serviceStartNanos";
	public static final String SERVICE_END_NANOS = "saga.timing.serviceEndNanos";
	public static final String CONTROLLER_START_NANOS = "saga.timing.controllerStartNanos";
	public static final String CONTROLLER_END_NANOS = "saga.timing.controllerEndNanos";

	public static final String SESSION_FIND_MS = "saga.timing.sessionFindMs";
	public static final String SESSION_SAVE_MS = "saga.timing.sessionSaveMs";
	public static final String SESSION_FIND_COUNT = "saga.timing.sessionFindCount";
	public static final String SESSION_SAVE_COUNT = "saga.timing.sessionSaveCount";

	public static final String HIKARI_ACTIVE = "saga.timing.hikariActive";
	public static final String HIKARI_IDLE = "saga.timing.hikariIdle";
	public static final String HIKARI_PENDING = "saga.timing.hikariPending";
	public static final String HIKARI_ACQUIRE_MS = "saga.timing.hikariAcquireMs";

	private RequestPhaseAttrs() {}
}
