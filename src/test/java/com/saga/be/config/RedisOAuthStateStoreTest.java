package com.saga.be.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.saga.be.entity.enums.OAuthFlowType;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.oauth.OAuthState;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisOAuthStateStoreTest {

	@Mock
	private StringRedisTemplate redis;
	@Mock
	private ValueOperations<String, String> values;

	private ObjectMapper mapper;
	private RedisOAuthStateStore store;

	@BeforeEach
	void setUp() {
		mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule());
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		store = new RedisOAuthStateStore(redis, mapper, Duration.ofMinutes(10));
	}

	@Test
	void nullReturnPathSerializesWithoutThrowing() throws Exception {
		when(redis.opsForValue()).thenReturn(values);
		OAuthState state = new OAuthState(
				"state-token",
				UUID.randomUUID(),
				OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY,
				null,
				UUID.randomUUID(),
				UUID.randomUUID(),
				"verifier",
				Instant.now());
		assertDoesNotThrow(() -> store.save(state, Duration.ofMinutes(10)));
		ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
		verify(values).set(eq("saga:oauth:state:state-token"), json.capture(), eq(Duration.ofMinutes(10)));
		assertTrue(json.getValue().contains("\"frontendReturnPath\":null"));
		OAuthState read = mapper.readValue(json.getValue(), OAuthState.class);
		assertEquals(state.state(), read.state());
		assertEquals(null, read.frontendReturnPath());
	}

	@Test
	void redisOutageIsNotConvertedToIllegalState() {
		when(redis.opsForValue()).thenReturn(values);
		doThrow(new RedisConnectionFailureException("redis down"))
				.when(values)
				.set(any(), any(), any(Duration.class));
		OAuthState state = new OAuthState(
				"state-token",
				UUID.randomUUID(),
				OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY,
				null,
				UUID.randomUUID(),
				UUID.randomUUID(),
				"verifier",
				Instant.now());
		assertThrows(RedisConnectionFailureException.class, () -> store.save(state, Duration.ofMinutes(10)));
	}

	@Test
	void mapperFailureBecomesIntegrationUnavailable() {
		when(redis.opsForValue()).thenReturn(values);
		RedisOAuthStateStore broken = new RedisOAuthStateStore(redis, new ObjectMapper(), Duration.ofMinutes(10));
		OAuthState state = new OAuthState(
				"state-token",
				UUID.randomUUID(),
				OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY,
				null,
				UUID.randomUUID(),
				UUID.randomUUID(),
				"verifier",
				Instant.now());
		IntegrationException ex = assertThrows(IntegrationException.class, () -> broken.save(state, Duration.ofMinutes(10)));
		assertEquals(IntegrationErrorCode.INTEGRATION_UNAVAILABLE, ex.getCode());
	}
}
