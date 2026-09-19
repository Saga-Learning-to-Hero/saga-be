package com.saga.be.service.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.admin.dashboard.AdminDashboardCachedPayload;
import com.saga.be.dto.admin.dashboard.AdminDashboardIntegrationPulseCachedPayload;
import com.saga.be.dto.admin.dashboard.AdminDashboardIntegrationPulseResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardKpisResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardSelectedSemesterResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardSummaryResponse;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.enums.IntegrationProvider;
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
class AdminDashboardPulseCacheTest {

	private static final Instant NOW = Instant.parse("2026-09-19T04:00:00Z");
	private static final UUID SEMESTER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

	@Mock
	private AdminDashboardPulseQueryService queries;
	@Mock
	private SemesterRepository semesters;
	@Mock
	private ActiveSemesterSettingRepository activeSettings;
	@Mock
	private AdminDashboardQueryService summaryQueries;

	private MemoryPulseCache cache;
	private Clock clock;
	private AdminDashboardPulseService service;

	@BeforeEach
	void setUp() {
		cache = new MemoryPulseCache();
		clock = Clock.fixed(NOW, ZoneOffset.UTC);
		service = service(cache, duration -> {}, Duration.ofMillis(40), Duration.ofMillis(10));
	}

	@Test
	void missComputesAndCaches() {
		when(queries.compute()).thenReturn(pulse(2, 3));
		List<AdminDashboardIntegrationPulseResponse> loaded = service.load(false);
		assertThat(loaded.getFirst().uniqueEventsReceived24h()).isEqualTo(2L);
		assertThat(cache.value).isNotNull();
		assertThat(cache.ttlSeconds).isEqualTo(60L);
		verify(queries, times(1)).compute();
	}

	@Test
	void hitDoesNotRunSql() {
		cache.value = cached("g1", pulse(9, 11));
		List<AdminDashboardIntegrationPulseResponse> loaded = service.load(false);
		assertThat(loaded.getFirst().uniqueEventsReceived24h()).isEqualTo(9L);
		verify(queries, never()).compute();
	}

	@Test
	void forceRefreshRecomputesEvenWhenCacheExists() {
		cache.value = cached("g1", pulse(1, 1));
		when(queries.compute()).thenReturn(pulse(8, 8));
		List<AdminDashboardIntegrationPulseResponse> loaded = service.load(true);
		assertThat(loaded.getFirst().uniqueEventsReceived24h()).isEqualTo(8L);
		assertThat(cache.value.integrationPulse().getFirst().uniqueEventsReceived24h()).isEqualTo(8L);
		verify(queries, times(1)).compute();
	}

	@Test
	void concurrentMisses_onePublisher() throws Exception {
		assertSingleFlight(false, 5);
	}

	@Test
	void concurrentForceRefresh_onePublisher() throws Exception {
		cache.value = cached("g1", pulse(1, 1));
		assertSingleFlight(true, 22);
	}

	@Test
	void staleOwnerCannotOverwriteNewerPulse() {
		cache.value = cached("g-old", pulse(1, 1));
		when(queries.compute()).thenAnswer(invocation -> {
			cache.lock = "owner-b";
			cache.value = cached("gen-b", pulse(22, 22));
			cache.ttlSeconds = 60L;
			return pulse(99, 99);
		});
		List<AdminDashboardIntegrationPulseResponse> loaded = service.load(true);
		assertThat(loaded.getFirst().uniqueEventsReceived24h()).isEqualTo(22L);
		assertThat(cache.value.generation()).isEqualTo("gen-b");
		assertThat(cache.lock).isEqualTo("owner-b");
		verify(queries, times(1)).compute();
	}

	@Test
	void failedComputePreservesOldPulseAndReleasesOwnLock() {
		cache.value = cached("stale", pulse(3, 3));
		when(queries.compute()).thenThrow(new IllegalStateException("pulse failed"));
		assertThatThrownBy(() -> service.load(true)).isInstanceOf(IllegalStateException.class);
		assertThat(cache.value.generation()).isEqualTo("stale");
		assertThat(cache.value.integrationPulse().getFirst().uniqueEventsReceived24h()).isEqualTo(3L);
		assertThat(cache.lock).isNull();
	}

	@Test
	void failedComputeDoesNotUnlockNewerOwner() {
		cache.value = cached("stale", pulse(3, 3));
		when(queries.compute()).thenAnswer(invocation -> {
			cache.lock = "newer-owner";
			throw new IllegalStateException("pulse failed");
		});
		assertThatThrownBy(() -> service.load(true)).isInstanceOf(IllegalStateException.class);
		assertThat(cache.lock).isEqualTo("newer-owner");
		assertThat(cache.value.generation()).isEqualTo("stale");
	}

	@Test
	void stalePulseFallbackWhenLockIsBusy() {
		cache.value = cached("stale", pulse(7, 7));
		cache.lock = "other-owner";
		AdminDashboardPulseService immediate =
				service(cache, duration -> {}, Duration.ZERO, Duration.ZERO);
		List<AdminDashboardIntegrationPulseResponse> loaded = immediate.load(true);
		assertThat(loaded.getFirst().uniqueEventsReceived24h()).isEqualTo(7L);
		verify(queries, never()).compute();
	}

	@Test
	void lockBusyWithoutCacheUsesIntegrationUnavailable() {
		cache.lock = "other-owner";
		AdminDashboardPulseService immediate =
				service(cache, duration -> {}, Duration.ZERO, Duration.ZERO);
		assertThatThrownBy(() -> immediate.load(true))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> {
					IntegrationException integration = (IntegrationException) ex;
					assertThat(integration.getCode()).isEqualTo(IntegrationErrorCode.INTEGRATION_UNAVAILABLE);
					assertThat(integration.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
				});
	}

	@Test
	void redisUnavailablePropagatesConnectionFailure() {
		cache.failGet = true;
		assertThatThrownBy(() -> service.load(false)).isInstanceOf(RedisConnectionFailureException.class);
	}

	@Test
	void pulseTtlIsIndependentOfSemesterSummaryTtl() {
		AdminDashboardServiceCacheTest.MemoryCache summaryCache = new AdminDashboardServiceCacheTest.MemoryCache();
		summaryCache.values.put(SEMESTER_ID, summaryPayload("g1", 4));
		summaryCache.ttls.put(SEMESTER_ID, 600L);
		when(semesters.findById(SEMESTER_ID)).thenReturn(Optional.of(semester()));
		when(queries.compute()).thenReturn(pulse(2, 4));
		AdminDashboardService dashboard = new AdminDashboardService(
				semesters,
				activeSettings,
				summaryQueries,
				summaryCache,
				service,
				passthroughTm(),
				clock,
				duration -> {},
				Duration.ofMillis(40),
				Duration.ofMillis(10),
				() -> "owner-token");
		AdminDashboardSummaryResponse response = dashboard.summary(SEMESTER_ID, false);
		assertThat(response.integrationPulse().getFirst().uniqueEventsReceived24h()).isEqualTo(2L);
		assertThat(summaryCache.ttls.get(SEMESTER_ID)).isEqualTo(600L);
		assertThat(cache.ttlSeconds).isEqualTo(60L);
		verify(summaryQueries, never()).compute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void forceRefreshRefreshesSummaryAndPulseIndependently() {
		AdminDashboardServiceCacheTest.MemoryCache summaryCache = new AdminDashboardServiceCacheTest.MemoryCache();
		summaryCache.values.put(SEMESTER_ID, summaryPayload("g1", 1));
		cache.value = cached("p1", pulse(1, 1));
		when(semesters.findById(SEMESTER_ID)).thenReturn(Optional.of(semester()));
		when(summaryQueries.compute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
				.thenReturn(summaryPayload("g2", 8));
		when(queries.compute()).thenReturn(pulse(5, 6));
		AdminDashboardService dashboard = new AdminDashboardService(
				semesters,
				activeSettings,
				summaryQueries,
				summaryCache,
				service,
				passthroughTm(),
				clock,
				duration -> {},
				Duration.ofMillis(40),
				Duration.ofMillis(10),
				() -> "owner-token");
		AdminDashboardSummaryResponse response = dashboard.summary(SEMESTER_ID, true);
		assertThat(response.kpis().totalStudents()).isEqualTo(8L);
		assertThat(response.integrationPulse().getFirst().uniqueEventsReceived24h()).isEqualTo(5L);
		assertThat(summaryCache.values.get(SEMESTER_ID).generation()).isEqualTo("g2");
		assertThat(cache.value.integrationPulse().getFirst().uniqueEventsReceived24h()).isEqualTo(5L);
		verify(summaryQueries, times(1))
				.compute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
		verify(queries, times(1)).compute();
	}

	private List<AdminDashboardIntegrationPulseResponse> assertSingleFlight(boolean forceRefresh, long count)
			throws Exception {
		AtomicInteger computes = new AtomicInteger();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch followerWaiting = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		when(queries.compute()).thenAnswer(invocation -> {
			computes.incrementAndGet();
			entered.countDown();
			assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
			return pulse(count, count);
		});
		MemoryPulseCache gated = new MemoryPulseCache();
		gated.lockFailed = followerWaiting;
		gated.value = cache.value;
		AdminDashboardPulseService concurrent =
				service(gated, duration -> Thread.sleep(5), Duration.ofSeconds(2), Duration.ofMillis(5));
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<List<AdminDashboardIntegrationPulseResponse>> owner =
					pool.submit(() -> concurrent.load(forceRefresh));
			assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
			Future<List<AdminDashboardIntegrationPulseResponse>> follower =
					pool.submit(() -> concurrent.load(forceRefresh));
			assertThat(followerWaiting.await(2, TimeUnit.SECONDS)).isTrue();
			release.countDown();
			assertThat(owner.get(2, TimeUnit.SECONDS).getFirst().uniqueEventsReceived24h()).isEqualTo(count);
			List<AdminDashboardIntegrationPulseResponse> followed = follower.get(2, TimeUnit.SECONDS);
			assertThat(followed.getFirst().uniqueEventsReceived24h()).isEqualTo(count);
			assertThat(computes.get()).isEqualTo(1);
			return followed;
		} finally {
			pool.shutdownNow();
		}
	}

	private AdminDashboardPulseService service(
			AdminDashboardPulseCacheStore store,
			AdminDashboardSleeper sleeper,
			Duration wait,
			Duration poll) {
		return new AdminDashboardPulseService(
				queries, store, passthroughTm(), clock, sleeper, wait, poll, () -> "owner-token");
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

	private static List<AdminDashboardIntegrationPulseResponse> pulse(long received24h, long received7d) {
		return List.of(
				new AdminDashboardIntegrationPulseResponse(
						IntegrationProvider.GITHUB, received24h, received7d, LocalDateTime.of(2026, 9, 19, 3, 0)),
				new AdminDashboardIntegrationPulseResponse(IntegrationProvider.JIRA, 0L, 0L, null));
	}

	private static AdminDashboardIntegrationPulseCachedPayload cached(
			String generation, List<AdminDashboardIntegrationPulseResponse> points) {
		return new AdminDashboardIntegrationPulseCachedPayload(generation, NOW, points);
	}

	private static AdminDashboardCachedPayload summaryPayload(String generation, long students) {
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
				new AdminDashboardKpisResponse(students, null, null, 0, 0, 0, null, 0, 0, null),
				List.of(),
				List.of());
	}

	static final class MemoryPulseCache implements AdminDashboardPulseCacheStore {
		volatile AdminDashboardIntegrationPulseCachedPayload value;
		volatile String lock;
		volatile Long ttlSeconds;
		volatile boolean failGet;
		volatile CountDownLatch lockFailed;

		@Override
		public Optional<AdminDashboardIntegrationPulseCachedPayload> get() {
			if (failGet) {
				throw new RedisConnectionFailureException("redis down");
			}
			return Optional.ofNullable(value);
		}

		@Override
		public synchronized boolean publishIfOwner(
				String ownerToken, AdminDashboardIntegrationPulseCachedPayload payload) {
			if (!ownerToken.equals(lock)) {
				return false;
			}
			value = payload;
			ttlSeconds = 60L;
			lock = null;
			return true;
		}

		@Override
		public Optional<Long> ttlSeconds() {
			return Optional.ofNullable(ttlSeconds);
		}

		@Override
		public boolean tryLock(String ownerToken) {
			synchronized (this) {
				if (lock != null) {
					CountDownLatch failed = lockFailed;
					if (failed != null) {
						failed.countDown();
					}
					return false;
				}
				lock = ownerToken;
				return true;
			}
		}

		@Override
		public synchronized boolean unlock(String ownerToken) {
			if (!ownerToken.equals(lock)) {
				return false;
			}
			lock = null;
			return true;
		}
	}
}
