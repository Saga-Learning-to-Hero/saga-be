package com.saga.be.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.saga.be.auth.LocalAuthService;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.SessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
		"saga.auth.cookie.same-site=None",
		"saga.auth.cookie.secure=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SagaSessionCookieWebTest.InMemorySpringSessionConfig.class)
class SagaSessionCookieWebTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private LocalAuthService localAuthService;

	@Autowired
	private ApplicationContext context;

	@Test
	void springSessionUsesSingleAuthPropertiesCookieSerializer() {
		assertThat(context.getBeansOfType(CookieSerializer.class)).hasSize(1);
		assertThat(context.getBeanNamesForType(CookieSerializer.class)).containsExactly("cookieSerializer");
		assertThat(context.getBean(CookieSerializer.class)).isInstanceOf(DefaultCookieSerializer.class);
	}

	@Test
	void loginEmitsSagaSessionWithCrossSiteAttributes() throws Exception {
		when(localAuthService.authenticate("admin", "admin123")).thenReturn(SagaAuthentications.authenticated(admin()));

		MvcResult csrf = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie xsrf = csrf.getResponse().getCookie("XSRF-TOKEN");
		assertThat(xsrf).isNotNull();
		String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");

		MvcResult login = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"identifier\":\"admin\",\"password\":\"admin123\"}")
						.cookie(xsrf)
						.header("X-XSRF-TOKEN", token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authenticated").value(true))
				.andReturn();

		String sagaSession = setCookie(login, "SAGA_SESSION");
		assertThat(sagaSession).contains("SAGA_SESSION=");
		assertThat(sagaSession).contains("Path=/");
		assertThat(sagaSession).contains("HttpOnly");
		assertThat(sagaSession).contains("Secure");
		assertThat(sagaSession).contains("SameSite=None");
		assertThat(sagaSession).doesNotContain("SameSite=Lax");
	}

	private static String setCookie(MvcResult result, String cookieName) {
		List<String> headers = result.getResponse().getHeaders("Set-Cookie");
		return headers.stream()
				.filter(header -> header.startsWith(cookieName + "="))
				.findFirst()
				.orElseGet(() -> {
					Cookie cookie = result.getResponse().getCookie(cookieName);
					assertThat(cookie).as("Set-Cookie " + cookieName).isNotNull();
					return cookieName + "=" + cookie.getValue()
							+ "; Path=" + cookie.getPath()
							+ (cookie.getSecure() ? "; Secure" : "")
							+ (cookie.isHttpOnly() ? "; HttpOnly" : "")
							+ (cookie.getAttribute("SameSite") != null
									? "; SameSite=" + cookie.getAttribute("SameSite")
									: "");
				});
	}

	private static UserAccount admin() {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail("admin@saga.local");
		account.setUsername("admin");
		account.setFullName("System Admin");
		account.setAccountRole(AccountRole.ADMIN);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setPasswordHash("not-plaintext");
		return account;
	}

	@TestConfiguration
	@EnableSpringHttpSession
	static class InMemorySpringSessionConfig {

		@Bean
		SessionRepository<MapSession> sessionRepository() {
			return new MapSessionRepository(new ConcurrentHashMap<>());
		}
	}
}
