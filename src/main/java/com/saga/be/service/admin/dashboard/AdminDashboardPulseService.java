package com.saga.be.service.admin.dashboard;

import com.saga.be.config.AdminDashboardProperties;
import com.saga.be.dto.admin.dashboard.AdminDashboardIntegrationPulseCachedPayload;
import com.saga.be.dto.admin.dashboard.AdminDashboardIntegrationPulseResponse;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * Global integration-pulse cache. Independent of semester summary v3. Redis GET/lock/SET stay
 * outside JDBC; the grouped receipt query runs in a short read-only transaction.
 *
 * <p>The injected dashboard {@link Clock} is used only for {@code Instant cachedAt} metadata.
 * Rolling 24h/7d cutoffs live in {@link AdminDashboardPulseQueryService} and use
 * {@link AdminDashboardPersistenceClock}, not {@code saga.dashboard.zone}.
 */
@Service
@Profile("!test")
public class AdminDashboardPulseService implements AdminDashboardPulseLoader {

	private final AdminDashboardPulseQueryService queries;
	private final AdminDashboardPulseCacheStore cache;
	private final TransactionTemplate reads;
	private final Clock clock;
	private final AdminDashboardSleeper sleeper;
	private final Duration refreshWait;
	private final Duration refreshPoll;
	private final AdminDashboardService.OwnerTokenFactory tokens;

	@Autowired
	public AdminDashboardPulseService(
			AdminDashboardPulseQueryService queries,
			AdminDashboardPulseCacheStore cache,
			PlatformTransactionManager transactionManager,
			AdminDashboardProperties properties) {
		this(
				queries,
				cache,
				transactionManager,
				properties.clock(),
				duration -> Thread.sleep(duration.toMillis()),
				AdminDashboardService.DEFAULT_REFRESH_WAIT,
				AdminDashboardService.DEFAULT_REFRESH_POLL,
				() -> UUID.randomUUID().toString());
	}

	public AdminDashboardPulseService(
			AdminDashboardPulseQueryService queries,
			AdminDashboardPulseCacheStore cache,
			PlatformTransactionManager transactionManager,
			Clock clock,
			AdminDashboardSleeper sleeper,
			Duration refreshWait,
			Duration refreshPoll,
			AdminDashboardService.OwnerTokenFactory tokens) {
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

	@Override
	public List<AdminDashboardIntegrationPulseResponse> load(boolean forceRefresh) {
		if (!forceRefresh) {
			Optional<AdminDashboardIntegrationPulseCachedPayload> hit = cache.get();
			if (hit.isPresent()) {
				return hit.get().integrationPulse();
			}
		}
		return singleFlight();
	}

	private List<AdminDashboardIntegrationPulseResponse> singleFlight() {
		Optional<AdminDashboardIntegrationPulseCachedPayload> baseline = cache.get();
		String ownerToken = tokens.next();
		if (cache.tryLock(ownerToken)) {
			try {
				AdminDashboardIntegrationPulseCachedPayload computed = compute();
				if (cache.publishIfOwner(ownerToken, computed)) {
					return computed.integrationPulse();
				}
			} catch (RuntimeException ex) {
				cache.unlock(ownerToken);
				throw ex;
			}
			Optional<AdminDashboardIntegrationPulseCachedPayload> published = cache.get();
			if (published.isPresent() && generationChanged(baseline.orElse(null), published.get())) {
				return published.get().integrationPulse();
			}
		}
		AdminDashboardIntegrationPulseCachedPayload waited = waitForGenerationChange(baseline.orElse(null));
		if (waited != null) {
			return waited.integrationPulse();
		}
		if (baseline.isPresent()) {
			return baseline.get().integrationPulse();
		}
		throw new IntegrationException(
				IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
				HttpStatus.SERVICE_UNAVAILABLE,
				"Admin dashboard integration pulse is refreshing and no cached pulse is available.");
	}

	private AdminDashboardIntegrationPulseCachedPayload compute() {
		Instant cachedAt = clock.instant();
		List<AdminDashboardIntegrationPulseResponse> points = reads.execute(status -> queries.compute());
		return new AdminDashboardIntegrationPulseCachedPayload(UUID.randomUUID().toString(), cachedAt, points);
	}

	private AdminDashboardIntegrationPulseCachedPayload waitForGenerationChange(
			AdminDashboardIntegrationPulseCachedPayload baseline) {
		Duration waited = Duration.ZERO;
		while (waited.compareTo(refreshWait) < 0) {
			Optional<AdminDashboardIntegrationPulseCachedPayload> latest = cache.get();
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
						"Admin dashboard pulse refresh wait was interrupted.");
			}
			waited = waited.plus(refreshPoll);
		}
		Optional<AdminDashboardIntegrationPulseCachedPayload> last = cache.get();
		if (last.isPresent() && generationChanged(baseline, last.get())) {
			return last.get();
		}
		return null;
	}

	private static boolean generationChanged(
			AdminDashboardIntegrationPulseCachedPayload baseline,
			AdminDashboardIntegrationPulseCachedPayload latest) {
		if (baseline == null) {
			return true;
		}
		return !Objects.equals(baseline.generation(), latest.generation());
	}
}
