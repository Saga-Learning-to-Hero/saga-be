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
import com.saga.be.dto.admin.dashboard.AdminDashboardCachedPayload;
import com.saga.be.dto.admin.dashboard.AdminDashboardKpisResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardSelectedSemesterResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
class AdminDashboardRedisCacheStoreTest {

	@Mock
	private StringRedisTemplate redis;
	@Mock
	private ValueOperations<String, String> values;

	private ObjectMapper mapper;
	private AdminDashboardRedisCacheStore store;
	private UUID semesterId;

	@BeforeEach
	void setUp() {
		mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule());
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		store = new AdminDashboardRedisCacheStore(redis, mapper);
		semesterId = UUID.fromString("11111111-1111-4111-8111-111111111111");
	}

	@Test
	void publishIfOwner_matchingTokenSetsPayloadTtlAndDeletesLock() throws Exception {
		when(redis.execute(any(RedisScript.class), anyList(), eq("owner-a"), any(), eq("600"))).thenReturn(1L);
		AdminDashboardCachedPayload payload = payload("gen-1");
		assertThat(store.publishIfOwner(semesterId, "owner-a", payload)).isTrue();
		@SuppressWarnings("unchecked")
		ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
		ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
		verify(redis)
				.execute(
						script.capture(),
						eq(List.of(
								"saga:admin:dashboard:summary:v1:" + semesterId + ":lock",
								"saga:admin:dashboard:summary:v1:" + semesterId)),
						eq("owner-a"),
						json.capture(),
						eq("600"));
		assertThat(script.getValue().getScriptAsString()).contains("SET");
		assertThat(script.getValue().getScriptAsString()).contains("'EX'");
		assertThat(script.getValue().getScriptAsString()).contains("DEL");
		assertThat(script.getValue().getScriptAsString()).contains("ARGV[1]");
		assertThat(json.getValue()).contains("\"generation\":\"gen-1\"");
		assertThat(json.getValue()).doesNotContain("ttlSecondsRemaining");
		assertThat(json.getValue()).doesNotContain("refreshPending");
		AdminDashboardCachedPayload read = mapper.readValue(json.getValue(), AdminDashboardCachedPayload.class);
		assertThat(read.generation()).isEqualTo("gen-1");
	}

	@Test
	void publishIfOwner_mismatchedTokenLeavesCacheAndNewerLockUntouched() {
		when(redis.execute(any(RedisScript.class), anyList(), eq("old-owner"), any(), eq("600"))).thenReturn(0L);
		assertThat(store.publishIfOwner(semesterId, "old-owner", payload("gen-a-late"))).isFalse();
		verify(redis)
				.execute(
						any(RedisScript.class),
						eq(List.of(
								"saga:admin:dashboard:summary:v1:" + semesterId + ":lock",
								"saga:admin:dashboard:summary:v1:" + semesterId)),
						eq("old-owner"),
						any(),
						eq("600"));
	}

	@Test
	void tryLockUsesSetNxOwnerTokenAnd45sTtl() {
		when(redis.opsForValue()).thenReturn(values);
		when(values.setIfAbsent(
						eq("saga:admin:dashboard:summary:v1:" + semesterId + ":lock"),
						eq("owner-a"),
						eq(Duration.ofSeconds(45))))
				.thenReturn(true);
		assertThat(store.tryLock(semesterId, "owner-a")).isTrue();
	}

	@Test
	void unlockUsesCompareAndDelete_ownerTokenOnly() {
		when(redis.execute(any(RedisScript.class), anyList(), eq("owner-a"))).thenReturn(1L);
		assertThat(store.unlock(semesterId, "owner-a")).isTrue();
		@SuppressWarnings("unchecked")
		ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
		verify(redis)
				.execute(
						script.capture(),
						eq(List.of("saga:admin:dashboard:summary:v1:" + semesterId + ":lock")),
						eq("owner-a"));
		assertThat(script.getValue().getScriptAsString()).contains("GET");
		assertThat(script.getValue().getScriptAsString()).contains("DEL");
		assertThat(script.getValue().getScriptAsString()).contains("ARGV[1]");
	}

	@Test
	void expiredOwnerCannotDeleteNewerLock() {
		when(redis.execute(any(RedisScript.class), anyList(), eq("old-owner"))).thenReturn(0L);
		assertThat(store.unlock(semesterId, "old-owner")).isFalse();
	}

	@Test
	void negativeExpireIsNotExposed() {
		when(redis.getExpire(eq("saga:admin:dashboard:summary:v1:" + semesterId), eq(TimeUnit.SECONDS)))
				.thenReturn(-1L);
		assertThat(store.ttlSeconds(semesterId)).isEmpty();
		when(redis.getExpire(eq("saga:admin:dashboard:summary:v1:" + semesterId), eq(TimeUnit.SECONDS)))
				.thenReturn(-2L);
		assertThat(store.ttlSeconds(semesterId)).isEmpty();
	}

	@Test
	void redisOutagePropagatesConnectionFailure() {
		when(redis.opsForValue()).thenReturn(values);
		when(values.get(any())).thenThrow(new RedisConnectionFailureException("redis down"));
		assertThatThrownBy(() -> store.get(semesterId)).isInstanceOf(RedisConnectionFailureException.class);
	}

	private static AdminDashboardCachedPayload payload(String generation) {
		return new AdminDashboardCachedPayload(
				generation,
				Instant.parse("2026-09-19T04:00:00Z"),
				new AdminDashboardSelectedSemesterResponse(
						UUID.fromString("11111111-1111-4111-8111-111111111111"),
						"FA26",
						"Fall",
						LocalDate.of(2026, 9, 1),
						LocalDate.of(2026, 12, 15),
						16,
						3,
						true),
				List.of(),
				new AdminDashboardKpisResponse(0, null, null, 0, 0, 0, null, 0, 0, null));
	}
}
