package com.saga.be.service.delay;

import com.saga.be.entity.jira.Task;
import com.saga.be.repository.TaskRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Opens delay cases for tasks that missed their deadline -- still open after the due day, or done
 * on a later day -- and closes cases whose explanation window ran out. Only deadlines within
 * {@code saga.delay-cases.window} are scanned, so switching the feature on does not open cases for
 * an old semester's backlog. Each task is handled in its own transaction: one failure (or a race
 * with another instance on the unique task/due-date key) never stops the scan.
 */
@Component
@Profile("!test")
public class TaskDelayCaseScanner {

	private static final Logger log = LoggerFactory.getLogger(TaskDelayCaseScanner.class);
	private static final int BATCH = 200;

	private final TaskRepository tasks;
	private final TaskDelayCaseService service;
	private final boolean enabled;
	private final Duration window;

	public TaskDelayCaseScanner(
			TaskRepository tasks,
			TaskDelayCaseService service,
			@Value("${saga.delay-cases.enabled:true}") boolean enabled,
			@Value("${saga.delay-cases.window:7d}") Duration window) {
		this.tasks = tasks;
		this.service = service;
		this.enabled = enabled;
		this.window = window;
	}

	@Scheduled(fixedDelayString = "${saga.delay-cases.scan-interval:1h}", initialDelayString = "${saga.delay-cases.initial-delay:5m}")
	public void scan() {
		if (!enabled) {
			return;
		}
		LocalDate today = service.today();
		LocalDateTime startOfToday = today.atStartOfDay();
		LocalDateTime windowStart = startOfToday.minus(window);
		int opened = scanPages(page -> tasks.findDelayCaseOverdueCandidates(startOfToday, windowStart, page), today);
		opened += scanPages(page -> tasks.findDelayCaseCompletedLateCandidates(windowStart, page), today);
		int expired = 0;
		try {
			expired = service.expireOverdueExplanations();
		} catch (RuntimeException ex) {
			log.warn("delay case expiry failed type={}", ex.getClass().getSimpleName());
		}
		if (opened > 0 || expired > 0) {
			log.info("delay case scan opened={} expired={} asOf={}", opened, expired, today);
		}
	}

	private int scanPages(Function<Pageable, Page<Task>> query, LocalDate today) {
		int opened = 0;
		int page = 0;
		Page<Task> slice;
		do {
			slice = query.apply(PageRequest.of(page++, BATCH));
			for (Task task : slice.getContent()) {
				if (!TaskDelayCaseService.isLate(task, today)) {
					continue;
				}
				try {
					if (service.openIfAbsent(task.getId())) {
						opened++;
					}
				} catch (RuntimeException ex) {
					log.warn("delay case open failed taskId={} type={}", task.getId(), ex.getClass().getSimpleName());
				}
			}
		} while (slice.hasNext());
		return opened;
	}
}
