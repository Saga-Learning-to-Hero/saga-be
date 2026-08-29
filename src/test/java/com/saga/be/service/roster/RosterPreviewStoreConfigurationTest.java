package com.saga.be.service.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.RosterConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RosterPreviewStoreConfigurationTest {

	private ApplicationContextRunner runner(String profile) {
		return new ApplicationContextRunner()
				.withInitializer(context -> context.getEnvironment().addActiveProfile(profile))
				.withUserConfiguration(RosterConfiguration.class, RedisStubs.class);
	}

	@Test
	void localProfileExposesRedisRosterPreviewStore() {
		runner("local").run(context -> {
			assertThat(context).hasSingleBean(RosterPreviewStore.class);
			assertThat(context.getBean(RosterPreviewStore.class)).isInstanceOf(RedisRosterPreviewStore.class);
		});
	}

	@Test
	void devProfileExposesRedisRosterPreviewStore() {
		runner("dev").run(context -> {
			assertThat(context).hasSingleBean(RosterPreviewStore.class);
			assertThat(context.getBean(RosterPreviewStore.class)).isInstanceOf(RedisRosterPreviewStore.class);
		});
	}

	@Test
	void testProfileDoesNotRegisterRuntimePreviewStore() {
		runner("test").run(context -> assertThat(context).doesNotHaveBean(RosterPreviewStore.class));
	}

	@Test
	void saveUsesRosterPreviewKeyPrefixAndConfiguredTtl() {
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> values = mock(ValueOperations.class);
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.opsForValue()).thenReturn(values);
		RedisRosterPreviewStore store = new RedisRosterPreviewStore(redis, new ObjectMapper());
		RosterPreviewSnapshot snapshot = new RosterPreviewSnapshot(
				UUID.randomUUID(), UUID.randomUUID(), "SE1705", List.of());
		store.save("preview-token", snapshot, Duration.ofMinutes(15));
		verify(values).set(eq("saga:roster:preview:preview-token"), org.mockito.ArgumentMatchers.anyString(), eq(Duration.ofMinutes(15)));
	}

	@Configuration
	static class RedisStubs {

		@Bean
		StringRedisTemplate stringRedisTemplate() {
			return mock(StringRedisTemplate.class);
		}

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}
	}
}
