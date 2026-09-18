package com.saga.be.dto.project;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
public sealed interface TaskEvidenceResponse permits TaskEvidenceGroupedResponse, TaskEvidencePageResponse {}
