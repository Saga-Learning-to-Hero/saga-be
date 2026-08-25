package com.saga.be.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.saga.be.dto.auth.RegisterRequest;
import com.saga.be.dto.auth.RegisterResponse;
import com.saga.be.dto.auth.RegisterResponse.RegisteredUserDto;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
class AuthRegisterCsrfTest {

	private static final String BODY =
			"""
			{"email":"student.personal@example.com","fullName":"Example Student","studentCode":"SE123456","password":"example-password","confirmPassword":"example-password","role":"ADMIN","accountRole":"LECTURER"}
			""";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private StudentRegistrationService studentRegistrationService;

	@BeforeEach
	void resetService() {
		reset(studentRegistrationService);
	}

	@Test
	void registerWithoutCsrfIsDenied() throws Exception {
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		verifyNoInteractions(studentRegistrationService);
	}

	@Test
	void registerWithCsrfReachesServiceAsStudentOnlyContract() throws Exception {
		when(studentRegistrationService.register(any()))
				.thenReturn(new RegisterResponse(
						true,
						new RegisteredUserDto(
								UUID.randomUUID(), "student.personal@example.com", "Example Student", "STUDENT")));
		MvcResult csrf = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
		assertThat(cookie).isNotNull();
		String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");

		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(BODY)
						.cookie(cookie)
						.header("X-XSRF-TOKEN", token))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.registered").value(true))
				.andExpect(jsonPath("$.user.role").value("STUDENT"));

		ArgumentCaptor<RegisterRequest> captor = ArgumentCaptor.forClass(RegisterRequest.class);
		verify(studentRegistrationService).register(captor.capture());
		assertThat(captor.getValue().email()).isEqualTo("student.personal@example.com");
	}
}
