package com.saga.be.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.session.autoconfigure.SessionAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

class SessionCookieConfigTest {

	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(SessionAutoConfiguration.class))
			.withUserConfiguration(SessionCookieConfig.class, AuthPropertiesConfig.class)
			.withPropertyValues(
					"saga.auth.cookie.same-site=None",
					"saga.auth.cookie.secure=true",
					"server.servlet.session.cookie.name=SAGA_SESSION",
					"server.servlet.session.cookie.http-only=true");

	@Test
	void cookieSerializerUsesAuthPropertiesEvenWithoutRedisConnectionFactory() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(CookieSerializer.class);
			assertThat(context).hasSingleBean(DefaultCookieSerializer.class);
			assertThat(context.getBeanNamesForType(CookieSerializer.class)).containsExactly("cookieSerializer");
			assertThat(context.getBean(CookieSerializer.class).getClass()).isEqualTo(DefaultCookieSerializer.class);
			assertThat(context.getBean("cookieSerializer")).isSameAs(context.getBean(CookieSerializer.class));

			String setCookie = writeSessionCookie(context.getBean(CookieSerializer.class));
			assertThat(setCookie).contains("SAGA_SESSION=");
			assertThat(setCookie).contains("Path=/");
			assertThat(setCookie).contains("HttpOnly");
			assertThat(setCookie).contains("Secure");
			assertThat(setCookie).contains("SameSite=None");
			assertThat(setCookie).doesNotContain("SameSite=Lax");
		});
	}

	private static String writeSessionCookie(CookieSerializer serializer) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		serializer.writeCookieValue(new CookieSerializer.CookieValue(request, response, "session-id-for-test"));
		String header = response.getHeader("Set-Cookie");
		assertThat(header).isNotBlank();
		return header;
	}

	@Configuration
	@EnableConfigurationProperties(AuthProperties.class)
	static class AuthPropertiesConfig {
	}
}
