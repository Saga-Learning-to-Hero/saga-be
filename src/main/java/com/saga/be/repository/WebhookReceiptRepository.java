package com.saga.be.repository;

import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.integration.WebhookReceipt;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookReceiptRepository extends JpaRepository<WebhookReceipt, UUID> {

	Optional<WebhookReceipt> findByProviderAndDeliveryId(IntegrationProvider provider, String deliveryId);

	/**
	 * Platform-wide unique-delivery aggregates. One grouped statement, no date {@code WHERE}, so
	 * {@code max(createdAt)} is the latest first-seen delivery overall — not a 7-day-capped max.
	 *
	 * <p>24h / 7d windows are inclusive on the cutoff: {@code createdAt >= :cutoff}. Counts are
	 * row counts (unique {@code (provider, deliveryId)}), not HTTP arrivals.
	 *
	 * <p>Existing {@code ix_webhook_status(receipt_status, created_at)} does not optimally serve
	 * this {@code provider + createdAt} grouping. No V24 is added here.
	 */
	@Query(
			"""
			select r.provider,
			       coalesce(sum(case when r.createdAt >= :cutoff24h then 1 else 0 end), 0),
			       coalesce(sum(case when r.createdAt >= :cutoff7d then 1 else 0 end), 0),
			       max(r.createdAt)
			from WebhookReceipt r
			group by r.provider
			""")
	List<Object[]> aggregateUniqueDeliveries(
			@Param("cutoff24h") LocalDateTime cutoff24h, @Param("cutoff7d") LocalDateTime cutoff7d);
}
