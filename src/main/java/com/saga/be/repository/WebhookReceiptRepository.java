package com.saga.be.repository;

import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.integration.WebhookReceipt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookReceiptRepository extends JpaRepository<WebhookReceipt, UUID> {

	Optional<WebhookReceipt> findByProviderAndDeliveryId(IntegrationProvider provider, String deliveryId);
}
