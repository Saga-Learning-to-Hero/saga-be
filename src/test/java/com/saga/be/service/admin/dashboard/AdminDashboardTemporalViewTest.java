package com.saga.be.service.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.admin.dashboard.AdminDashboardAvailableSemesterResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardCachedPayload;
import com.saga.be.dto.admin.dashboard.AdminDashboardKpisResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardSelectedSemesterResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardMissingService;
import com.saga.be.dto.admin.dashboard.AdminDashboardUnconnectedTeamResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardWeeklyPointResponse;
import com.saga.be.dto.admin.dashboard.SemesterPeriodStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminDashboardTemporalViewTest {

	private static final UUID SEMESTER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
	private static final Instant WEEK2 = Instant.parse("2026-09-14T23:00:00Z");
	private static final Instant WEEK3 = Instant.parse("2026-09-15T00:00:00Z");
	private static final Instant BEFORE_START = Instant.parse("2026-08-31T23:00:00Z");
	private static final Instant AT_START = Instant.parse("2026-09-01T00:00:00Z");
	private static final Instant END_EXCLUSIVE = Instant.parse("2026-12-16T00:00:00Z");

	@Test
	void weekBoundaryFlipsCurrentWeekWithoutTouchingAggregatesOrGeneration() {
		AdminDashboardCachedPayload cached = payload(WEEK2, 2, true);
		AdminDashboardCachedPayload after =
				AdminDashboardTemporalView.decorate(cached, Clock.fixed(WEEK3, ZoneOffset.UTC));
		assertThat(after.generation()).isEqualTo("gen-frozen");
		assertThat(after.kpis().totalStudents()).isEqualTo(9);
		assertThat(after.weeklyTimeline().get(1).commits()).isEqualTo(20);
		assertThat(after.weeklyTimeline().get(1).tasksCompleted()).isEqualTo(4);
		assertThat(after.weeklyTimeline().get(1).traceabilityRate()).isEqualTo(50.0d);
		assertThat(after.selectedSemester().currentWeekIndex()).isEqualTo(3);
		assertThat(after.weeklyTimeline().get(1).isCurrentWeek()).isFalse();
		assertThat(after.weeklyTimeline().get(2).isCurrentWeek()).isTrue();
	}

	@Test
	void semesterStartFlipsPeriodStatusAndWeek1() {
		AdminDashboardCachedPayload cached = payload(BEFORE_START, null, false);
		assertThat(cached.availableSemesters().getFirst().periodStatus()).isEqualTo(SemesterPeriodStatus.UPCOMING);
		AdminDashboardCachedPayload after =
				AdminDashboardTemporalView.decorate(cached, Clock.fixed(AT_START, ZoneOffset.UTC));
		assertThat(after.availableSemesters().getFirst().periodStatus()).isEqualTo(SemesterPeriodStatus.IN_PROGRESS);
		assertThat(after.selectedSemester().currentWeekIndex()).isEqualTo(1);
		assertThat(after.weeklyTimeline().getFirst().isCurrentWeek()).isTrue();
		assertThat(after.generation()).isEqualTo("gen-frozen");
	}

	@Test
	void endExclusiveClearsCurrentWeekAndCompletesPeriod() {
		AdminDashboardCachedPayload cached = payload(WEEK3, 3, true);
		AdminDashboardCachedPayload after =
				AdminDashboardTemporalView.decorate(cached, Clock.fixed(END_EXCLUSIVE, ZoneOffset.UTC));
		assertThat(after.selectedSemester().currentWeekIndex()).isNull();
		assertThat(after.weeklyTimeline()).allSatisfy(point -> assertThat(point.isCurrentWeek()).isFalse());
		assertThat(after.availableSemesters().getFirst().periodStatus()).isEqualTo(SemesterPeriodStatus.COMPLETED);
		assertThat(after.weeklyTimeline().get(2).commits()).isEqualTo(30);
	}

	@Test
	void daysSinceCreatedRecomputesAcrossMidnightWithoutChangingAlertMembership() {
		UUID teamId = UUID.fromString("44444444-4444-4444-8444-444444444444");
		AdminDashboardUnconnectedTeamResponse stale = new AdminDashboardUnconnectedTeamResponse(
				teamId,
				7,
				"Old",
				"SWP",
				"Lecturer",
				"lecturer@fe.edu.vn",
				AdminDashboardMissingService.PROJECT,
				LocalDateTime.of(2026, 9, 1, 8, 0),
				0);
		AdminDashboardCachedPayload cached = payload(WEEK2, 2, true, List.of(stale));
		AdminDashboardCachedPayload day0 =
				AdminDashboardTemporalView.decorate(cached, Clock.fixed(AT_START, ZoneOffset.UTC));
		assertThat(day0.unconnectedTeamsAlert().getFirst().daysSinceCreated()).isZero();
		AdminDashboardCachedPayload day7 = AdminDashboardTemporalView.decorate(
				cached, Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC));
		assertThat(day7.unconnectedTeamsAlert().getFirst().daysSinceCreated()).isEqualTo(7);
		AdminDashboardCachedPayload day8 = AdminDashboardTemporalView.decorate(
				cached, Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC));
		assertThat(day8.unconnectedTeamsAlert().getFirst().daysSinceCreated()).isEqualTo(8);
		assertThat(day8.unconnectedTeamsAlert()).hasSize(1);
		assertThat(day8.unconnectedTeamsAlert().getFirst().teamId()).isEqualTo(teamId);
		assertThat(day8.generation()).isEqualTo("gen-frozen");
		Instant nearUtcMidnight = Instant.parse("2026-09-08T23:30:00Z");
		assertThat(AdminDashboardTemporalView.decorate(cached, Clock.fixed(nearUtcMidnight, ZoneOffset.UTC))
						.unconnectedTeamsAlert()
						.getFirst()
						.daysSinceCreated())
				.isEqualTo(7);
		assertThat(AdminDashboardTemporalView.decorate(cached, Clock.fixed(nearUtcMidnight, ZoneOffset.UTC))
						.unconnectedTeamsAlert()
						.getFirst()
						.daysSinceCreated())
				.isEqualTo(7);
		assertThat(AdminDashboardTemporalView.decorate(cached, Clock.fixed(nearUtcMidnight, ZoneId.of("Asia/Tokyo")))
						.unconnectedTeamsAlert()
						.getFirst()
						.daysSinceCreated())
				.isEqualTo(8);
	}

	@Test
	void configuredUtcClockIsIndependentOfADifferentHostZoneAtTheSameInstant() {
		Instant nearUtcMidnight = Instant.parse("2026-09-07T23:30:00Z");
		Clock utc = Clock.fixed(nearUtcMidnight, ZoneOffset.UTC);
		Clock tokyo = Clock.fixed(nearUtcMidnight, ZoneId.of("Asia/Tokyo"));
		AdminDashboardCachedPayload cached = payload(WEEK2, 2, true);
		AdminDashboardCachedPayload hostA = AdminDashboardTemporalView.decorate(cached, utc);
		AdminDashboardCachedPayload hostB =
				AdminDashboardTemporalView.decorate(cached, Clock.fixed(nearUtcMidnight, ZoneOffset.UTC));
		assertThat(hostA.selectedSemester().currentWeekIndex()).isEqualTo(1);
		assertThat(hostB.selectedSemester().currentWeekIndex()).isEqualTo(1);
		assertThat(hostA.weeklyTimeline().getFirst().isCurrentWeek()).isTrue();
		assertThat(AdminDashboardTemporalView.decorate(cached, tokyo).selectedSemester().currentWeekIndex())
				.isEqualTo(2);
	}

	private static AdminDashboardCachedPayload payload(Instant cachedAt, Integer staleWeek, boolean inProgress) {
		return payload(cachedAt, staleWeek, inProgress, List.of());
	}

	private static AdminDashboardCachedPayload payload(
			Instant cachedAt,
			Integer staleWeek,
			boolean inProgress,
			List<AdminDashboardUnconnectedTeamResponse> alerts) {
		return new AdminDashboardCachedPayload(
				"gen-frozen",
				cachedAt,
				new AdminDashboardSelectedSemesterResponse(
						SEMESTER_ID,
						"FA26",
						"Fall",
						LocalDate.of(2026, 9, 1),
						LocalDate.of(2026, 12, 15),
						16,
						staleWeek,
						true),
				List.of(new AdminDashboardAvailableSemesterResponse(
						SEMESTER_ID,
						"FA26",
						"Fall",
						LocalDate.of(2026, 9, 1),
						LocalDate.of(2026, 12, 15),
						true,
						inProgress ? SemesterPeriodStatus.IN_PROGRESS : SemesterPeriodStatus.UPCOMING)),
				new AdminDashboardKpisResponse(9, null, null, 0, 0, 0, null, 0, 0, null),
				List.of(
						point(1, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7), staleWeek != null && staleWeek == 1, 10),
						point(2, LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 14), staleWeek != null && staleWeek == 2, 20),
						point(3, LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 21), staleWeek != null && staleWeek == 3, 30)),
				alerts);
	}

	private static AdminDashboardWeeklyPointResponse point(
			int index, LocalDate start, LocalDate end, boolean current, long commits) {
		return new AdminDashboardWeeklyPointResponse(
				index,
				"Tuần %02d".formatted(index),
				start,
				end,
				current,
				commits,
				4,
				50.0d);
	}
}
