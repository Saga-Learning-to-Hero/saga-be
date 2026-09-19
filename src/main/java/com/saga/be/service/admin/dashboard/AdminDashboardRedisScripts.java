package com.saga.be.service.admin.dashboard;

import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Shared Lua for dashboard cache fencing. Summary v3 and integration-pulse v1 must use the same
 * scripts so lock ownership, publish-if-owner, and compare-delete cannot drift.
 */
final class AdminDashboardRedisScripts {

	static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>();
	static final DefaultRedisScript<Long> PUBLISH_IF_OWNER = new DefaultRedisScript<>();

	static {
		COMPARE_AND_DELETE.setResultType(Long.class);
		COMPARE_AND_DELETE.setScriptText(
				"""
				if redis.call('GET', KEYS[1]) == ARGV[1] then
				  return redis.call('DEL', KEYS[1])
				else
				  return 0
				end
				""");
		PUBLISH_IF_OWNER.setResultType(Long.class);
		PUBLISH_IF_OWNER.setScriptText(
				"""
				if redis.call('GET', KEYS[1]) == ARGV[1] then
				  redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
				  redis.call('DEL', KEYS[1])
				  return 1
				else
				  return 0
				end
				""");
	}

	private AdminDashboardRedisScripts() {}
}
