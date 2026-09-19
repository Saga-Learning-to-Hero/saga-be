package com.saga.be.service.admin.dashboard;

import com.saga.be.dto.admin.dashboard.AdminDashboardIntegrationPulseCachedPayload;
import java.util.Optional;

public interface AdminDashboardPulseCacheStore {

	Optional<AdminDashboardIntegrationPulseCachedPayload> get();

	/**
	 * Atomically publish {@code payload} only while {@code ownerToken} still owns the lock.
	 * On success the cache is SET with TTL and the lock is deleted. On mismatch the cache and
	 * the current lock are left untouched.
	 */
	boolean publishIfOwner(String ownerToken, AdminDashboardIntegrationPulseCachedPayload payload);

	Optional<Long> ttlSeconds();

	boolean tryLock(String ownerToken);

	/** Compare-and-delete: only the owner token may release the lock. */
	boolean unlock(String ownerToken);
}
