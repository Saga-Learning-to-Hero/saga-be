package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.ai.AiProviderBinding;
import com.saga.be.entity.ai.AiAnalysisProviderDecision;
import com.saga.be.entity.ai.AiTeamCredential;
import com.saga.be.entity.ai.CourseAiProviderCredential;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiCredentialStatus;
import com.saga.be.entity.enums.AiInvocationOrigin;
import com.saga.be.entity.enums.AiProvider;
import com.saga.be.entity.enums.AiProviderRole;
import com.saga.be.entity.project.Project;
import com.saga.be.repository.AiCourseKeyGrantRepository;
import com.saga.be.repository.AiTeamCredentialRepository;
import com.saga.be.repository.CourseAiProviderCredentialRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A team's AI work: its own key first, the course key only for a team the lecturer picked, else none. */
class AiProjectKeyRoutingTest {

	private final UUID projectId = UUID.randomUUID();
	private final UUID courseId = UUID.randomUUID();
	private final UUID courseCredentialId = UUID.randomUUID();
	private AiTeamCredentialRepository teamCredentials;
	private AiCourseKeyGrantRepository grants;
	private AiCredentialResolver resolver;

	@BeforeEach
	void setUp() {
		CourseAiProviderCredentialRepository courseCredentials = mock(CourseAiProviderCredentialRepository.class);
		CourseAiSettingsService settings = mock(CourseAiSettingsService.class);
		when(settings.get(courseId)).thenReturn(new CourseAiSettingsService.Settings(true, false));
		CourseAiProviderCredential courseKey = new CourseAiProviderCredential();
		courseKey.setId(courseCredentialId);
		courseKey.setFingerprint("course-fp");
		courseKey.setStatus(AiCredentialStatus.ACTIVE);
		when(courseCredentials.findByCourse_IdAndProviderRoleAndProvider(eq(courseId), any(), eq(AiProvider.OPENAI))).thenReturn(Optional.of(courseKey));
		resolver = new AiCredentialResolver(courseCredentials, settings, mock(CourseAiCredentialService.class),
				new AiCredentialTransportCipher(""), List.of(), new AiModelCatalog());
		teamCredentials = mock(AiTeamCredentialRepository.class);
		grants = mock(AiCourseKeyGrantRepository.class);
		when(teamCredentials.findByProject_Id(projectId)).thenReturn(Optional.empty());
		when(grants.existsByProject_Id(projectId)).thenReturn(false);
		resolver.setTeamCredentials(teamCredentials);
		resolver.setCourseKeyGrants(grants);
	}

	private AiTeamCredential teamKey(AiCredentialStatus status) {
		AiTeamCredential key = new AiTeamCredential();
		key.setId(UUID.randomUUID());
		Project project = new Project();
		project.setId(projectId);
		key.setProject(project);
		key.setProvider(AiProvider.GEMINI);
		key.setModelId("gemini-3.6-flash");
		key.setFingerprint("team-fp");
		key.setStatus(status);
		return key;
	}

	private AiCredentialResolver.Resolution route(AiProviderRole role) {
		return resolver.resolveForProject(projectId, courseId, AiAnalysisType.TASK_INTELLIGENCE, role, AiInvocationOrigin.USER_REQUEST);
	}

	@Test
	void theTeamsOwnKeyComesFirst_evenWhenTheTeamMayAlsoUseTheCourseKey() {
		AiTeamCredential key = teamKey(AiCredentialStatus.ACTIVE);
		when(teamCredentials.findByProject_Id(projectId)).thenReturn(Optional.of(key));
		when(grants.existsByProject_Id(projectId)).thenReturn(true);

		AiCredentialResolver.Resolution resolution = route(AiProviderRole.PRIMARY);

		assertThat(resolution.team()).isTrue();
		assertThat(resolution.outcome()).isEqualTo(AiCredentialResolver.Outcome.COURSE);
		assertThat(resolution.teamCredentialId()).isEqualTo(key.getId());
		assertThat(resolution.courseCredentialId()).isNull();
		assertThat(resolution.binding()).isEqualTo(new AiProviderBinding(AiProvider.GEMINI, "gemini-3.6-flash"));
		assertThat(resolution.identityFingerprint()).isEqualTo("team-fp@GEMINI:gemini-3.6-flash");
	}

	@Test
	void withoutAKeyAndNotPickedByTheLecturer_theTeamHasNoAi() {
		AiCredentialResolver.Resolution resolution = route(AiProviderRole.PRIMARY);
		assertThat(resolution.outcome()).isEqualTo(AiCredentialResolver.Outcome.UNAVAILABLE);
		assertThat(resolver.courseKeyAllowed(projectId)).isFalse();
	}

	@Test
	void aTeamThePickedByTheLecturer_usesTheCourseKey() {
		when(grants.existsByProject_Id(projectId)).thenReturn(true);
		AiCredentialResolver.Resolution resolution = route(AiProviderRole.PRIMARY);
		assertThat(resolution.team()).isFalse();
		assertThat(resolution.outcome()).isEqualTo(AiCredentialResolver.Outcome.COURSE);
		assertThat(resolution.courseCredentialId()).isEqualTo(courseCredentialId);
	}

	@Test
	void aRejectedOrRemovedTeamKeyFallsToTheLecturersChoice() {
		when(teamCredentials.findByProject_Id(projectId)).thenReturn(Optional.of(teamKey(AiCredentialStatus.INVALID)));
		assertThat(route(AiProviderRole.PRIMARY).outcome()).isEqualTo(AiCredentialResolver.Outcome.UNAVAILABLE);
		when(grants.existsByProject_Id(projectId)).thenReturn(true);
		assertThat(route(AiProviderRole.PRIMARY).courseCredentialId()).isEqualTo(courseCredentialId);
		when(teamCredentials.findByProject_Id(projectId)).thenReturn(Optional.of(teamKey(AiCredentialStatus.REVOKED)));
		assertThat(route(AiProviderRole.PRIMARY).team()).isFalse();
	}

	@Test
	void aDegradedTeamKeyIsStillTheTeamsKey() {
		when(teamCredentials.findByProject_Id(projectId)).thenReturn(Optional.of(teamKey(AiCredentialStatus.DEGRADED)));
		assertThat(route(AiProviderRole.PRIMARY).team()).isTrue();
	}

	@Test
	void theSecondaryBrainNeverUsesATeamKey_andTheCourseOneOnlyForAPickedTeam() {
		when(teamCredentials.findByProject_Id(projectId)).thenReturn(Optional.of(teamKey(AiCredentialStatus.ACTIVE)));
		assertThat(route(AiProviderRole.SECONDARY).team()).isFalse();
		assertThat(route(AiProviderRole.SECONDARY).outcome()).isEqualTo(AiCredentialResolver.Outcome.UNAVAILABLE);
	}

	@Test
	void courseLevelWorkIsUnchanged() {
		AiCredentialResolver.Resolution resolution = resolver.resolveForProject(null, courseId, AiAnalysisType.PROGRESS_NARRATIVE, AiProviderRole.PRIMARY, AiInvocationOrigin.USER_REQUEST);
		assertThat(resolution.courseCredentialId()).isEqualTo(courseCredentialId);
		verify(grants, never()).existsByProject_Id(any());
	}

	@Test
	void theCourseOnlyRouteNeverReturnsTheTeamKey() {
		when(teamCredentials.findByProject_Id(projectId)).thenReturn(Optional.of(teamKey(AiCredentialStatus.ACTIVE)));
		assertThat(resolver.resolveCourseForProject(projectId, courseId, AiAnalysisType.COMMIT_INTELLIGENCE, AiProviderRole.PRIMARY, AiInvocationOrigin.AUTOMATION).outcome())
				.isEqualTo(AiCredentialResolver.Outcome.UNAVAILABLE);
	}

	@Test
	void applyBindingRecordsTheTeamKeyOnTheDecision() {
		UUID teamCredentialId = UUID.randomUUID();
		AiAnalysisProviderDecision decision = new AiAnalysisProviderDecision();
		decision.setCourseCredentialId(UUID.randomUUID());
		AiCredentialResolver.applyBinding(decision, new AiCredentialResolver.Resolution(AiCredentialResolver.Outcome.COURSE, null, "team-fp",
				new AiProviderBinding(AiProvider.COHERE, "command-a-plus-05-2026"), teamCredentialId));
		assertThat(decision.getTeamCredentialId()).isEqualTo(teamCredentialId);
		assertThat(decision.getCourseCredentialId()).isNull();
		assertThat(decision.getAiProvider()).isEqualTo(AiProvider.COHERE);
		assertThat(decision.getModelId()).isEqualTo("command-a-plus-05-2026");
	}
}
