package com.saga.be.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.service.projection.JiraAutoFailoverService;
import com.saga.be.service.projection.JiraAutoFailoverService.Outcome;
import com.saga.be.service.projection.JiraAutoFailoverService.Result;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class JiraSourceHealthMonitorTest {

	private static final Instant START = Instant.parse("2026-10-04T08:00:00Z");

	private JiraIntegrationRepository integrations;
	private JiraTeamTokenService tokens;
	private JiraIssueWriteClient jira;
	private JiraAutoFailoverService failover;
	private MutableClock clock;
	private JiraSourceHealthMonitor monitor;
	private Project project;
	private JiraIntegration dying;
	private JiraIntegration standby;

	@BeforeEach
	void setUp() {
		integrations = mock(JiraIntegrationRepository.class);
		tokens = mock(JiraTeamTokenService.class);
		jira = mock(JiraIssueWriteClient.class);
		failover = mock(JiraAutoFailoverService.class);
		clock = new MutableClock(START);
		monitor = new JiraSourceHealthMonitor(integrations, tokens, jira, failover, clock, true, Duration.ofHours(1), 3);
		project = new Project();
		project.setId(UUID.randomUUID());
		dying = source("cloud-old", "10000");
		standby = source("cloud-new", "20000");
		when(integrations.findByConnectionStatus(IntegrationStatus.ACTIVE)).thenReturn(List.of(dying, standby));
		when(tokens.accessToken(dying)).thenReturn("old-token");
		when(tokens.accessToken(standby)).thenReturn("new-token");
		when(jira.listProjectIssueTypes("new-token", "cloud-new", "20000")).thenReturn(List.of());
		when(failover.failover(any(), any(), any())).thenReturn(new Result(Outcome.MOVED, null, 4));
	}

	@Test
	void aSourceGoneForAnHourAndThreeProbesFailsOverToTheHealthyOne() {
		dyingAnswers(new IntegrationException(IntegrationErrorCode.INTEGRATION_UNAVAILABLE, HttpStatus.NOT_FOUND, "gone"));

		monitor.probe(); // 08:00 1st
		clock.advance(Duration.ofMinutes(30));
		monitor.probe(); // 08:30 2nd
		clock.advance(Duration.ofMinutes(29));
		monitor.probe(); // 08:59 3rd: three probes but not an hour yet
		verify(failover, never()).failover(any(), any(), any());

		clock.advance(Duration.ofMinutes(1));
		monitor.probe(); // 09:00 4th: an hour since the first failure

		verify(failover).failover(dying.getId(), standby.getId(), LocalDateTime.of(2026, 10, 4, 8, 0));
		assertThat(monitor.streaks()).doesNotContainKey(dying.getId());
	}

	@Test
	void timeoutsAndServerErrorsNeverCountAndASuccessClearsTheStreak() {
		dyingAnswers(new IntegrationException(IntegrationErrorCode.INTEGRATION_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "5xx"));
		for (int i = 0; i < 10; i++) {
			monitor.probe();
			clock.advance(Duration.ofMinutes(30));
		}
		assertThat(monitor.streaks()).isEmpty();

		dyingAnswers(new IntegrationException(IntegrationErrorCode.JIRA_UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "401"));
		monitor.probe();
		clock.advance(Duration.ofMinutes(30));
		monitor.probe();
		assertThat(monitor.streaks().get(dying.getId()).failedProbes()).isEqualTo(2);

		org.mockito.Mockito.doReturn(List.of()).when(jira).listProjectIssueTypes("old-token", "cloud-old", "10000");
		monitor.probe();
		assertThat(monitor.streaks()).isEmpty();
		verify(failover, never()).failover(any(), any(), any());
	}

	@Test
	void whenEverySourceFailsTogetherNothingMoves() {
		dyingAnswers(new IntegrationException(IntegrationErrorCode.JIRA_UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "401"));
		when(tokens.accessToken(standby)).thenThrow(
				new IntegrationException(IntegrationErrorCode.JIRA_TOKEN_REFRESH_FAILED, HttpStatus.BAD_GATEWAY, "refresh"));

		for (int i = 0; i < 6; i++) {
			monitor.probe();
			clock.advance(Duration.ofMinutes(30));
		}

		verify(failover, never()).failover(any(), any(), any());
	}

	@Test
	void aProjectWithASingleSourceIsNeverProbedAndTheSwitchTurnsItAllOff() {
		when(integrations.findByConnectionStatus(IntegrationStatus.ACTIVE)).thenReturn(List.of(dying));
		monitor.probe();
		verifyNoInteractions(tokens, jira, failover);

		JiraSourceHealthMonitor off = new JiraSourceHealthMonitor(integrations, tokens, jira, failover, clock, false, Duration.ofHours(1), 3);
		off.probe();
		verify(integrations, never()).findByConnectionStatus(IntegrationStatus.REVOKED);
		verifyNoInteractions(tokens, jira, failover);
	}

	@Test
	void aSkippedFailoverKeepsTheStreakSoTheNextRoundTriesAgain() {
		dyingAnswers(new IntegrationException(IntegrationErrorCode.INTEGRATION_REVOKED, HttpStatus.FORBIDDEN, "revoked"));
		when(failover.failover(any(), any(), any())).thenReturn(new Result(Outcome.SKIPPED, "NO_TEAM_LEADER", 0));

		for (int i = 0; i < 4; i++) {
			monitor.probe();
			clock.advance(Duration.ofMinutes(30));
		}

		verify(failover, org.mockito.Mockito.atLeastOnce()).failover(eq(dying.getId()), eq(standby.getId()), any());
		assertThat(monitor.streaks()).containsKey(dying.getId());
	}

	@Test
	void onlyGoneAnswersCountAsDead() {
		assertThat(health(new IntegrationException(IntegrationErrorCode.INTEGRATION_FORBIDDEN, HttpStatus.FORBIDDEN, "x")))
				.isEqualTo(JiraSourceHealthMonitor.Health.DEAD);
		assertThat(health(new IntegrationException(IntegrationErrorCode.INTEGRATION_UNAVAILABLE, HttpStatus.NOT_FOUND, "x")))
				.isEqualTo(JiraSourceHealthMonitor.Health.DEAD);
		assertThat(health(new IntegrationException(IntegrationErrorCode.JIRA_TOKEN_REFRESH_FAILED, HttpStatus.BAD_GATEWAY, "x")))
				.isEqualTo(JiraSourceHealthMonitor.Health.DEAD);
		assertThat(health(new IntegrationException(IntegrationErrorCode.INTEGRATION_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT, "x")))
				.isEqualTo(JiraSourceHealthMonitor.Health.UNKNOWN);
		assertThat(health(new IllegalStateException("network"))).isEqualTo(JiraSourceHealthMonitor.Health.UNKNOWN);
	}

	// ------------------------------------------------------------------ fixtures

	private JiraSourceHealthMonitor.Health health(RuntimeException failure) {
		when(jira.listProjectIssueTypes("old-token", "cloud-old", "10000")).thenThrow(failure);
		JiraSourceHealthMonitor.Health health = monitor.check(dying);
		org.mockito.Mockito.reset(jira);
		return health;
	}

	private void dyingAnswers(RuntimeException failure) {
		org.mockito.Mockito.doThrow(failure).when(jira).listProjectIssueTypes("old-token", "cloud-old", "10000");
	}

	private JiraIntegration source(String cloudId, String jiraProjectId) {
		JiraIntegration source = new JiraIntegration();
		source.setId(UUID.randomUUID());
		source.setProject(project);
		source.setCloudId(cloudId);
		source.setJiraProjectId(jiraProjectId);
		source.setConnectionStatus(IntegrationStatus.ACTIVE);
		return source;
	}

	private static final class MutableClock extends Clock {
		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
