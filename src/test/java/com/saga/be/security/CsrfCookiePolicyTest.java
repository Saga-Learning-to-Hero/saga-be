package com.saga.be.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
		"saga.auth.cookie.same-site=None",
		"saga.auth.cookie.secure=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CsrfCookiePolicyTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void csrfCookieFollowsAuthPropertiesCrossSitePolicy() throws Exception {
		MvcResult csrf = mockMvc.perform(get("/api/auth/csrf"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
				.andReturn();

		Cookie cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
		assertThat(cookie).isNotNull();
		assertThat(cookie.isHttpOnly()).isFalse();
		assertThat(cookie.getSecure()).isTrue();
		assertThat(cookie.getAttribute("SameSite")).isEqualTo("None");

		String bodyToken = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
		assertThat(bodyToken).isEqualTo(cookie.getValue());
	}
}
