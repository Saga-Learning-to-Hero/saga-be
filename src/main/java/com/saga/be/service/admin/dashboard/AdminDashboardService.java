package com.saga.be.service.admin.dashboard;

import com.saga.be.config.AdminDashboardProperties;
import com.saga.be.dto.admin.dashboard.AdminDashboardCacheMetadataResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardCachedPayload;
import com.saga.be.dto.admin.dashboard.AdminDashboardSummaryResponse;
import com.saga.be.entity.academic.ActiveSemesterSetting;
import com.saga.be.entity.academic.Semester;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.ActiveSemesterSettingRepository;
import com.saga.be.repository.SemesterRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Admin dashboard Phase A+B. Redis GET/lock/SET and wait loops stay outside any JDBC transaction.
 * Database aggregation runs in a short read-only {@link TransactionTemplate} that is closed
 * before the cache write. Cached aggregates are decorated with live temporal fields from
 * {@code saga.dashboard.zone} on every response.
 */
@Service
@Profile("!test")
public class AdminDashboardService {

	static final Duration DEFAULT_REFRESH_WAIT = Duration.ofSeconds(8);
	static final Duration DEFAULT_REFRESH_POLL = Duration.ofMillis(100);

	private final SemesterRepository semesters;
	private final ActiveSemesterSettingRepository activeSettings;
	private final AdminDashboardQueryService queries;
	private final AdminDashboardCacheStore cache;
	private final TransactionTemplate reads;
	private final Clock clock;
	private final AdminDashboardSleeper sleeper;
	private final Duration refreshWait;
	private final Duration refreshPoll;
	private final OwnerTokenFactory tokens;

	@Autowired
	public AdminDashboardService(
			SemesterRepository semesters,
			ActiveSemesterSettingRepository activeSettings,
			AdminDashboardQueryService queries,
			AdminDashboardCacheStore cache,
			PlatformTransactionManager transactionManager,
			AdminDashboardProperties properties) {
		this(
				semesters,
				activeSettings,
				queries,
				cache,
				transactionManager,
				properties.clock(),
				duration -> Thread.sleep(duration.toMillis()),
				DEFAULT_REFRESH_WAIT,
				DEFAULT_REFRESH_POLL,
				() -> UUID.randomUUID().toString());
	}

	public AdminDashboardService(
			SemesterRepository semesters,
			ActiveSemesterSettingRepository activeSettings,
			AdminDashboardQueryService queries,
			AdminDashboardCacheStore cache,
			PlatformTransactionManager transactionManager,
			Clock clock,
			AdminDashboardSleeper sleeper,
			Duration refreshWait,
			Duration refreshPoll,
			OwnerTokenFactory tokens) {
		this.semesters = semesters;
		this.activeSettings = activeSettings;
		this.queries = queries;
		this.cache = cache;
		TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
		readOnly.setReadOnly(true);
		this.reads = readOnly;
		this.clock = clock;
		this.sleeper = sleeper;
		this.refreshWait = refreshWait;
		this.refreshPoll = refreshPoll;
		this.tokens = tokens;
	}

	public AdminDashboardSummaryResponse summary(UUID semesterId, boolean forceRefresh) {
		Semester selected = resolveSemester(semesterId);
		UUID resolvedId = selected.getId();
		if (!forceRefresh) {
			Optional<AdminDashboardCachedPayload> hit = cache.get(resolvedId);
			if (hit.isPresent()) {
				return toResponse(hit.get(), liveMetadata(resolvedId, hit.get(), false));
			}
		}
		return singleFlight(selected);
	}

	Semester resolveSemester(UUID semesterId) {
		Semester semester;
		if (semesterId == null) {
			ActiveSemesterSetting setting = activeSettings.findByIdFetchSemester((byte) 1).orElse(null);
			semester = setting == null ? null : setting.getSemester();
			if (semester == null || semester.getDeletedAt() != null) {
				throw new AcademicException(
						AcademicErrorCode.SEMESTER_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"No active semester is configured.");
			}
		} else {
			semester = semesters
					.findById(semesterId)
					.filter(row -> row.getDeletedAt() == null)
					.orElseThrow(
							() -> new AcademicException(
									AcademicErrorCode.SEMESTER_NOT_FOUND,
									HttpStatus.NOT_FOUND,
									"Semester was not found."));
		}
		if (semester.getStartDate() == null || semester.getEndDate() == null) {
			throw new AcademicException(
					AcademicErrorCode.SEMESTER_DATE_RANGE_INVALID,
					HttpStatus.BAD_REQUEST,
					"Semester startDate and endDate are required.");
		}
		return semester;
	}

	private AdminDashboardSummaryResponse singleFlight(Semester selected) {
		UUID semesterId = selected.getId();
		Optional<AdminDashboardCachedPayload> baseline = cache.get(semesterId);
		String ownerToken = tokens.next();
		if (cache.tryLock(semesterId, ownerToken)) {
			try {
				AdminDashboardCachedPayload computed = compute(selected);
				if (cache.publishIfOwner(semesterId, ownerToken, computed)) {
					return toResponse(computed, liveMetadata(semesterId, computed, false));
				}
			} catch (RuntimeException ex) {
				cache.unlock(semesterId, ownerToken);
				throw ex;
			}
			Optional<AdminDashboardCachedPayload> published = cache.get(semesterId);
			if (published.isPresent() && generationChanged(baseline.orElse(null), published.get())) {
				return toResponse(published.get(), liveMetadata(semesterId, published.get(), false));
			}
		}
		AdminDashboardCachedPayload waited = waitForGenerationChange(semesterId, baseline.orElse(null));
		if (waited != null) {
			return toResponse(waited, liveMetadata(semesterId, waited, false));
		}
		if (baseline.isPresent()) {
			return toResponse(baseline.get(), liveMetadata(semesterId, baseline.get(), true));
		}
		throw new IntegrationException(
				IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
				HttpStatus.SERVICE_UNAVAILABLE,
				"Admin dashboard is refreshing and no cached summary is available.");
	}

	private AdminDashboardCachedPayload compute(Semester selected) {
		Instant cachedAt = clock.instant();
		return reads.execute(status -> queries.compute(selected, cachedAt));
	}

	private AdminDashboardCachedPayload waitForGenerationChange(
			UUID semesterId, AdminDashboardCachedPayload baseline) {
		Duration waited = Duration.ZERO;
		while (waited.compareTo(refreshWait) < 0) {
			Optional<AdminDashboardCachedPayload> latest = cache.get(semesterId);
			if (latest.isPresent() && generationChanged(baseline, latest.get())) {
				return latest.get();
			}
			try {
				sleeper.sleep(refreshPoll);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IntegrationException(
						IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
						HttpStatus.SERVICE_UNAVAILABLE,
						"Admin dashboard refresh wait was interrupted.");
			}
			waited = waited.plus(refreshPoll);
		}
		Optional<AdminDashboardCachedPayload> last = cache.get(semesterId);
		if (last.isPresent() && generationChanged(baseline, last.get())) {
			return last.get();
		}
		return null;
	}

	private static boolean generationChanged(
			AdminDashboardCachedPayload baseline, AdminDashboardCachedPayload latest) {
		if (baseline == null) {
			return true;
		}
		return !Objects.equals(baseline.generation(), latest.generation());
	}

	private AdminDashboardCacheMetadataResponse liveMetadata(
			UUID semesterId, AdminDashboardCachedPayload payload, boolean refreshPending) {
		Optional<Long> ttl = cache.ttlSeconds(semesterId);
		Instant expiresAt = ttl.map(seconds -> clock.instant().plusSeconds(seconds)).orElse(null);
		return new AdminDashboardCacheMetadataResponse(payload.cachedAt(), expiresAt, ttl.orElse(null), refreshPending);
	}

	private AdminDashboardSummaryResponse toResponse(
			AdminDashboardCachedPayload payload, AdminDashboardCacheMetadataResponse metadata) {
		AdminDashboardCachedPayload view = AdminDashboardTemporalView.decorate(payload, clock);
		return new AdminDashboardSummaryResponse(
				view.selectedSemester(),
				view.availableSemesters(),
				view.kpis(),
				view.weeklyTimeline(),
				metadata);
	}

	@FunctionalInterface
	public interface OwnerTokenFactory {
		String next();
	}
}
