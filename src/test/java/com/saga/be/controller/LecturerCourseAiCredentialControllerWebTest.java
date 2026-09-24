package com.saga.be.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.saga.be.ai.AiProviderBinding;
import com.saga.be.dto.ai.CourseAiCredentialResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.enums.*;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaAuthentications;
import com.saga.be.service.ai.AiModelCatalog;
import com.saga.be.service.ai.CourseAiCredentialService;
import com.saga.be.service.ai.CourseAiSettingsService;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.*;
import org.hamcrest.Matchers;
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
		CourseAiCredentialService.SafeMetadata absent = new CourseAiCredentialService.SafeMetadata(false, AiProvider.OPENAI, AiProviderRole.PRIMARY, null, null, null, null, null);
		CourseAiCredentialService.SafeMetadata saved = configured(AiProviderRole.PRIMARY);
		when(credentials.safeMetadata(eq(course), eq(AiProviderRole.PRIMARY), eq(AiProvider.OPENAI))).thenReturn(absent, saved);
		when(credentials.save(eq(course), eq(AiProviderRole.PRIMARY), eq(AiProvider.OPENAI), eq(SECRET), eq(lecturer))).thenReturn(saved);

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
				.andExpect(jsonPath("$.provider").value("OPENAI"))
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
		verify(credentials).save(course, AiProviderRole.PRIMARY, AiProvider.OPENAI, SECRET, lecturer);
		verify(credentials).revoke(course, AiProviderRole.PRIMARY, AiProvider.OPENAI);
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

	@Test
	void assignedLecturerReadsTheServerSideCatalogButStudentAndForeignLecturerCannot() throws Exception {
		UserAccount lecturer = account(AccountRole.LECTURER);
		UserAccount other = account(AccountRole.LECTURER);
		UserAccount student = account(AccountRole.STUDENT);
		Course course = course();
		allow(lecturer, course);
		deny(other, course);

		mockMvc.perform(get(path(course, "/ai-provider-catalog")).with(authentication(SagaAuthentications.authenticated(lecturer))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.providers[*].provider").value(Matchers.contains("OPENAI", "GEMINI", "OPENROUTER")))
				.andExpect(jsonPath("$.providers[0].models[*].modelId").value(Matchers.contains("gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.6-sol")))
				.andExpect(jsonPath("$.providers[0].models[0].freeTierEligible").value(false))
				.andExpect(jsonPath("$.providers[1].models[*].modelId").value(Matchers.contains("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.1-pro-preview")))
				.andExpect(jsonPath("$.providers[1].models[*].displayName").value(Matchers.contains("Gemini 3.8 Flash", "Gemini 3.5 Flash-Lite", "Gemini 3.1 Pro")))
				.andExpect(jsonPath("$.providers[1].models[0].freeTierEligible").value(true))
				.andExpect(jsonPath("$.providers[1].models[2].freeTierEligible").value(false))
				.andExpect(jsonPath("$.providers[2].models.length()").value(1))
				.andExpect(jsonPath("$.providers[2].displayName").value("OpenRouter"))
				.andExpect(jsonPath("$.providers[2].models[0].displayName").value("OpenRouter Free Models Router"))
				.andExpect(jsonPath("$.providers[2].models[0].modelId").value("openrouter/free"))
				.andExpect(jsonPath("$.providers[2].models[0].supportsStructuredOutput").value(true))
				.andExpect(jsonPath("$.providers[2].models[0].recommendedForAutomation").value(false))
				.andExpect(jsonPath("$.freeTierNotice").value(Matchers.containsString("may change")));
		mockMvc.perform(get(path(course, "/ai-provider-catalog")).with(authentication(SagaAuthentications.authenticated(other))))
				.andExpect(status().isForbidden());
		mockMvc.perform(get(path(course, "/ai-provider-catalog")).with(authentication(SagaAuthentications.authenticated(student))))
				.andExpect(status().isForbidden());
	}

	@Test
	void perProviderRoutesSaveListAndRevokeEachProviderIndependentlyWithSafeMetadataOnly() throws Exception {
		UserAccount lecturer = account(AccountRole.LECTURER);
		Course course = course();
		allow(lecturer, course);
		for (AiProvider provider : AiProvider.values()) {
			when(credentials.safeMetadata(eq(course), eq(AiProviderRole.PRIMARY), eq(provider))).thenReturn(new CourseAiCredentialService.SafeMetadata(false, provider, AiProviderRole.PRIMARY, null, null, null, null, null));
			when(credentials.save(eq(course), eq(AiProviderRole.PRIMARY), eq(provider), eq(SECRET), eq(lecturer))).thenReturn(configured(AiProviderRole.PRIMARY, provider));
		}
		when(credentials.list(course)).thenReturn(List.of(configured(AiProviderRole.PRIMARY, AiProvider.OPENAI), configured(AiProviderRole.PRIMARY, AiProvider.GEMINI), configured(AiProviderRole.SECONDARY, AiProvider.OPENROUTER)));
		Csrf csrf = csrf();

		for (String provider : List.of("OPENAI", "gemini", "OpenRouter")) {
			MvcResult put = mockMvc.perform(put(path(course, "/ai-credentials/PRIMARY/" + provider))
							.with(authentication(SagaAuthentications.authenticated(lecturer))).session(csrf.session()).cookie(csrf.cookie())
							.header("X-XSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
							.content("{\"apiKey\":\"" + SECRET + "\"}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.provider").value(provider.toUpperCase(Locale.ROOT)))
					.andExpect(jsonPath("$.status").value("UNVERIFIED"))
					.andExpect(jsonPath("$.createdAt").exists())
					.andReturn();
			assertSafeResponse(put.getResponse().getContentAsString());
		}
		MvcResult list = mockMvc.perform(get(path(course, "/ai-credentials")).with(authentication(SagaAuthentications.authenticated(lecturer))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].provider").value(Matchers.contains("OPENAI", "GEMINI", "OPENROUTER")))
				.andReturn();
		assertSafeResponse(list.getResponse().getContentAsString());
		mockMvc.perform(delete(path(course, "/ai-credentials/PRIMARY/GEMINI"))
						.with(authentication(SagaAuthentications.authenticated(lecturer))).session(csrf.session()).cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()))
				.andExpect(status().isNoContent());

		for (AiProvider provider : AiProvider.values()) verify(credentials).save(course, AiProviderRole.PRIMARY, provider, SECRET, lecturer);
		verify(credentials).revoke(course, AiProviderRole.PRIMARY, AiProvider.GEMINI);
		verify(credentials, never()).revoke(course, AiProviderRole.PRIMARY, AiProvider.OPENAI);
	}

	@Test
	void unknownProviderAndPathBodyMismatchAreRejectedWithoutSaving() throws Exception {
		UserAccount lecturer = account(AccountRole.LECTURER);
		Course course = course();
		allow(lecturer, course);
		Csrf csrf = csrf();

		mockMvc.perform(put(path(course, "/ai-credentials/PRIMARY/ANTHROPIC"))
						.with(authentication(SagaAuthentications.authenticated(lecturer))).session(csrf.session()).cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"apiKey\":\"" + SECRET + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AI_PROVIDER_NOT_SUPPORTED"));
		mockMvc.perform(put(path(course, "/ai-credentials/PRIMARY/GEMINI"))
						.with(authentication(SagaAuthentications.authenticated(lecturer))).session(csrf.session()).cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"provider\":\"OPENAI\",\"apiKey\":\"" + SECRET + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AI_CREDENTIAL_INVALID_REQUEST"));
		MvcResult legacy = mockMvc.perform(put(path(course, "/ai-credentials/PRIMARY"))
						.with(authentication(SagaAuthentications.authenticated(lecturer))).session(csrf.session()).cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"provider\":\"mistral\",\"apiKey\":\"" + SECRET + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AI_PROVIDER_NOT_SUPPORTED"))
				.andReturn();
		assertSafeResponse(legacy.getResponse().getContentAsString());

		verify(credentials, never()).save(any(), any(), any(), any(), any());
	}

	@Test
	void bindingsAreFullyReplacedThroughTheValidatingServiceAndReturnedCanonically() throws Exception {
		UserAccount lecturer = account(AccountRole.LECTURER);
		Course course = course();
		allow(lecturer, course);
		AiProviderBinding gemini = new AiProviderBinding(AiProvider.GEMINI, "gemini-3.8-flash");
		AiProviderBinding openrouter = new AiProviderBinding(AiProvider.OPENROUTER, "openrouter/free");
		AiProviderBinding terra = new AiProviderBinding(AiProvider.OPENAI, "gpt-5.6-terra");
		var updated = new CourseAiSettingsService.Settings(true, false, gemini, true, List.of(openrouter), terra);
		when(settings.get(course.getId())).thenReturn(new CourseAiSettingsService.Settings(true, false));
		when(settings.updateBindings(eq(course), any(), eq(true), any(), any())).thenReturn(updated);
		when(settings.storedFallbackBindings(course.getId())).thenReturn(List.of(openrouter));
		Csrf csrf = csrf();

		mockMvc.perform(put(path(course, "/ai-settings/bindings"))
						.with(authentication(SagaAuthentications.authenticated(lecturer))).session(csrf.session()).cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"primaryBinding\":{\"provider\":\"gemini\",\"modelId\":\"gemini-3.8-flash\"},\"fallbackEnabled\":true,"
								+ "\"fallbackBindings\":[{\"provider\":\"OPENROUTER\",\"modelId\":\"openrouter/free\"}],"
								+ "\"secondaryBinding\":{\"provider\":\"OPENAI\",\"modelId\":\"gpt-5.6-terra\"}}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.automationEnabled").value(true))
				.andExpect(jsonPath("$.allowPlatformFallback").value(false))
				.andExpect(jsonPath("$.primaryBinding.provider").value("GEMINI"))
				.andExpect(jsonPath("$.primaryBinding.modelId").value("gemini-3.8-flash"))
				.andExpect(jsonPath("$.fallbackEnabled").value(true))
				.andExpect(jsonPath("$.fallbackBindings[0].provider").value("OPENROUTER"))
				.andExpect(jsonPath("$.secondaryBinding.modelId").value("gpt-5.6-terra"));

		verify(settings).updateBindings(course, new CourseAiSettingsService.BindingInput("gemini", "gemini-3.8-flash"), true,
				List.of(new CourseAiSettingsService.BindingInput("OPENROUTER", "openrouter/free")), new CourseAiSettingsService.BindingInput("OPENAI", "gpt-5.6-terra"));
		verify(settings, never()).update(any(), anyBoolean(), anyBoolean());
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
		return configured(role, AiProvider.OPENAI);
	}

	private CourseAiCredentialService.SafeMetadata configured(AiProviderRole role, AiProvider provider) {
		return new CourseAiCredentialService.SafeMetadata(true, provider, role, AiCredentialStatus.UNVERIFIED, "onse", LocalDateTime.of(2026, 9, 24, 11, 0), LocalDateTime.of(2026, 9, 24, 12, 0), null);
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
			return new LecturerCourseAiCredentialController(authorization, settings, credentials, new AiModelCatalog(), users, audit);
		}
	}
}
