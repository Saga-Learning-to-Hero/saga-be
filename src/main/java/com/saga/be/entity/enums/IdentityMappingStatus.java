package com.saga.be.entity.enums;

public enum IdentityMappingStatus {
	PENDING,
	VERIFIED,
	DISCONNECTED,
	REJECTED,
	ACTIVE,
	REVOKED;

	public boolean isActiveLink() {
		return this == ACTIVE || this == VERIFIED || this == PENDING;
	}
}
