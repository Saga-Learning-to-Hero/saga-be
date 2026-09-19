package com.saga.be.service.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.saga.be.dto.admin.dashboard.AdminDashboardIntegrationPulseCachedPayload;
import com.saga.be.dto.admin.dashboard.AdminDashboardIntegrationPulseResponse;
import com.saga.be.entity.enums.IntegrationProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class AdminDashboardPulseRedisCacheStoreTest {

	@Mock
	private StringRedisTemplate redis;
	@Mock
	private ValueOperations<String, String> values;

	private ObjectMapper mapper;
	private AdminDashboardPulseRedisCacheStore store;

	@BeforeEach
	void setUp() {
		mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule());
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		store = new AdminDashboardPulseRedisCacheStore(redis, mapper);
	}

	@Test
	void publishIfOwner_matchingTokenSetsPayloadWith60sTtlAndSharedLua() throws Exception {
		when(redis.execute(any(RedisScript.class), anyList(), eq("owner-a"), any(), eq("60"))).thenReturn(1L);
		AdminDashboardIntegrationPulseCachedPayload payload = payload("gen-1");
		assertThat(store.publishIfOwner("owner-a", payload)).isTrue();
		@SuppressWarnings("unchecked")
		ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
		ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
		verify(redis)
				.execute(
						script.capture(),
						eq(List.of(
								"saga:admin:dashboard:integration-pulse:v1:lock",
								"saga:admin:dashboard:integration-pulse:v1")),
						eq("owner-a"),
						json.capture(),
						eq("60"));
		assertThat(script.getValue()).isSameAs(AdminDashboardRedisScripts.PUBLISH_IF_OWNER);
		assertThat(script.getValue().getScriptAsString()).isEqualTo(
				AdminDashboardRedisScripts.PUBLISH_IF_OWNER.getScriptAsString());
		assertThat(json.getValue()).contains("\"generation\":\"gen-1\"");
		assertThat(json.getValue()).contains("uniqueEventsReceived24h");
		assertThat(json.getValue()).doesNotContain("ttlSecondsRemaining");
		AdminDashboardIntegrationPulseCachedPayload read =
				mapper.readValue(json.getValue(), AdminDashboardIntegrationPulseCachedPayload.class);
		assertThat(read.generation()).isEqualTo("gen-1");
	}

	@Test
	void publishIfOwner_mismatchedTokenLeavesCacheUntouched() {
		when(redis.execute(any(RedisScript.class), anyList(), eq("old-owner"), any(), eq("60"))).thenReturn(0L);
		assertThat(store.publishIfOwner("old-owner", payload("gen-a-late"))).isFalse();
	}

	@Test
	void tryLockUsesSetNxOwnerTokenAnd45sTtl() {
		when(redis.opsForValue()).thenReturn(values);
		when(values.setIfAbsent(
						eq("saga:admin:dashboard:integration-pulse:v1:lock"),
						eq("owner-a"),
						eq(Duration.ofSeconds(45))))
				.thenReturn(true);
		assertThat(store.tryLock("owner-a")).isTrue();
	}

	@Test
	void unlockUsesSharedCompareAndDelete() {
		when(redis.execute(any(RedisScript.class), anyList(), eq("owner-a"))).thenReturn(1L);
		assertThat(store.unlock("owner-a")).isTrue();
		@SuppressWarnings("unchecked")
		ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
		verify(redis)
				.execute(
						script.capture(),
						eq(List.of("saga:admin:dashboard:integration-pulse:v1:lock")),
						eq("owner-a"));
		assertThat(script.getValue()).isSameAs(AdminDashboardRedisScripts.COMPARE_AND_DELETE);
	}

	@Test
	void summaryAndPulseStoresShareIdenticalLuaText() {
		assertThat(AdminDashboardRedisScripts.PUBLISH_IF_OWNER.getScriptAsString())
				.contains("SET")
				.contains("'EX'")
				.contains("DEL")
				.contains("ARGV[1]");
		assertThat(AdminDashboardRedisScripts.COMPARE_AND_DELETE.getScriptAsString())
				.contains("GET")
				.contains("DEL")
				.contains("ARGV[1]");
	}

	@Test
	void redisOutagePropagatesConnectionFailure() {
		when(redis.opsForValue()).thenReturn(values);
		when(values.get(any())).thenThrow(new RedisConnectionFailureException("redis down"));
		assertThatThrownBy(store::get).isInstanceOf(RedisConnectionFailureException.class);
	}

	@Test
	void cacheKeyIsGlobalNotSemesterScoped() {
		assertThat(AdminDashboardPulseRedisCacheStore.CACHE_KEY)
				.isEqualTo("saga:admin:dashboard:integration-pulse:v1");
		assertThat(AdminDashboardPulseRedisCacheStore.CACHE_KEY).doesNotContain("{");
		assertThat(AdminDashboardPulseRedisCacheStore.CACHE_TTL).isEqualTo(Duration.ofSeconds(60));
		assertThat(AdminDashboardRedisCacheStore.CACHE_TTL).isEqualTo(Duration.ofSeconds(600));
		assertThat(AdminDashboardRedisCacheStore.KEY_PREFIX).isEqualTo("saga:admin:dashboard:summary:v3:");
	}

	private static AdminDashboardIntegrationPulseCachedPayload payload(String generation) {
		return new AdminDashboardIntegrationPulseCachedPayload(
				generation,
				Instant.parse("2026-09-19T04:00:00Z"),
				List.of(
						new AdminDashboardIntegrationPulseResponse(IntegrationProvider.GITHUB, 1L, 2L, null),
						new AdminDashboardIntegrationPulseResponse(IntegrationProvider.JIRA, 0L, 0L, null)));
	}
}
