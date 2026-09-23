package com.saga.be.entity.enums;

/** Persisted provenance on {@code ai_analysis_provider_decision}. The resolver's third possible
 * outcome, UNAVAILABLE, is deliberately never persisted here -- an unavailable resolution never
 * reaches a provider decision row at all. */
public enum AiCredentialSource { COURSE, PLATFORM }
