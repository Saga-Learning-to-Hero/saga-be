package com.saga.be.repository;

import java.time.LocalDateTime;
import java.util.UUID;

/** Id-only projection for native parent validation. */
public interface TaskParentIdentity {

	UUID getId();

	UUID getProjectId();

	LocalDateTime getDeletedAt();
}
