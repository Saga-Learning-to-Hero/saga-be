package com.saga.be.dto.project;

import java.util.UUID;

public record TaskParentOptionItem(
		UUID id, String title, String status, UUID parentTaskId, String externalKey) {}
