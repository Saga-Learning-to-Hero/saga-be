package com.saga.be.service.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.admin.dashboard.AdminDashboardWeeklyPointResponse;
import com.saga.be.service.admin.dashboard.AdminDashboardSemesterWeeks.WeekSlice;
import com.saga.be.service.admin.dashboard.AdminDashboardWeeklyTimeline.CommitActivity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminDashboardWeeklyTimelineTest {

	@Test
	void bucketsCommitsAndCompletionsWithoutPerWeekSql() {
		List<WeekSlice> slices = AdminDashboardSemesterWeeks.slices(
				LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 14), LocalDateTime.of(2026, 9, 3, 0, 0));
		UUID linked = UUID.fromString("11111111-1111-4111-8111-111111111111");
		UUID unlinked = UUID.fromString("22222222-2222-4222-8222-222222222222");
		List<AdminDashboardWeeklyPointResponse> points = AdminDashboardWeeklyTimeline.bucket(
				slices,
				List.of(
						new CommitActivity(linked, LocalDateTime.of(2026, 9, 2, 8, 0), true),
						new CommitActivity(unlinked, LocalDateTime.of(2026, 9, 2, 9, 0), false),
						new CommitActivity(UUID.randomUUID(), LocalDateTime.of(2026, 9, 2, 10, 0), true),
						new CommitActivity(UUID.randomUUID(), LocalDateTime.of(2026, 9, 8, 0, 0), false)),
				List.of(LocalDateTime.of(2026, 9, 3, 0, 0), LocalDateTime.of(2026, 9, 14, 23, 59, 59)));
		assertThat(points).hasSize(2);
		assertThat(points.get(0).commits()).isEqualTo(3);
		assertThat(points.get(0).tasksCompleted()).isEqualTo(1);
		assertThat(points.get(0).traceabilityRate()).isEqualTo(AdminDashboardQueryService.ratioPercent(2, 3));
		assertThat(points.get(0).isCurrentWeek()).isTrue();
		assertThat(points.get(1).commits()).isEqualTo(1);
		assertThat(points.get(1).tasksCompleted()).isEqualTo(1);
		assertThat(points.get(1).traceabilityRate()).isEqualTo(0.0d);
		assertThat(points.get(1).weekLabel()).isEqualTo("Tuần 02");
	}

	@Test
	void zeroDenominatorYieldsNullRate_andExactBoundaryGoesToNextSlice() {
		List<WeekSlice> slices = AdminDashboardSemesterWeeks.slices(
				LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 14), LocalDateTime.of(2026, 8, 1, 0, 0));
		List<AdminDashboardWeeklyPointResponse> empty = AdminDashboardWeeklyTimeline.bucket(slices, List.of(), List.of());
		assertThat(empty.get(0).traceabilityRate()).isNull();
		assertThat(empty.get(0).commits()).isZero();

		UUID id = UUID.randomUUID();
		List<AdminDashboardWeeklyPointResponse> atEndExclusive = AdminDashboardWeeklyTimeline.bucket(
				slices,
				List.of(new CommitActivity(id, LocalDateTime.of(2026, 9, 8, 0, 0), true)),
				List.of());
		assertThat(atEndExclusive.get(0).commits()).isZero();
		assertThat(atEndExclusive.get(1).commits()).isEqualTo(1);
		assertThat(atEndExclusive.get(1).traceabilityRate()).isEqualTo(100.0d);
	}

	@Test
	void activitiesDedupByIdAndSkipNullTimestamp() {
		UUID id = UUID.fromString("33333333-3333-4333-8333-333333333333");
		List<CommitActivity> activities = AdminDashboardWeeklyTimeline.activities(
				List.of(
						new Object[] {id, LocalDateTime.of(2026, 9, 2, 0, 0)},
						new Object[] {id, LocalDateTime.of(2026, 9, 9, 0, 0)},
						new Object[] {UUID.randomUUID(), null}),
				Set.of(id));
		assertThat(activities).hasSize(1);
		assertThat(activities.getFirst().linked()).isTrue();
	}
}
