package com.saga.be.service.admin.dashboard;

import com.saga.be.dto.admin.dashboard.AdminDashboardIntegrationPulseResponse;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.repository.WebhookReceiptRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Platform-wide unique webhook delivery pulse. Reads {@code webhook_receipt} only — no Course,
 * Semester, Project, or Integration join, and no provider HTTP.
 *
 * <p>Rolling windows use the <em>persistence</em> wall-clock
 * ({@link AdminDashboardPersistenceClock}), not {@code saga.dashboard.zone}. Stored
 * {@code createdAt} is a Hibernate naive {@code LocalDateTime} written from JVM
 * {@code LocalDateTime.now()}; cutoffs are {@code LocalDateTime.now(persistenceClock)} minus
 * 24 hours / 7 days, compared directly. Inclusive: {@code createdAt >= cutoff}.
 */
@Service
@Profile("!test")
public class AdminDashboardPulseQueryService {

	private final WebhookReceiptRepository receipts;
	private final Clock persistenceClock;

	@Autowired
	public AdminDashboardPulseQueryService(WebhookReceiptRepository receipts) {
		this(receipts, AdminDashboardPersistenceClock.wallClock());
	}

	public AdminDashboardPulseQueryService(WebhookReceiptRepository receipts, Clock persistenceClock) {
		this.receipts = receipts;
		this.persistenceClock = persistenceClock;
	}

	public List<AdminDashboardIntegrationPulseResponse> compute() {
		LocalDateTime now = LocalDateTime.now(persistenceClock);
		LocalDateTime cutoff24h = now.minusHours(24);
		LocalDateTime cutoff7d = now.minusDays(7);
		Map<IntegrationProvider, AdminDashboardIntegrationPulseResponse> byProvider =
				new EnumMap<>(IntegrationProvider.class);
		for (Object[] row : receipts.aggregateUniqueDeliveries(cutoff24h, cutoff7d)) {
			IntegrationProvider provider = (IntegrationProvider) row[0];
			if (provider != IntegrationProvider.GITHUB && provider != IntegrationProvider.JIRA) {
				continue;
			}
			byProvider.put(
					provider,
					new AdminDashboardIntegrationPulseResponse(
							provider, longValue(row[1]), longValue(row[2]), (LocalDateTime) row[3]));
		}
		return List.of(
				byProvider.getOrDefault(IntegrationProvider.GITHUB, empty(IntegrationProvider.GITHUB)),
				byProvider.getOrDefault(IntegrationProvider.JIRA, empty(IntegrationProvider.JIRA)));
	}

	public static List<AdminDashboardIntegrationPulseResponse> emptyRows() {
		return List.of(empty(IntegrationProvider.GITHUB), empty(IntegrationProvider.JIRA));
	}

	private static AdminDashboardIntegrationPulseResponse empty(IntegrationProvider provider) {
		return new AdminDashboardIntegrationPulseResponse(provider, 0L, 0L, null);
	}

	private static long longValue(Object value) {
		if (value == null) {
			return 0L;
		}
		return ((Number) value).longValue();
	}
}
