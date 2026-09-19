package com.saga.be.service.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.admin.dashboard.AdminDashboardCachedPayload;
import com.saga.be.dto.admin.dashboard.AdminDashboardKpisResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardSelectedSemesterResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardSummaryResponse;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceCacheTest {

	private static final Instant NOW = Instant.parse("2026-09-19T04:00:00Z");
	private static final UUID SEMESTER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

	@Mock
	private SemesterRepository semesters;
	@Mock
	private ActiveSemesterSettingRepository activeSettings;
	@Mock
	private AdminDashboardQueryService queries;

	private MemoryCache cache;
	private AdminDashboardService service;

	@BeforeEach
	void setUp() {
		cache = new MemoryCache();
		service = service(cache, duration -> {}, Duration.ofMillis(40), Duration.ofMillis(10));
	}

	@Test
	void missComputesAndCaches() {
		Semester semester = semester();
		when(semesters.findById(SEMESTER_ID)).thenReturn(Optional.of(semester));
		when(queries.compute(eq(semester), any())).thenReturn(payload("g1", 4));
		AdminDashboardSummaryResponse response = service.summary(SEMESTER_ID, false);
		assertThat(response.kpis().totalStudents()).isEqualTo(4);
		assertThat(cache.values).containsKey(SEMESTER_ID);
		assertThat(response.cacheMetadata().refreshPending()).isFalse();
		verify(queries, times(1)).compute(eq(semester), any());
	}

	@Test
	void hitDoesNotRecomputeAggregates() {
		Semester semester = semester();
		when(semesters.findById(SEMESTER_ID)).thenReturn(Optional.of(semester));
		cache.values.put(SEMESTER_ID, payload("g1", 9));
		AdminDashboardSummaryResponse response = service.summary(SEMESTER_ID, false);
		assertThat(response.kpis().totalStudents()).isEqualTo(9);
		verify(queries, never()).compute(any(), any());
	}

	@Test
	void forceRefreshRecomputesEvenWhenCacheExists() {
		Semester semester = semester();
		when(semesters.findById(SEMESTER_ID)).thenReturn(Optional.of(semester));
		cache.values.put(SEMESTER_ID, payload("g1", 1));
		when(queries.compute(eq(semester), any())).thenReturn(payload("g2", 8));
		AdminDashboardSummaryResponse response = service.summary(SEMESTER_ID, true);
		assertThat(response.kpis().totalStudents()).isEqualTo(8);
		assertThat(cache.values.get(SEMESTER_ID).generation()).isEqualTo("g2");
		verify(queries, times(1)).compute(eq(semester), any());
	}

	@Test
	void concurrentMisses_oneLockOwnerComputes() throws Exception {
		assertSingleFlight(false, "g-owner", 5);
	}

	@Test
	void concurrentForceRefresh_followerObservesNewGeneration() throws Exception {
		cache.values.put(SEMESTER_ID, payload("g1", 1));
		AdminDashboardSummaryResponse followed = assertSingleFlight(true, "g2", 22);
		assertThat(followed.cacheMetadata().refreshPending()).isFalse();
	}

	private AdminDashboardSummaryResponse assertSingleFlight(boolean forceRefresh, String generation, long students)
			throws Exception {
		Semester semester = semester();
		when(semesters.findById(SEMESTER_ID)).thenReturn(Optional.of(semester));
		AtomicInteger computes = new AtomicInteger();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch followerWaiting = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		when(queries.compute(eq(semester), any())).thenAnswer(invocation -> {
			computes.incrementAndGet();
			entered.countDown();
			assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
			return payload(generation, students);
		});
		MemoryCache gated = new MemoryCache();
		gated.lockFailed = followerWaiting;
		gated.values.putAll(cache.values);
		AdminDashboardService concurrent =
				service(gated, duration -> Thread.sleep(5), Duration.ofSeconds(2), Duration.ofMillis(5));
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<AdminDashboardSummaryResponse> owner = pool.submit(() -> concurrent.summary(SEMESTER_ID, forceRefresh));
			assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
			Future<AdminDashboardSummaryResponse> follower =
					pool.submit(() -> concurrent.summary(SEMESTER_ID, forceRefresh));
			assertThat(followerWaiting.await(2, TimeUnit.SECONDS)).isTrue();
			release.countDown();
			assertThat(owner.get(2, TimeUnit.SECONDS).kpis().totalStudents()).isEqualTo(students);
			AdminDashboardSummaryResponse followed = follower.get(2, TimeUnit.SECONDS);
			assertThat(followed.kpis().totalStudents()).isEqualTo(students);
			assertThat(computes.get()).isEqualTo(1);
			return followed;
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void failedComputePreservesStaleCache_andReleasesOnlyOwnLock() {
		Semester semester = semester();
		when(semesters.findById(SEMESTER_ID)).thenReturn(Optional.of(semester));
		cache.values.put(SEMESTER_ID, payload("stale", 3));
		when(queries.compute(eq(semester), any())).thenThrow(new IllegalStateException("compute failed"));
		assertThatThrownBy(() -> service.summary(SEMESTER_ID, true)).isInstanceOf(IllegalStateException.class);
		assertThat(cache.values.get(SEMESTER_ID).generation()).isEqualTo("stale");
		assertThat(cache.values.get(SEMESTER_ID).kpis().totalStudents()).isEqualTo(3);
		assertThat(cache.locks).isEmpty();
	}

	@Test
	void failedComputeDoesNotUnlockNewerOwner() {
		Semester semester = semester();
		when(semesters.findById(SEMESTER_ID)).thenReturn(Optional.of(semester));
		cache.values.put(SEMESTER_ID, payload("stale", 3));
		when(queries.compute(eq(semester), any())).thenAnswer(invocation -> {
			cache.locks.put(SEMESTER_ID, "newer-owner");
			throw new IllegalStateException("compute failed");
		});
		assertThatThrownBy(() -> service.summary(SEMESTER_ID, true)).isInstanceOf(IllegalStateException.class);
		assertThat(cache.locks.get(SEMESTER_ID)).isEqualTo("newer-owner");
		assertThat(cache.values.get(SEMESTER_ID).generation()).isEqualTo("stale");
	}

	@Test
	void staleOwnerCannotOverwriteNewerPublishedGeneration() {
		Semester semester = semester();
		when(semesters.findById(SEMESTER_ID)).thenReturn(Optional.of(semester));
		cache.values.put(SEMESTER_ID, payload("g-old", 1));
		when(queries.compute(eq(semester), any())).thenAnswer(invocation -> {
			cache.locks.put(SEMESTER_ID, "owner-b");
			cache.values.put(SEMESTER_ID, payload("gen-b", 22));
			cache.ttls.put(SEMESTER_ID, 600L);
			return payload("gen-a-late", 99);
		});
		AdminDashboardSummaryResponse response = service.summary(SEMESTER_ID, true);
		assertThat(response.kpis().totalStudents()).isEqualTo(22);
		assertThat(cache.values.get(SEMESTER_ID).generation()).isEqualTo("gen-b");
		assertThat(cache.values.get(SEMESTER_ID).kpis().totalStudents()).isEqualTo(22);
		assertThat(cache.locks.get(SEMESTER_ID)).isEqualTo("owner-b");
		verify(queries, times(1)).compute(eq(semester), any());
	}

	@Test
	void staleFallbackMarksRefreshPending() {
		Semester semester = semester();
		when(semesters.findById(SEMESTER_ID)).thenReturn(Optional.of(semester));
		cache.values.put(SEMESTER_ID, payload("stale", 7));
		cache.locks.put(SEMESTER_ID, "other-owner");
		AdminDashboardService immediate =
				service(cache, duration -> {}, Duration.ZERO, Duration.ZERO);
		AdminDashboardSummaryResponse response = immediate.summary(SEMESTER_ID, true);
		assertThat(response.kpis().totalStudents()).isEqualTo(7);
		assertThat(response.cacheMetadata().refreshPending()).isTrue();
		verify(queries, never()).compute(any(), any());
	}

	@Test
	void forceRefreshTimeoutWithoutCacheUsesIntegrationUnavailable() {
		Semester semester = semester();
		when(semesters.findById(SEMESTER_ID)).thenReturn(Optional.of(semester));
		cache.locks.put(SEMESTER_ID, "other-owner");
		AdminDashboardService immediate =
				service(cache, duration -> {}, Duration.ZERO, Duration.ZERO);
		assertThatThrownBy(() -> immediate.summary(SEMESTER_ID, true))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> {
					IntegrationException integration = (IntegrationException) ex;
					assertThat(integration.getCode()).isEqualTo(IntegrationErrorCode.INTEGRATION_UNAVAILABLE);
					assertThat(integration.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
				});
	}

	@Test
	void redisUnavailablePropagatesConnectionFailure() {
		Semester semester = semester();
		when(semesters.findById(SEMESTER_ID)).thenReturn(Optional.of(semester));
		cache.failGet = true;
		assertThatThrownBy(() -> service.summary(SEMESTER_ID, false))
				.isInstanceOf(RedisConnectionFailureException.class);
	}

	@Test
	void omittedSemesterIdUsesActiveSingleton_andMissingActiveIs404() {
		when(activeSettings.findByIdFetchSemester((byte) 1)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.summary(null, false))
				.isInstanceOf(AcademicException.class)
				.satisfies(ex -> {
					AcademicException academic = (AcademicException) ex;
					assertThat(academic.getCode()).isEqualTo(AcademicErrorCode.SEMESTER_NOT_FOUND);
					assertThat(academic.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
					assertThat(academic.getMessage()).isEqualTo("No active semester is configured.");
				});
	}

	@Test
	void nullSemesterDatesAre400() {
		Semester semester = semester();
		semester.setEndDate(null);
		when(semesters.findById(SEMESTER_ID)).thenReturn(Optional.of(semester));
		assertThatThrownBy(() -> service.summary(SEMESTER_ID, false))
				.isInstanceOf(AcademicException.class)
				.satisfies(ex -> {
					AcademicException academic = (AcademicException) ex;
					assertThat(academic.getCode()).isEqualTo(AcademicErrorCode.SEMESTER_DATE_RANGE_INVALID);
					assertThat(academic.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
				});
	}

	@Test
	void liveTtlMetadataIsAttachedOnHit() {
		Semester semester = semester();
		when(semesters.findById(SEMESTER_ID)).thenReturn(Optional.of(semester));
		cache.values.put(SEMESTER_ID, payload("g1", 2));
		cache.ttls.put(SEMESTER_ID, 580L);
		AdminDashboardSummaryResponse response = service.summary(SEMESTER_ID, false);
		assertThat(response.cacheMetadata().ttlSecondsRemaining()).isEqualTo(580L);
		assertThat(response.cacheMetadata().expiresAt()).isEqualTo(NOW.plusSeconds(580));
		assertThat(response.cacheMetadata().cachedAt()).isEqualTo(Instant.parse("2026-09-19T03:50:00Z"));
	}

	private AdminDashboardService service(
			AdminDashboardCacheStore store,
			AdminDashboardSleeper sleeper,
			Duration wait,
			Duration poll) {
		return new AdminDashboardService(
				semesters,
				activeSettings,
				queries,
				store,
				passthroughTm(),
				Clock.fixed(NOW, ZoneOffset.UTC),
				sleeper,
				wait,
				poll,
				() -> "owner-token");
	}

	private static PlatformTransactionManager passthroughTm() {
		return new PlatformTransactionManager() {
			@Override
			public TransactionStatus getTransaction(TransactionDefinition definition) {
				return new SimpleTransactionStatus();
			}

			@Override
			public void commit(TransactionStatus status) {}

			@Override
			public void rollback(TransactionStatus status) {}
		};
	}

	private static Semester semester() {
		Semester semester = new Semester();
		semester.setId(SEMESTER_ID);
		semester.setCode("FA26");
		semester.setName("Fall");
		semester.setStartDate(LocalDateTime.of(2026, 9, 1, 0, 0));
		semester.setEndDate(LocalDateTime.of(2026, 12, 15, 0, 0));
		return semester;
	}

	private static AdminDashboardCachedPayload payload(String generation, long students) {
		return new AdminDashboardCachedPayload(
				generation,
				Instant.parse("2026-09-19T03:50:00Z"),
				new AdminDashboardSelectedSemesterResponse(
						SEMESTER_ID,
						"FA26",
						"Fall",
						LocalDate.of(2026, 9, 1),
						LocalDate.of(2026, 12, 15),
						16,
						3,
						true),
				List.of(),
				new AdminDashboardKpisResponse(students, null, null, 0, 0, 0, null, 0, 0, null));
	}

	static final class MemoryCache implements AdminDashboardCacheStore {
		final ConcurrentHashMap<UUID, AdminDashboardCachedPayload> values = new ConcurrentHashMap<>();
		final ConcurrentHashMap<UUID, String> locks = new ConcurrentHashMap<>();
		final ConcurrentHashMap<UUID, Long> ttls = new ConcurrentHashMap<>();
		volatile boolean failGet;
		volatile CountDownLatch lockFailed;

		@Override
		public Optional<AdminDashboardCachedPayload> get(UUID semesterId) {
			if (failGet) {
				throw new RedisConnectionFailureException("redis down");
			}
			return Optional.ofNullable(values.get(semesterId));
		}

		@Override
		public synchronized boolean publishIfOwner(
				UUID semesterId, String ownerToken, AdminDashboardCachedPayload payload) {
			if (!ownerToken.equals(locks.get(semesterId))) {
				return false;
			}
			values.put(semesterId, payload);
			ttls.put(semesterId, 600L);
			locks.remove(semesterId, ownerToken);
			return true;
		}

		@Override
		public Optional<Long> ttlSeconds(UUID semesterId) {
			return Optional.ofNullable(ttls.get(semesterId));
		}

		@Override
		public boolean tryLock(UUID semesterId, String ownerToken) {
			boolean acquired = locks.putIfAbsent(semesterId, ownerToken) == null;
			if (!acquired && lockFailed != null) {
				lockFailed.countDown();
			}
			return acquired;
		}

		@Override
		public synchronized boolean unlock(UUID semesterId, String ownerToken) {
			return locks.remove(semesterId, ownerToken);
		}
	}
}
