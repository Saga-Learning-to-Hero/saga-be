package com.saga.be.integration.webhook;

import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.enums.WebhookReceiptStatus;
import com.saga.be.entity.integration.WebhookReceipt;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public class WebhookReceiptService {

	public record IngestResult(WebhookReceipt receipt, boolean duplicate) {}

	public interface Store {
		Optional<WebhookReceipt> find(IntegrationProvider provider, String deliveryId);

		WebhookReceipt save(WebhookReceipt receipt);
	}

	private final Store store;

	public WebhookReceiptService(Store store) {
		this.store = store;
	}

	public IngestResult ingest(
			IntegrationProvider provider,
			String deliveryId,
			String eventType,
			String action,
			String payloadJson,
			UUID targetId,
			LocalDateTime now) {
		Optional<WebhookReceipt> existing = store.find(provider, deliveryId);
		if (existing.isPresent()) {
			WebhookReceipt duplicate = existing.get();
			if (duplicate.getReceiptStatus() != WebhookReceiptStatus.DUPLICATE) {
				duplicate.setReceiptStatus(WebhookReceiptStatus.DUPLICATE);
				store.save(duplicate);
			}
			return new IngestResult(duplicate, true);
		}
		WebhookReceipt receipt = new WebhookReceipt();
		receipt.setProvider(provider);
		receipt.setDeliveryId(deliveryId);
		receipt.setEventType(eventType);
		receipt.setEventAction(action);
		receipt.setPayloadJson(payloadJson);
		receipt.setTargetId(targetId);
		receipt.setReceiptStatus(WebhookReceiptStatus.RECEIVED);
		receipt.setAttemptCount(1);
		receipt.setVersion(0L);
		receipt.setProcessedAt(null);
		return new IngestResult(store.save(receipt), false);
	}

	public void markProcessed(WebhookReceipt receipt, LocalDateTime now) {
		receipt.setReceiptStatus(WebhookReceiptStatus.PROCESSED);
		receipt.setProcessedAt(now);
		store.save(receipt);
	}

	public void markFailed(WebhookReceipt receipt, String errorCategory) {
		receipt.setReceiptStatus(WebhookReceiptStatus.FAILED);
		receipt.setErrorCategory(errorCategory);
		store.save(receipt);
	}
}
