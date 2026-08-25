package com.saga.be.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeveloperApiDocsTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void rootLandingPageIsPublic() throws Exception {
		mockMvc.perform(get("/"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("SAGA Backend")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("/swagger-ui.html")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Check health")))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("OpenAPI JSON"))))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Auth endpoints"))));
	}

	@Test
	void swaggerUiIsAccessibleAnonymously() throws Exception {
		mockMvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

	@Test
	void openApiJsonIsAccessibleAnonymouslyAndDocumentsAuth() throws Exception {
		MvcResult result = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andReturn();
		String spec = result.getResponse().getContentAsString();
		assertThat(spec).contains("/api/auth/csrf");
		assertThat(spec).contains("/api/auth/login");
		assertThat(spec).contains("/api/auth/me");
		assertThat(spec).contains("/api/auth/logout");
		assertThat(spec).contains("/api/auth/password/setup");
		assertThat(spec).contains("/api/auth/register");
		assertThat(spec).contains("/oauth2/authorization/google");
		assertThat(spec).contains("SAGA Backend API");
		assertThat(spec).contains("SAGA_SESSION");
		assertThat(spec).doesNotContain("\"scheme\":\"bearer\"");
		assertThat(spec).doesNotContain("/api/student/anything");
		assertThat(spec).doesNotContain("/api/admin/anything");
		boolean csrfHasRequestParams = true;
		try {
			Object params = JsonPath.read(spec, "$.paths['/api/auth/csrf'].get.parameters");
			csrfHasRequestParams = params instanceof java.util.List<?> list && !list.isEmpty();
		} catch (PathNotFoundException ignored) {
			csrfHasRequestParams = false;
		}
		assertThat(csrfHasRequestParams)
				.as("GET /api/auth/csrf must not document injected CsrfToken as a request parameter")
				.isFalse();
		assertThat((Object) JsonPath.read(spec, "$.components.schemas.CsrfTokenResponse.properties.token"))
				.isNotNull();
		assertThat((Object) JsonPath.read(spec, "$.components.schemas.CsrfTokenResponse.properties.headerName"))
				.isNotNull();
		assertThat((Object) JsonPath.read(spec, "$.components.schemas.CsrfTokenResponse.properties.parameterName"))
				.isNotNull();
		assertThat((Object) JsonPath.read(spec, "$.paths['/api/auth/csrf'].get.responses['200']")).isNotNull();
	}

	@Test
	void documentationRoutesDoNotOpenBusinessApis() throws Exception {
		mockMvc.perform(get("/api/student/anything")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/admin/anything")).andExpect(status().isUnauthorized());
	}
}
