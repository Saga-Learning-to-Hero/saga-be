package com.saga.be.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.AiAcademicClassificationStatus;
import com.saga.be.entity.enums.AiAcademicProvenance;
import com.saga.be.entity.enums.AiAcademicTargetType;
import com.saga.be.entity.enums.AiArtifactType;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.AiAcademicClassificationRepository;
import com.saga.be.repository.LecturerCourseAcademicClassificationRow;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaAuthentications;
import com.saga.be.service.ai.LecturerCourseAcademicClassificationReadService;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * HTTP-boundary test for GET /api/lecturer/courses/{courseId}/ai/academic-classifications. The
 * production controller and read service are excluded by their {@code !test} profile, so this
 * registers both for real (real routing, security filter chain, argument conversion, global error
 * mapping and service logic) and mocks only course authorization and persistence.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LecturerCourseAiAcademicClassificationControllerWebTest.Configuration.class)
class LecturerCourseAiAcademicClassificationControllerWebTest {

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private LecturerCourseAuthorization authorization;
	@Autowired private AiAcademicClassificationRepository classifications;
	@Autowired private UserAccountRepository users;

	private final Map<UUID, UserAccount> accounts = new HashMap<>();

	@BeforeEach
	void resetCollaborators() {
		reset(authorization, classifications, users);
		accounts.clear();
		when(users.findById(any(UUID.class))).thenAnswer(call -> Optional.ofNullable(accounts.get(call.getArgument(0))));
	}

	@Test
	void assignedLecturerGetsThePageWithDefaultPagingAndNoFilters() throws Exception {
		UserAccount lecturer = account(AccountRole.LECTURER);
		Course course = course();
		allow(lecturer, course);
		LecturerCourseAcademicClassificationRow row = fullRow();
		when(classifications.findCoursePage(eq(course.getId()), isNull(), isNull(), isNull(), isNull(), any()))
				.thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 50), 1));

		mockMvc.perform(as(get(path(course)), lecturer))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(50))
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.items[0].classification.id").value(row.id().toString()))
				.andExpect(jsonPath("$.items[0].classification.artifactType").value("COMMIT"))
				.andExpect(jsonPath("$.items[0].classification.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.items[0].classification.provenance").value("HUMAN"))
				.andExpect(jsonPath("$.items[0].classification.targetType").value("EXPECTED_DELIVERABLE"))
				.andExpect(jsonPath("$.items[0].classification.targetCode").value("D1"))
				.andExpect(jsonPath("$.items[0].authoritative").value(true))
				.andExpect(jsonPath("$.items[0].projectName").value("Alpha Project"))
				.andExpect(jsonPath("$.items[0].teamName").value("Alpha"))
				.andExpect(jsonPath("$.items[0].commitSha").value("abc123"));

		verify(classifications).findCoursePage(course.getId(), null, null, null, null, PageRequest.of(0, 50));
	}

	@Test
	void studentIsDeniedAtTheSecurityBoundaryBeforeAnyAuthorizationOrQuery() throws Exception {
		UserAccount student = account(AccountRole.STUDENT);

		mockMvc.perform(as(get(path(course())), student))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

		verifyNoInteractions(authorization, classifications);
	}

	@Test
	void unrelatedLecturerIsForbiddenAndNothingIsQueried() throws Exception {
		UserAccount lecturer = account(AccountRole.LECTURER);
		Course course = course();
		deny(lecturer, course);

		mockMvc.perform(as(get(path(course)), lecturer))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("LECTURER_COURSE_FORBIDDEN"));

		verify(classifications, never()).findCoursePage(any(), any(), any(), any(), any(), any());
	}

	@Test
	void adminGoesThroughTheStrictPolicyNotTheAdminBypassingRequireCourse() throws Exception {
		UserAccount admin = account(AccountRole.ADMIN);
		Course course = course();
		deny(admin, course);

		mockMvc.perform(as(get(path(course)), admin))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("LECTURER_COURSE_FORBIDDEN"));

		verify(authorization).requireAssignedLecturerStrict(eq(admin), eq(course.getId()), anyString());
		verify(authorization, never()).requireCourse(any(), any());
		verify(classifications, never()).findCoursePage(any(), any(), any(), any(), any(), any());
	}

	@Test
	void unknownAndGuessedCrossCourseIdsAreSafe() throws Exception {
		UserAccount lecturer = account(AccountRole.LECTURER);
		Course own = course();
		Course other = course();
		Course unknown = course();
		allow(lecturer, own);
		deny(lecturer, other);
		when(authorization.requireAssignedLecturerStrict(eq(lecturer), eq(unknown.getId()), anyString()))
				.thenThrow(new AcademicException(AcademicErrorCode.COURSE_NOT_FOUND, HttpStatus.NOT_FOUND, "Course was not found."));

		mockMvc.perform(as(get(path(other)), lecturer))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("LECTURER_COURSE_FORBIDDEN"));
		mockMvc.perform(as(get(path(unknown)), lecturer))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"));

		verify(classifications, never()).findCoursePage(any(), any(), any(), any(), any(), any());
	}

	@Test
	void paginationParamsPassThroughAndOutOfRangeIsRejected() throws Exception {
		UserAccount lecturer = account(AccountRole.LECTURER);
		Course course = course();
		allow(lecturer, course);
		when(classifications.findCoursePage(eq(course.getId()), any(), any(), any(), any(), any()))
				.thenAnswer(call -> new PageImpl<>(List.of(), call.<Pageable>getArgument(5), 57));

		mockMvc.perform(as(get(path(course)).param("page", "2").param("size", "10"), lecturer))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(2))
				.andExpect(jsonPath("$.size").value(10))
				.andExpect(jsonPath("$.total").value(57));
		verify(classifications).findCoursePage(course.getId(), null, null, null, null, PageRequest.of(2, 10));

		for (String[] bad : List.of(new String[] {"0", "201"}, new String[] {"0", "0"}, new String[] {"-1", "10"})) {
			mockMvc.perform(as(get(path(course)).param("page", bad[0]).param("size", bad[1]), lecturer))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
		}
		verify(classifications, never()).findCoursePage(any(), any(), any(), any(), any(), eq(PageRequest.of(0, 201)));
	}

	@Test
	void filtersPassThroughUsingTheExistingEnumsAndInvalidValuesAreRejected() throws Exception {
		UserAccount lecturer = account(AccountRole.LECTURER);
		Course course = course();
		allow(lecturer, course);
		UUID projectId = UUID.randomUUID();
		UUID teamId = UUID.randomUUID();
		when(classifications.findCoursePage(any(), any(), any(), any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

		mockMvc.perform(as(get(path(course))
								.param("artifactType", "TASK")
								.param("status", "CORRECTED")
								.param("projectId", projectId.toString())
								.param("teamId", teamId.toString()),
						lecturer))
				.andExpect(status().isOk());
		verify(classifications).findCoursePage(
				course.getId(), AiArtifactType.TASK, AiAcademicClassificationStatus.CORRECTED, projectId, teamId,
				PageRequest.of(0, 50));

		for (String[] bad : List.of(
				new String[] {"artifactType", "SPRINT"},
				new String[] {"status", "APPROVED"},
				new String[] {"projectId", "not-a-uuid"})) {
			mockMvc.perform(as(get(path(course)).param(bad[0], bad[1]), lecturer))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
		}
	}

	@Test
	void courseWithoutClassificationsIsASuccessfulEmptyPage() throws Exception {
		UserAccount lecturer = account(AccountRole.LECTURER);
		Course course = course();
		allow(lecturer, course);
		when(classifications.findCoursePage(eq(course.getId()), any(), any(), any(), any(), any()))
				.thenReturn(Page.empty(PageRequest.of(0, 50)));

		mockMvc.perform(as(get(path(course)), lecturer))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty())
				.andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void responseExposesOnlyClassificationAndContextFieldsNoSecretOrProviderMetadata() throws Exception {
		UserAccount lecturer = account(AccountRole.LECTURER);
		Course course = course();
		allow(lecturer, course);
		when(classifications.findCoursePage(eq(course.getId()), any(), any(), any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(fullRow()), PageRequest.of(0, 50), 1));

		String body = mockMvc.perform(as(get(path(course)), lecturer))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		JsonNode root = objectMapper.readTree(body);
		assertThat(fieldNames(root)).containsExactlyInAnyOrder("items", "page", "size", "total");
		JsonNode item = root.get("items").get(0);
		assertThat(fieldNames(item)).containsExactlyInAnyOrder(
				"classification", "authoritative", "projectId", "projectName", "teamId", "teamName",
				"taskExternalKey", "taskTitle", "commitSha", "commitMessage");
		assertThat(fieldNames(item.get("classification"))).containsExactlyInAnyOrder(
				"id", "artifactType", "artifactId", "artifactRevision", "syllabusVersionId", "targetType",
				"targetId", "targetCode", "targetName", "confidence", "aiSummary", "status", "provenance",
				"sourceClassificationId", "reviewedAt", "createdAt");
		assertThat(body.toLowerCase()).doesNotContain(
				"apikey", "credential", "encrypted", "fingerprint", "providerkey", "modelid", "costmetadata",
				"idempotency", "evidencehash", "prompt");
	}

	private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UserAccount actor) {
		return request.with(authentication(SagaAuthentications.authenticated(actor)));
	}

	private void allow(UserAccount actor, Course course) {
		when(authorization.requireAssignedLecturerStrict(eq(actor), eq(course.getId()), anyString())).thenReturn(course);
	}

	private void deny(UserAccount actor, Course course) {
		when(authorization.requireAssignedLecturerStrict(eq(actor), eq(course.getId()), anyString()))
				.thenThrow(new AcademicException(AcademicErrorCode.LECTURER_COURSE_FORBIDDEN, HttpStatus.FORBIDDEN, "Forbidden."));
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

	private static Course course() {
		Course course = new Course();
		course.setId(UUID.randomUUID());
		return course;
	}

	private static String path(Course course) {
		return "/api/lecturer/courses/" + course.getId() + "/ai/academic-classifications";
	}

	private static LecturerCourseAcademicClassificationRow fullRow() {
		return new LecturerCourseAcademicClassificationRow(
				UUID.randomUUID(), AiArtifactType.COMMIT, UUID.randomUUID(), "abc123",
				UUID.randomUUID(), AiAcademicTargetType.EXPECTED_DELIVERABLE,
				UUID.randomUUID(), "P1", "Inception",
				UUID.randomUUID(), "D1", "SRS document",
				1d, "lecturer reason", AiAcademicClassificationStatus.CONFIRMED, AiAcademicProvenance.HUMAN,
				UUID.randomUUID(), LocalDateTime.of(2026, 9, 20, 10, 0), LocalDateTime.of(2026, 9, 20, 10, 0),
				UUID.randomUUID(), "Alpha Project", UUID.randomUUID(), "Alpha",
				"SAGA-1", "Login screen", "abc123", "feat: login");
	}

	private static List<String> fieldNames(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	@TestConfiguration
	static class Configuration {
		@Bean @Primary LecturerCourseAuthorization lecturerCourseAuthorization() { return mock(LecturerCourseAuthorization.class); }
		@Bean @Primary AiAcademicClassificationRepository aiAcademicClassificationRepository() { return mock(AiAcademicClassificationRepository.class); }
		@Bean @Primary UserAccountRepository userAccountRepository() { return mock(UserAccountRepository.class); }
		@Bean LecturerCourseAcademicClassificationReadService lecturerCourseAcademicClassificationReadService(
				AiAcademicClassificationRepository classifications, LecturerCourseAuthorization authorization) {
			return new LecturerCourseAcademicClassificationReadService(classifications, authorization);
		}
		@Bean LecturerCourseAiAcademicClassificationController lecturerCourseAiAcademicClassificationController(
				LecturerCourseAcademicClassificationReadService reads, UserAccountRepository users) {
			return new LecturerCourseAiAcademicClassificationController(reads, users);
		}
	}
}
