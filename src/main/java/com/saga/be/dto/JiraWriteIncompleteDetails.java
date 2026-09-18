package com.saga.be.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Recovery context when a Jira write committed but a local follow-up did not.")
public record JiraWriteIncompleteDetails(
		@Schema(description = "Committed local Task id") UUID taskId,
		@Schema(description = "True when the Jira mutation already succeeded") boolean providerWriteApplied,
		@Schema(description = "True when native parent_task_id was persisted") boolean nativeParentApplied,
		@Schema(description = "Machine-readable next step", example = "PATCH_NATIVE_PARENT")
				String recoveryAction) {

	public static final String PATCH_NATIVE_PARENT = "PATCH_NATIVE_PARENT";

	public static JiraWriteIncompleteDetails nativeParentNotApplied(UUID taskId) {
		return new JiraWriteIncompleteDetails(taskId, true, false, PATCH_NATIVE_PARENT);
	}
}
