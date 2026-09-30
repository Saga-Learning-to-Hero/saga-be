package com.saga.be.service.sprint;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SprintPeriodsTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
	private final JiraIntegration siteA = site(IntegrationStatus.ACTIVE);
	private final JiraIntegration siteB = site(IntegrationStatus.ACTIVE);

	@Test
	void sprintsHandingOverOnTheSameDayDoNotOverlap() {
		Sprint sprint4 = sprint(siteA, "closed", day(9, 6), day(9, 20), day(9, 20));
		Sprint sprint5 = sprint(siteB, "active", day(9, 20), day(10, 3), null);

		assertThat(SprintPeriods.overlaps(List.of(sprint4, sprint5), TODAY, true)).isEmpty();
	}

	@Test
	void sprintsOfTwoSitesRunningTogetherOverlap() {
		Sprint siteASprint = sprint(siteA, "active", day(9, 20), day(10, 3), null);
		Sprint siteBSprint = sprint(siteB, "active", day(9, 25), day(10, 8), null);

		List<SprintPeriods.Overlap> overlaps = SprintPeriods.overlaps(List.of(siteBSprint, siteASprint), TODAY, true);

		assertThat(overlaps).hasSize(1);
		assertThat(overlaps.getFirst().first()).isSameAs(siteASprint);
		assertThat(overlaps.getFirst().second()).isSameAs(siteBSprint);
	}

	@Test
	void closedSprintEndsOnItsRealCompleteDateNotThePlannedEnd() {
		Sprint completedEarly = sprint(siteA, "closed", day(9, 1), day(9, 20), day(9, 12));
		Sprint next = sprint(siteB, "closed", day(9, 12), day(9, 26), day(9, 26));

		assertThat(SprintPeriods.overlaps(List.of(completedEarly, next), TODAY, true)).isEmpty();
	}

	@Test
	void activeSprintOnALiveSiteIsStillRunningPastItsPlannedEnd() {
		Sprint overrunning = sprint(siteA, "active", day(9, 1), day(9, 14), null);
		Sprint startedToday = sprint(siteB, "active", TODAY.atStartOfDay(), day(10, 14), null);

		assertThat(SprintPeriods.overlaps(List.of(overrunning, startedToday), TODAY, true)).hasSize(1);
	}

	@Test
	void activeSprintLeftOnADeadSiteStopsAtItsPlannedEnd() {
		Sprint stuckOnDeadSite = sprint(site(IntegrationStatus.REVOKED), "active", day(9, 1), day(9, 14), null);
		Sprint newSite = sprint(siteB, "active", day(9, 20), day(10, 3), null);

		assertThat(SprintPeriods.overlaps(List.of(stuckOnDeadSite, newSite), TODAY, true)).isEmpty();
	}

	@Test
	void sprintWithoutStartOrUsableEndHasNoPeriod() {
		assertThat(SprintPeriods.of(sprint(siteA, "future", null, day(10, 3), null), TODAY)).isNull();
		assertThat(SprintPeriods.of(sprint(siteA, "future", day(10, 3), null, null), TODAY)).isNull();
		assertThat(SprintPeriods.of(sprint(siteA, "future", day(10, 3), day(10, 3), null), TODAY)).isNull();
	}

	@Test
	void startedOnlySkipsPlannedSprintsButFullCheckIncludesThem() {
		Sprint running = sprint(siteA, "active", day(9, 20), day(10, 3), null);
		Sprint planned = sprint(siteB, "future", day(10, 1), day(10, 14), null);

		assertThat(SprintPeriods.overlaps(List.of(running, planned), TODAY, true)).isEmpty();
		assertThat(SprintPeriods.overlaps(List.of(running, planned), TODAY, false)).hasSize(1);
	}

	@Test
	void pairKeyIsTheSameWhicheverSprintComesFirst() {
		Sprint a = sprint(siteA, "active", day(9, 20), day(10, 3), null);
		Sprint b = sprint(siteB, "active", day(9, 25), day(10, 8), null);

		assertThat(new SprintPeriods.Overlap(a, b).pairKey()).isEqualTo(new SprintPeriods.Overlap(b, a).pairKey());
	}

	@Test
	void firstConflictIgnoresTheSprintItselfAndDeletedSprints() {
		Sprint self = sprint(siteA, "future", day(10, 4), day(10, 17), null);
		Sprint deleted = sprint(siteB, "future", day(10, 5), day(10, 18), null);
		deleted.setDeletedAt(LocalDateTime.of(2026, 9, 29, 0, 0));
		SprintPeriods.Period candidate = new SprintPeriods.Period(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 17));

		assertThat(SprintPeriods.firstConflict(candidate, List.of(self, deleted), self.getId(), TODAY)).isEmpty();
	}

	@Test
	void firstConflictReturnsTheEarliestOverlappingSprint() {
		Sprint later = sprint(siteB, "future", day(10, 10), day(10, 24), null);
		Sprint earlier = sprint(siteA, "active", day(9, 20), day(10, 3), null);
		SprintPeriods.Period candidate = new SprintPeriods.Period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15));

		assertThat(SprintPeriods.firstConflict(candidate, List.of(later, earlier), null, TODAY)).contains(earlier);
	}

	@Test
	void requestDatesAcceptJiraInstantsLocalDateTimesAndPlainDates() {
		assertThat(SprintPeriods.parseRequestDate("2026-10-04T00:00:00.000+0700")).isEqualTo(LocalDateTime.of(2026, 10, 4, 0, 0));
		assertThat(SprintPeriods.parseRequestDate("2026-10-04T09:30:00")).isEqualTo(LocalDateTime.of(2026, 10, 4, 9, 30));
		assertThat(SprintPeriods.parseRequestDate("2026-10-04")).isEqualTo(LocalDateTime.of(2026, 10, 4, 0, 0));
		assertThat(SprintPeriods.parseRequestDate(" ")).isNull();
		assertThat(SprintPeriods.parseRequestDate("not-a-date")).isNull();
	}

	private static LocalDateTime day(int month, int dayOfMonth) {
		return LocalDateTime.of(2026, month, dayOfMonth, 9, 0);
	}

	private static JiraIntegration site(IntegrationStatus status) {
		JiraIntegration site = new JiraIntegration();
		site.setId(UUID.randomUUID());
		site.setConnectionStatus(status);
		return site;
	}

	private static Sprint sprint(
			JiraIntegration site, String state, LocalDateTime start, LocalDateTime end, LocalDateTime complete) {
		Sprint sprint = new Sprint();
		sprint.setId(UUID.randomUUID());
		sprint.setJiraIntegration(site);
		sprint.setName("Sprint " + state);
		sprint.setState(state);
		sprint.setStartDate(start);
		sprint.setEndDate(end);
		sprint.setCompleteDate(complete);
		return sprint;
	}
}
