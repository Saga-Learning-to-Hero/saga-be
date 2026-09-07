package com.saga.be.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class RedisTimeoutPropertiesTest {

	@Test
	void applicationPropertiesFailFastInsteadOfLettuceSixtySecondDefaults() throws IOException {
		Properties properties = load("application.properties");
		assertThat(properties.getProperty("spring.data.redis.timeout")).isEqualTo("${SAGA_REDIS_TIMEOUT:3s}");
		assertThat(properties.getProperty("spring.data.redis.connect-timeout"))
				.isEqualTo("${SAGA_REDIS_CONNECT_TIMEOUT:3s}");
	}

	@Test
	void healthProbesSplitLivenessFromReadiness() throws IOException {
		Properties base = load("application.properties");
		assertThat(base.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
		assertThat(base.getProperty("management.endpoint.health.group.liveness.include")).isEqualTo("ping");
		assertThat(base.getProperty("management.health.neo4j.enabled")).isEqualTo("false");
		assertThat(base.getProperty("management.health.rabbit.enabled")).isEqualTo("false");
		assertThat(base.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info");
		Properties dev = load("application-dev.properties");
		assertThat(dev.getProperty("management.endpoint.health.group.readiness.include")).isEqualTo("db,redis");
		Properties local = load("application-local.properties");
		assertThat(local.getProperty("management.endpoint.health.group.readiness.include")).isEqualTo("db,redis");
	}

	private static Properties load(String classpathLocation) throws IOException {
		try (InputStream in = RedisTimeoutPropertiesTest.class.getClassLoader().getResourceAsStream(classpathLocation)) {
			assertThat(in).as(classpathLocation).isNotNull();
			Properties properties = new Properties();
			properties.load(in);
			return properties;
		}
	}
}
