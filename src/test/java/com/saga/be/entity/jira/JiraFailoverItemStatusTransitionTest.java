package com.saga.be.entity.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.entity.enums.JiraFailoverItemStatus;
import org.junit.jupiter.api.Test;

class JiraFailoverItemStatusTransitionTest {

	@Test
	void remoteOutcomeUnknownCannotReturnToPendingOrCreating() {
		JiraTaskFailoverItem item = new JiraTaskFailoverItem();
		item.setStatus(JiraFailoverItemStatus.REMOTE_OUTCOME_UNKNOWN);

		assertThat(item.canTransitionTo(JiraFailoverItemStatus.PENDING)).isFalse();
		assertThat(item.canTransitionTo(JiraFailoverItemStatus.CREATING)).isFalse();
		assertThatThrownBy(() -> item.transitionTo(JiraFailoverItemStatus.PENDING))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("REMOTE_OUTCOME_UNKNOWN");
	}

	@Test
	void remoteOutcomeUnknownCannotAbandonOrFailWithoutReconciliation() {
		JiraTaskFailoverItem item = new JiraTaskFailoverItem();
		item.setStatus(JiraFailoverItemStatus.REMOTE_OUTCOME_UNKNOWN);

		assertThat(item.canTransitionTo(JiraFailoverItemStatus.ABANDONED)).isFalse();
		assertThat(item.canTransitionTo(JiraFailoverItemStatus.FAILED)).isFalse();
		assertThat(item.canTransitionTo(JiraFailoverItemStatus.REMOTE_BOUND)).isTrue();
		assertThat(item.canTransitionTo(JiraFailoverItemStatus.SUCCEEDED)).isTrue();
	}

	@Test
	void remoteBoundOnlyCompletesToSucceeded() {
		JiraTaskFailoverItem item = new JiraTaskFailoverItem();
		item.setStatus(JiraFailoverItemStatus.REMOTE_BOUND);

		assertThat(item.canTransitionTo(JiraFailoverItemStatus.SUCCEEDED)).isTrue();
		assertThat(item.canTransitionTo(JiraFailoverItemStatus.FAILED)).isFalse();
		assertThat(item.canTransitionTo(JiraFailoverItemStatus.ABANDONED)).isFalse();
	}

	@Test
	void pendingMayReleaseClaimViaSkipOrAbandonForRunCancellation() {
		JiraTaskFailoverItem item = new JiraTaskFailoverItem();
		item.setStatus(JiraFailoverItemStatus.PENDING);

		assertThat(item.canTransitionTo(JiraFailoverItemStatus.SKIPPED)).isTrue();
		assertThat(item.canTransitionTo(JiraFailoverItemStatus.ABANDONED)).isTrue();
		assertThat(item.canTransitionTo(JiraFailoverItemStatus.CREATING)).isTrue();
	}

	@Test
	void creatingMayMoveToRemoteOutcomeUnknown() {
		JiraTaskFailoverItem item = new JiraTaskFailoverItem();
		item.setStatus(JiraFailoverItemStatus.CREATING);

		assertThat(item.canTransitionTo(JiraFailoverItemStatus.REMOTE_OUTCOME_UNKNOWN)).isTrue();
		item.transitionTo(JiraFailoverItemStatus.REMOTE_OUTCOME_UNKNOWN);
		assertThat(item.getStatus()).isEqualTo(JiraFailoverItemStatus.REMOTE_OUTCOME_UNKNOWN);
	}

	@Test
	void claimHoldingStatusesMatchGeneratedColumnSemantics() {
		assertThat(JiraTaskFailoverItem.CLAIM_HOLDING_STATUSES)
				.containsExactlyInAnyOrder(
						JiraFailoverItemStatus.PENDING,
						JiraFailoverItemStatus.CREATING,
						JiraFailoverItemStatus.REMOTE_OUTCOME_UNKNOWN,
						JiraFailoverItemStatus.REMOTE_BOUND,
						JiraFailoverItemStatus.SUCCEEDED);
		assertThat(JiraTaskFailoverItem.CLAIM_RELEASING_STATUSES)
				.containsExactlyInAnyOrder(
						JiraFailoverItemStatus.FAILED,
						JiraFailoverItemStatus.SKIPPED,
						JiraFailoverItemStatus.ABANDONED);
	}
}
