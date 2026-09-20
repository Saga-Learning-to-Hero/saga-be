package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.entity.enums.JiraFailoverItemStatus;
import com.saga.be.entity.jira.JiraTaskFailoverItem;
import com.saga.be.entity.jira.Task;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JiraFailoverItemSupersedeTest {

	@Test
	void isSuccessfullySupersededRequiresSucceededAndNonNullTarget() {
		UUID sourceId = UUID.randomUUID();
		Task source = new Task();
		source.setId(sourceId);
		Task target = new Task();
		target.setId(UUID.randomUUID());

		JiraTaskFailoverItem succeededWithTarget = new JiraTaskFailoverItem();
		succeededWithTarget.setSourceTask(source);
		succeededWithTarget.setTargetTask(target);
		succeededWithTarget.setStatus(JiraFailoverItemStatus.SUCCEEDED);

		JiraTaskFailoverItem succeededWithoutTarget = new JiraTaskFailoverItem();
		succeededWithoutTarget.setSourceTask(source);
		succeededWithoutTarget.setTargetTask(null);
		succeededWithoutTarget.setStatus(JiraFailoverItemStatus.SUCCEEDED);

		JiraTaskFailoverItem failed = new JiraTaskFailoverItem();
		failed.setSourceTask(source);
		failed.setTargetTask(target);
		failed.setStatus(JiraFailoverItemStatus.FAILED);

		assertThat(isSuperseded(List.of(succeededWithTarget))).isTrue();
		assertThat(isSuperseded(List.of(succeededWithoutTarget))).isFalse();
		assertThat(isSuperseded(List.of(failed))).isFalse();
		assertThat(isSuperseded(List.of())).isFalse();
	}

	@Test
	void inFlightClaimStatusesIncludePendingAndExcludeSucceeded() {
		assertThat(JiraFailoverSupersedeQueries.IN_FLIGHT_CLAIM_STATUSES)
				.containsExactlyInAnyOrder(
						JiraFailoverItemStatus.PENDING,
						JiraFailoverItemStatus.CREATING,
						JiraFailoverItemStatus.REMOTE_OUTCOME_UNKNOWN,
						JiraFailoverItemStatus.REMOTE_BOUND);
		assertThat(JiraFailoverSupersedeQueries.CLAIM_HOLDING_STATUSES)
				.contains(JiraFailoverItemStatus.SUCCEEDED)
				.containsAll(JiraFailoverSupersedeQueries.IN_FLIGHT_CLAIM_STATUSES);
	}

	private static boolean isSuperseded(List<JiraTaskFailoverItem> items) {
		return items.stream()
				.anyMatch(item -> item.getStatus() == JiraFailoverItemStatus.SUCCEEDED
						&& item.getTargetTask() != null);
	}
}
