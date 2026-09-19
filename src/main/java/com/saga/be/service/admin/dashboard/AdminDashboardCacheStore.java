package com.saga.be.service.admin.dashboard;

import com.saga.be.dto.admin.dashboard.AdminDashboardCachedPayload;
import java.util.Optional;
import java.util.UUID;

public interface AdminDashboardCacheStore {

	Optional<AdminDashboardCachedPayload> get(UUID semesterId);

	/**
	 * Atomically publish {@code payload} only while {@code ownerToken} still owns the lock.
	 * On success the cache is SET with TTL and the lock is deleted. On mismatch the cache and
	 * the current lock are left untouched.
	 */
	boolean publishIfOwner(UUID semesterId, String ownerToken, AdminDashboardCachedPayload payload);

	/**
	 * Redis TTL in seconds for the cache key. Empty when the key is missing or Redis reports an
	 * unavailable / negative expire (no TTL, already gone).
	 */
	Optional<Long> ttlSeconds(UUID semesterId);

	boolean tryLock(UUID semesterId, String ownerToken);

	/** Compare-and-delete: only the owner token may release the lock. */
	boolean unlock(UUID semesterId, String ownerToken);
}
