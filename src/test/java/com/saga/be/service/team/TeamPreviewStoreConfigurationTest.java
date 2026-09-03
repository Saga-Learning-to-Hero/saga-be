package com.saga.be.service.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.TeamConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class TeamPreviewStoreConfigurationTest {

	private ApplicationContextRunner runner(String profile) {
		return new ApplicationContextRunner()
				.withInitializer(context -> context.getEnvironment().addActiveProfile(profile))
				.withUserConfiguration(TeamConfiguration.class, RedisStubs.class);
	}

	@Test
	void localProfileExposesRedisTeamPreviewStore() {
		runner("local").run(context -> {
			assertThat(context).hasSingleBean(TeamPreviewStore.class);
			assertThat(context.getBean(TeamPreviewStore.class)).isInstanceOf(RedisTeamPreviewStore.class);
		});
	}

	@Test
	void testProfileDoesNotRegisterRuntimePreviewStore() {
		runner("test").run(context -> assertThat(context).doesNotHaveBean(TeamPreviewStore.class));
	}

	@Test
	void saveUsesTeamPreviewKeyPrefixAndConfiguredTtl() {
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> values = mock(ValueOperations.class);
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.opsForValue()).thenReturn(values);
		RedisTeamPreviewStore store = new RedisTeamPreviewStore(redis, new ObjectMapper());
		TeamPreviewSnapshot snapshot =
				new TeamPreviewSnapshot(UUID.randomUUID(), UUID.randomUUID(), "SE1705", List.of(), List.of());
		store.save("preview-token", snapshot, Duration.ofMinutes(15));
		verify(values).set(eq("saga:team:preview:preview-token"), org.mockito.ArgumentMatchers.anyString(), eq(Duration.ofMinutes(15)));
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
