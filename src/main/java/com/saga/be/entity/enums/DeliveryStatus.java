package com.saga.be.entity.enums;

public enum DeliveryStatus {
	PENDING,
	SENT,
	FAILED,
	/** Future FCM worker: never send. Use when recipient_user_id != installation.owner_user_id or the installation is inactive. */
	SKIPPED
}
