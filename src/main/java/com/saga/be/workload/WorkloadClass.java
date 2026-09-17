package com.saga.be.workload;

/**
 * Latency-sensitivity class for SAGA work. Not READ vs WRITE.
 * Hikari has no priority queue; this label is for observability and future admission.
 */
public enum WorkloadClass {
	INTERACTIVE_LIGHT,
	INTERACTIVE_NORMAL,
	INTERACTIVE_WRITE,
	HEAVY_READ,
	BACKGROUND_SYNC,
	REALTIME
}
