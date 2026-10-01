package com.saga.be.service.delay;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.entity.enums.DelayCaseEnums.LeaderDecision;
import com.saga.be.entity.enums.DelayCaseEnums.Verification;
import com.saga.be.entity.enums.DelayCaseStatus;
import com.saga.be.entity.enums.DelayCauseCategory;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.service.delay.DelayCaseRules.BlockerFacts;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class DelayCaseRulesTest {

	private static final LocalDateTime DUE = LocalDateTime.of(2026, 10, 4, 0, 0);

	private static DelaySignals signals(boolean dueChanged, boolean spUp, boolean reassigned) {
		return new DelaySignals(LocalDate.of(2026, 10, 4), DUE.minusDays(10), false, 0, null, null, 0, null, null, 0,
				dueChanged, spUp, reassigned, 0);
	}

	@Test
	void blockerStillOpenOrFinishedOnOrAfterTheDueDaySupportsTheClaim() {
		assertThat(DelayCaseRules.verify(DelayCauseCategory.BLOCKED_BY_TASK, signals(false, false, false),
				new BlockerFacts("SAGA-12", TaskStatus.IN_PROGRESS, null), DUE).verification())
				.isEqualTo(Verification.CONSISTENT);
		assertThat(DelayCaseRules.verify(DelayCauseCategory.BLOCKED_BY_TASK, signals(false, false, false),
				new BlockerFacts("SAGA-12", TaskStatus.DONE, DUE.plusHours(15)), DUE).verification())
				.isEqualTo(Verification.CONSISTENT);
		assertThat(DelayCaseRules.verify(DelayCauseCategory.BLOCKED_BY_TASK, signals(false, false, false),
				new BlockerFacts("SAGA-12", TaskStatus.DONE, DUE.plusDays(4)), DUE).note())
				.contains("SAGA-12").contains("08/10/2026");
	}

	@Test
	void blockerDoneBeforeTheDueDayContradictsTheClaim() {
		DelayCaseRules.Check check = DelayCaseRules.verify(DelayCauseCategory.BLOCKED_BY_TASK, signals(false, false, false),
				new BlockerFacts("SAGA-12", TaskStatus.DONE, DUE.minusDays(2)), DUE);

		assertThat(check.verification()).isEqualTo(Verification.MISMATCH);
		assertThat(check.note()).contains("02/10/2026").contains("04/10/2026");
		assertThat(DelayCaseRules.verify(DelayCauseCategory.BLOCKED_BY_TASK, signals(false, false, false), null, DUE)
				.verification()).isEqualTo(Verification.MISMATCH);
		assertThat(DelayCaseRules.verify(DelayCauseCategory.BLOCKED_BY_TASK, signals(false, false, false),
				new BlockerFacts("SAGA-12", TaskStatus.DONE, null), DUE).verification())
				.isEqualTo(Verification.UNVERIFIABLE);
	}

	@Test
	void historyBackedCausesAreConsistentOnlyWhenTheHistoryShowsIt() {
		assertThat(DelayCaseRules.verify(DelayCauseCategory.SCOPE_CHANGED, signals(false, true, false), null, DUE).verification())
				.isEqualTo(Verification.CONSISTENT);
		assertThat(DelayCaseRules.verify(DelayCauseCategory.SCOPE_CHANGED, signals(false, false, false), null, DUE).verification())
				.isEqualTo(Verification.UNVERIFIABLE);
		assertThat(DelayCaseRules.verify(DelayCauseCategory.SCHEDULE_CHANGED, signals(true, false, false), null, DUE).verification())
				.isEqualTo(Verification.CONSISTENT);
		assertThat(DelayCaseRules.verify(DelayCauseCategory.REASSIGNED_LATE, signals(false, false, true), null, DUE).verification())
				.isEqualTo(Verification.CONSISTENT);
		assertThat(DelayCaseRules.verify(DelayCauseCategory.REASSIGNED_LATE, signals(false, false, false), null, DUE).verification())
				.isEqualTo(Verification.UNVERIFIABLE);
	}

	@Test
	void unprovableAndSubjectiveCauses() {
		for (DelayCauseCategory category : new DelayCauseCategory[] {
			DelayCauseCategory.TECHNICAL_ISSUE, DelayCauseCategory.PERSONAL_EMERGENCY, DelayCauseCategory.OTHER
		}) {
			assertThat(DelayCaseRules.verify(category, signals(false, false, false), null, DUE).verification())
					.isEqualTo(Verification.UNVERIFIABLE);
		}
		for (DelayCauseCategory category : new DelayCauseCategory[] {
			DelayCauseCategory.STARTED_LATE, DelayCauseCategory.UNDERESTIMATED, DelayCauseCategory.NO_PROGRESS
		}) {
			assertThat(DelayCaseRules.verify(category, signals(false, false, false), null, DUE).verification())
					.isEqualTo(Verification.CONSISTENT);
		}
	}

	@Test
	void theLeaderGoesFirstUnlessTheAssigneeIsTheLeader() {
		assertThat(DelayCaseRules.afterExplanation(false)).isEqualTo(DelayCaseStatus.AWAITING_LEADER);
		assertThat(DelayCaseRules.afterExplanation(true)).isEqualTo(DelayCaseStatus.AWAITING_LECTURER);
	}

	@Test
	void onlyAnAgreedUncontradictedSubjectiveCauseClosesWithoutTheLecturer() {
		assertThat(DelayCaseRules.afterLeader(DelayCauseCategory.STARTED_LATE, Verification.CONSISTENT, LeaderDecision.AGREE))
				.isEqualTo(DelayCaseStatus.CLOSED_SUBJECTIVE);
		// leader disagrees
		assertThat(DelayCaseRules.afterLeader(DelayCauseCategory.STARTED_LATE, Verification.CONSISTENT, LeaderDecision.DISAGREE))
				.isEqualTo(DelayCaseStatus.AWAITING_LECTURER);
		// objective causes, OTHER, and contradicted explanations always reach the lecturer
		assertThat(DelayCaseRules.afterLeader(DelayCauseCategory.PERSONAL_EMERGENCY, Verification.UNVERIFIABLE, LeaderDecision.AGREE))
				.isEqualTo(DelayCaseStatus.AWAITING_LECTURER);
		assertThat(DelayCaseRules.afterLeader(DelayCauseCategory.OTHER, Verification.UNVERIFIABLE, LeaderDecision.AGREE))
				.isEqualTo(DelayCaseStatus.AWAITING_LECTURER);
		assertThat(DelayCaseRules.afterLeader(DelayCauseCategory.NO_PROGRESS, Verification.MISMATCH, LeaderDecision.AGREE))
				.isEqualTo(DelayCaseStatus.AWAITING_LECTURER);
	}

	@Test
	void categoriesAreGroupedAndTheUnprovableOnesNeedANote() {
		assertThat(DelayCauseCategory.values()).hasSize(10);
		assertThat(DelayCauseCategory.OTHER.group()).isEqualTo(DelayCauseCategory.Group.OTHER);
		assertThat(DelayCauseCategory.BLOCKED_BY_TASK.group()).isEqualTo(DelayCauseCategory.Group.OBJECTIVE);
		assertThat(DelayCauseCategory.NO_PROGRESS.group()).isEqualTo(DelayCauseCategory.Group.SUBJECTIVE);
		assertThat(DelayCauseCategory.OTHER.requiresNote()).isTrue();
		assertThat(DelayCauseCategory.PERSONAL_EMERGENCY.requiresNote()).isTrue();
		assertThat(DelayCauseCategory.BLOCKED_BY_TASK.requiresNote()).isFalse();
	}
}
