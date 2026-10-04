package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.ai.CourseAiTeamAccessDtos;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.ai.AiCourseKeyGrant;
import com.saga.be.entity.ai.AiTeamCredential;
import com.saga.be.entity.enums.AiCredentialStatus;
import com.saga.be.entity.enums.AiProvider;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.AiCourseKeyGrantRepository;
import com.saga.be.repository.AiTeamCredentialRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CourseAiTeamAccessServiceTest {

	private final UUID courseId = UUID.randomUUID();
	private final UserAccount lecturer = new UserAccount();
	private LecturerCourseAuthorization authorization;
	private AiCourseKeyGrantRepository grants;
	private AiTeamCredentialRepository teamCredentials;
	private AiCredentialResolver resolver;
	private CourseAiTeamAccessService service;
	private Team withKey;
	private Team picked;
	private Team neither;

	@BeforeEach
	void setUp() {
		authorization = mock(LecturerCourseAuthorization.class);
		TeamRepository teams = mock(TeamRepository.class);
		grants = mock(AiCourseKeyGrantRepository.class);
		teamCredentials = mock(AiTeamCredentialRepository.class);
		resolver = mock(AiCredentialResolver.class);
		CourseAiSettingsService settings = mock(CourseAiSettingsService.class);
		when(settings.get(courseId)).thenReturn(new CourseAiSettingsService.Settings(true, false));
		PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
		when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		when(resolver.resolve(eq(courseId), any(), any(), any()))
				.thenReturn(new AiCredentialResolver.Resolution(AiCredentialResolver.Outcome.COURSE, UUID.randomUUID(), "fp"));
		withKey = team(1, "Nhóm 1");
		picked = team(2, "Nhóm 2");
		neither = team(3, "Nhóm 3");
		when(teams.findByCourse_IdOrderByTeamNoAsc(courseId)).thenReturn(List.of(withKey, picked, neither));
		AiTeamCredential key = new AiTeamCredential();
		key.setProject(withKey.getProject());
		key.setProvider(AiProvider.GEMINI);
		key.setModelId("gemini-3.6-flash");
		key.setStatus(AiCredentialStatus.ACTIVE);
		when(teamCredentials.findByProject_IdIn(any())).thenReturn(List.of(key));
		when(grants.findGrantedProjectIds(any())).thenReturn(List.of(picked.getProject().getId()));
		service = new CourseAiTeamAccessService(authorization, teams, grants, teamCredentials, resolver, settings, tx);
	}

	private static Team team(int no, String name) {
		Project project = new Project();
		project.setId(UUID.randomUUID());
		project.setName("Dự án " + no);
		Team team = new Team();
		team.setId(UUID.randomUUID());
		team.setTeamNo(no);
		team.setName(name);
		team.setProject(project);
		return team;
	}

	@Test
	void everyTeamShowsItsOwnKeyTheLecturersChoiceAndTheKeyInEffect() {
		CourseAiTeamAccessDtos.Response response = service.list(lecturer, courseId);

		assertThat(response.courseKeyConfigured()).isTrue();
		assertThat(response.automationEnabled()).isTrue();
		assertThat(response.teams()).extracting(CourseAiTeamAccessDtos.TeamAccess::teamName).containsExactly("Nhóm 1", "Nhóm 2", "Nhóm 3");
		assertThat(response.teams()).extracting(CourseAiTeamAccessDtos.TeamAccess::effectiveKey).containsExactly("TEAM", "COURSE", "NONE");
		assertThat(response.teams().get(0).teamKey().provider()).isEqualTo("GEMINI");
		assertThat(response.teams().get(1).courseKeyAllowed()).isTrue();
		assertThat(response.teams().get(2).courseKeyAllowed()).isFalse();
		assertThat(response.toString()).doesNotContain("encrypted");
	}

	@Test
	void pickingATeamStoresAGrant_unpickingRemovesIt() {
		when(grants.findByProject_Id(neither.getProject().getId())).thenReturn(Optional.empty());
		service.update(lecturer, courseId, neither.getProject().getId(), new CourseAiTeamAccessDtos.UpdateRequest(true));
		ArgumentCaptor<AiCourseKeyGrant> saved = ArgumentCaptor.forClass(AiCourseKeyGrant.class);
		verify(grants).save(saved.capture());
		assertThat(saved.getValue().getProject()).isSameAs(neither.getProject());
		assertThat(saved.getValue().getGrantedBy()).isSameAs(lecturer);

		AiCourseKeyGrant existing = new AiCourseKeyGrant();
		when(grants.findByProject_Id(picked.getProject().getId())).thenReturn(Optional.of(existing));
		service.update(lecturer, courseId, picked.getProject().getId(), new CourseAiTeamAccessDtos.UpdateRequest(false));
		verify(grants).delete(existing);
	}

	@Test
	void pickingAnAlreadyPickedTeamAddsNothing() {
		when(grants.findByProject_Id(picked.getProject().getId())).thenReturn(Optional.of(new AiCourseKeyGrant()));
		service.update(lecturer, courseId, picked.getProject().getId(), new CourseAiTeamAccessDtos.UpdateRequest(true));
		verify(grants, never()).save(any());
	}

	@Test
	void aTeamOfAnotherCourseIsRejected_andAllowedIsRequired() {
		assertThatThrownBy(() -> service.update(lecturer, courseId, UUID.randomUUID(), new CourseAiTeamAccessDtos.UpdateRequest(true)))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> assertThat(((IntegrationException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
		assertThatThrownBy(() -> service.update(lecturer, courseId, picked.getProject().getId(), new CourseAiTeamAccessDtos.UpdateRequest(null)))
				.isInstanceOf(IntegrationException.class);
		verify(grants, never()).save(any());
	}

	@Test
	void onlyTheAssignedLecturer() {
		doThrow(new IntegrationException(IntegrationErrorCode.ACCESS_DENIED, HttpStatus.FORBIDDEN, "no"))
				.when(authorization).requireAssignedLecturerStrict(any(), eq(courseId), anyString());
		assertThatThrownBy(() -> service.list(lecturer, courseId)).isInstanceOf(IntegrationException.class);
		assertThatThrownBy(() -> service.update(lecturer, courseId, picked.getProject().getId(), new CourseAiTeamAccessDtos.UpdateRequest(true)))
				.isInstanceOf(IntegrationException.class);
		verify(grants, never()).save(any());
	}

	@Test
	void withoutACourseKeyNoPickedTeamHasAi_andAResolverFailureIsSafe() {
		when(resolver.resolve(eq(courseId), any(), any(), any())).thenThrow(new IntegrationException(IntegrationErrorCode.AI_MODEL_NOT_SUPPORTED, HttpStatus.BAD_REQUEST, "gone"));
		CourseAiTeamAccessDtos.Response response = service.list(lecturer, courseId);
		assertThat(response.courseKeyConfigured()).isFalse();
		assertThat(response.teams()).extracting(CourseAiTeamAccessDtos.TeamAccess::effectiveKey).containsExactly("TEAM", "NONE", "NONE");
	}
}
