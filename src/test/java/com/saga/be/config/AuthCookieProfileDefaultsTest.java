package com.saga.be.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class AuthCookieProfileDefaultsTest {

	@Test
	void localProfileKeepsSaferCookieDefaults() throws IOException {
		Properties properties = load("application-local.properties");
		assertThat(properties.getProperty("saga.auth.cookie.secure")).isEqualTo("false");
		assertThat(properties.getProperty("saga.auth.cookie.same-site")).isEqualTo("Lax");
	}

	@Test
	void devProfileDefaultsToSecureCrossSiteCookies() throws IOException {
		Properties properties = load("application-dev.properties");
		assertThat(properties.getProperty("saga.auth.cookie.secure")).isEqualTo("true");
		assertThat(properties.getProperty("saga.auth.cookie.same-site"))
				.isEqualTo("${SAGA_AUTH_COOKIE_SAME_SITE:None}");
	}

	private static Properties load(String classpathLocation) throws IOException {
		try (InputStream in = AuthCookieProfileDefaultsTest.class.getClassLoader().getResourceAsStream(classpathLocation)) {
			assertThat(in).as(classpathLocation).isNotNull();
			Properties properties = new Properties();
			properties.load(in);
			return properties;
		}
	}
}
