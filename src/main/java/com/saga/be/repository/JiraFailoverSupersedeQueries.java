package com.saga.be.repository;

import com.saga.be.entity.enums.JiraFailoverItemStatus;
import com.saga.be.entity.jira.JiraTaskFailoverItem;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Domain helpers for failover supersede / outbound-claim queries.
 *
 * <p>Successfully superseded = {@code SUCCEEDED} with non-null {@code targetTask}.
 *
 * <p>Outbound claim (DB-enforced via V25 {@code outbound_claim_task_id}) = statuses in
 * {@link JiraTaskFailoverItem#CLAIM_HOLDING_STATUSES}.
 */
public final class JiraFailoverSupersedeQueries {

	/** Non-SUCCEEDED claim holders that block a new cutover for the same source Task. */
	public static final Collection<JiraFailoverItemStatus> IN_FLIGHT_CLAIM_STATUSES = List.of(
			JiraFailoverItemStatus.PENDING,
			JiraFailoverItemStatus.CREATING,
			JiraFailoverItemStatus.REMOTE_OUTCOME_UNKNOWN,
			JiraFailoverItemStatus.REMOTE_BOUND);

	public static final Collection<JiraFailoverItemStatus> CLAIM_HOLDING_STATUSES =
			JiraTaskFailoverItem.CLAIM_HOLDING_STATUSES;

	private JiraFailoverSupersedeQueries() {}

	public static boolean isSuccessfullySuperseded(JiraTaskFailoverItemRepository items, UUID sourceTaskId) {
		return items.isSuccessfullySuperseded(sourceTaskId);
	}

	public static boolean isOutboundClaimed(JiraTaskFailoverItemRepository items, UUID sourceTaskId) {
		return items.existsOutboundClaim(sourceTaskId);
	}

	public static List<JiraTaskFailoverItem> findInFlight(
			JiraTaskFailoverItemRepository items, UUID sourceTaskId) {
		return items.findInFlightBySourceTaskId(sourceTaskId);
	}
}
