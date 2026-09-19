package com.saga.be.service.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TaskWorkSessionTimelineServiceElapsedTest {

	@Test
	void elapsedUsesEndedAtWhenPresentIncludingDirtyOpen() {
		LocalDateTime start = LocalDateTime.of(2026, 9, 14, 10, 0, 0);
		LocalDateTime end = start.plusMinutes(5);
		LocalDateTime now = start.plusHours(1);
		assertEquals(300L, TaskWorkSessionTimelineService.elapsedSeconds(start, end, now));
	}

	@Test
	void elapsedUsesCapturedNowWhenEndedAtNull() {
		LocalDateTime start = LocalDateTime.of(2026, 9, 14, 10, 0, 0);
		LocalDateTime now = start.plusMinutes(10);
		assertEquals(600L, TaskWorkSessionTimelineService.elapsedSeconds(start, null, now));
	}

	@Test
	void elapsedClampsNegativeToZero() {
		LocalDateTime start = LocalDateTime.of(2026, 9, 14, 10, 0, 0);
		LocalDateTime end = start.minusMinutes(1);
		assertEquals(0L, TaskWorkSessionTimelineService.elapsedSeconds(start, end, start.plusHours(1)));
	}

	@Test
	void elapsedNullStartIsZero() {
		assertEquals(0L, TaskWorkSessionTimelineService.elapsedSeconds(null, LocalDateTime.now(), LocalDateTime.now()));
	}
}
