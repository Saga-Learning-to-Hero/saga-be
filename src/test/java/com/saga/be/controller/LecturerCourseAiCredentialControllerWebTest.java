package com.saga.be.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.saga.be.dto.ai.CourseAiCredentialResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.enums.*;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaAuthentications;
import com.saga.be.service.ai.CourseAiCredentialService;
import com.saga.be.service.ai.CourseAiSettingsService;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP-boundary authorization test for the course BYOK API.  The production controller is
 * intentionally excluded by its {@code !test} profile, so this test registers that exact
 * controller with mocked collaborators and still exercises the real MVC routing, security and
 * CSRF filters.  It is deliberately not a direct controller-method unit test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LecturerCourseAiCredentialControllerWebTest.Configuration.class)
class LecturerCourseAiCredentialControllerWebTest {

	private static final String SECRET = "sk-course-secret-never-in-response";

	@Autowired private MockMvc mockMvc;
	@Autowired private LecturerCourseAuthorization authorization;
	@Autowired private CourseAiSettingsService settings;
	@Autowired private CourseAiCredentialService credentials;
	@Autowired private UserAccountRepository users;
	@Autowired private AuditService audit;

	private final Map<UUID, UserAccount> accounts = new HashMap<>();

	@BeforeEach
	void resetCollaborators() {
		reset(authorization, settings, credentials, users, audit);
		accounts.clear();
		when(users.findById(any(UUID.class))).thenAnswer(call -> Optional.ofNullable(accounts.get(call.getArgument(0))));
	}

	@Test
	void assignedLecturerCanReadUpdateConfigureReplaceAndRevokeOnlySafeMetadata() throws Exception {
		UserAccount lecturer = account(AccountRole.LECTURER);
		Course course = course();
		allow(lecturer, course);
		when(settings.get(course.getId())).thenReturn(new CourseAiSettingsService.Settings(false, false));
		when(settings.update(eq(course), eq(true), eq(true))).thenReturn(new CourseAiSettingsService.Settings(true, true));
		CourseAiCredentialService.SafeMetadata absent = new CourseAiCredentialService.SafeMetadata(false, null, AiProviderRole.PRIMARY, null, null, null, null);
		CourseAiCredentialService.SafeMetadata saved = configured(AiProviderRole.PRIMARY);
		when(credentials.safeMetadata(eq(course), eq(AiProviderRole.PRIMARY))).thenReturn(absent, saved);
		when(credentials.save(eq(course), eq(AiProviderRole.PRIMARY), eq("openai"), eq(SECRET), eq(lecturer))).thenReturn(saved);

		mockMvc.perform(get(path(course, "/ai-settings")).with(authentication(SagaAuthentications.authenticated(lecturer))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.automationEnabled").value(false))
				.andExpect(jsonPath("$.allowPlatformFallback").value(false));

		Csrf csrf = csrf();
		mockMvc.perform(patch(path(course, "/ai-settings"))
						.with(authentication(SagaAuthentications.authenticated(lecturer))).session(csrf.session()).cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"automationEnabled\":true,\"allowPlatformFallback\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.automationEnabled").value(true));

		MvcResult put = mockMvc.perform(put(path(course, "/ai-credentials/PRIMARY"))
						.with(authentication(SagaAuthentications.authenticated(lecturer))).session(csrf.session()).cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"provider\":\"openai\",\"apiKey\":\"" + SECRET + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.configured").value(true))
				.andExpect(jsonPath("$.provider").value("openai"))
				.andExpect(jsonPath("$.role").value("PRIMARY"))
				.andExpect(jsonPath("$.lastFour").value("onse"))
				.andExpect(jsonPath("$.id").doesNotExist())
				.andReturn();
		assertSafeResponse(put.getResponse().getContentAsString());

		mockMvc.perform(delete(path(course, "/ai-credentials/PRIMARY"))
						.with(authentication(SagaAuthentications.authenticated(lecturer))).session(csrf.session()).cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()))
				.andExpect(status().isNoContent());

		verify(settings).update(course, true, true);
		verify(credentials).save(course, AiProviderRole.PRIMARY, "openai", SECRET, lecturer);
		verify(credentials).revoke(course, AiProviderRole.PRIMARY);
	}

	@Test
	void studentIsDeniedCredentialManagementAtTheHttpSecurityBoundary() throws Exception {
		UserAccount student = account(AccountRole.STUDENT);
		Course course = course();
		Csrf csrf = csrf();

		mockMvc.perform(put(path(course, "/ai-credentials/PRIMARY"))
						.with(authentication(SagaAuthentications.authenticated(student))).session(csrf.session()).cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"provider\":\"openai\",\"apiKey\":\"" + SECRET + "\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		mockMvc.perform(delete(path(course, "/ai-credentials/PRIMARY"))
						.with(authentication(SagaAuthentications.authenticated(student))).session(csrf.session()).cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()))
				.andExpect(status().isForbidden());

		verifyNoInteractions(authorization, credentials);
	}

	@Test
	void adminCannotGenericlyManageCourseCredential() throws Exception {
		UserAccount admin = account(AccountRole.ADMIN);
		Course course = course();
		deny(admin, course);
		Csrf csrf = csrf();

		mockMvc.perform(put(path(course, "/ai-credentials/PRIMARY"))
						.with(authentication(SagaAuthentications.authenticated(admin))).session(csrf.session()).cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"provider\":\"openai\",\"apiKey\":\"" + SECRET + "\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("LECTURER_COURSE_FORBIDDEN"));

		verify(credentials, never()).save(any(), any(), any(), any(), any());
	}

	@Test
	void lecturerForCourseACannotUseGuessedCourseBOrCredentialIdentifierToBypassAuthorization() throws Exception {
		UserAccount lecturer = account(AccountRole.LECTURER);
		Course courseA = course();
		Course courseB = course();
		allow(lecturer, courseA);
		deny(lecturer, courseB);
		Csrf csrf = csrf();

		mockMvc.perform(put(path(courseB, "/ai-credentials/PRIMARY"))
						.with(authentication(SagaAuthentications.authenticated(lecturer))).session(csrf.session()).cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"provider\":\"openai\",\"apiKey\":\"" + SECRET + "\"}"))
				.andExpect(status().isForbidden());

		// This API deliberately has no credential-id selector. A UUID in the role position cannot
		// be coerced into a credential lookup, so guessing an ID does not change authorization.
		mockMvc.perform(get(path(courseA, "/ai-credentials/" + UUID.randomUUID()))
						.with(authentication(SagaAuthentications.authenticated(lecturer))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"));

		verify(credentials, never()).save(any(), any(), any(), any(), any());
	}

	private void allow(UserAccount actor, Course course) {
		when(authorization.requireAssignedLecturerStrict(eq(actor), eq(course.getId()))).thenReturn(course);
	}

	private void deny(UserAccount actor, Course course) {
		when(authorization.requireAssignedLecturerStrict(eq(actor), eq(course.getId()))).thenThrow(forbidden());
	}

	private AcademicException forbidden() {
		return new AcademicException(AcademicErrorCode.LECTURER_COURSE_FORBIDDEN, HttpStatus.FORBIDDEN, "Forbidden.");
	}

	private UserAccount account(AccountRole role) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail(role.name().toLowerCase() + "@saga.local");
		account.setUsername(role.name().toLowerCase());
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setPasswordHash("hash");
		accounts.put(account.getId(), account);
		return account;
	}

	private Course course() {
		Course course = new Course();
		course.setId(UUID.randomUUID());
		return course;
	}

	private String path(Course course, String suffix) { return "/api/lecturer/courses/" + course.getId() + suffix; }

	private Csrf csrf() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
		assertThat(cookie).isNotNull();
		return new Csrf(JsonPath.read(result.getResponse().getContentAsString(), "$.token"), cookie, (MockHttpSession) result.getRequest().getSession());
	}

	private CourseAiCredentialService.SafeMetadata configured(AiProviderRole role) {
		return new CourseAiCredentialService.SafeMetadata(true, "openai", role, AiCredentialStatus.UNVERIFIED, "onse", LocalDateTime.of(2026, 9, 24, 12, 0), null);
	}

	private void assertSafeResponse(String body) {
		assertThat(body).doesNotContain(SECRET, "encryptedSecret", "encryptionNonce", "nonce", "master", "transport");
	}

	private record Csrf(String token, Cookie cookie, MockHttpSession session) {}

	@TestConfiguration
	static class Configuration {
		@Bean @Primary LecturerCourseAuthorization lecturerCourseAuthorization() { return mock(LecturerCourseAuthorization.class); }
		@Bean @Primary CourseAiSettingsService courseAiSettingsService() { return mock(CourseAiSettingsService.class); }
		@Bean @Primary CourseAiCredentialService courseAiCredentialService() { return mock(CourseAiCredentialService.class); }
		@Bean @Primary UserAccountRepository userAccountRepository() { return mock(UserAccountRepository.class); }
		@Bean @Primary AuditService auditService() { return mock(AuditService.class); }
		@Bean LecturerCourseAiCredentialController lecturerCourseAiCredentialController(
				LecturerCourseAuthorization authorization, CourseAiSettingsService settings, CourseAiCredentialService credentials,
				UserAccountRepository users, AuditService audit) {
			return new LecturerCourseAiCredentialController(authorization, settings, credentials, users, audit);
		}
	}
}
