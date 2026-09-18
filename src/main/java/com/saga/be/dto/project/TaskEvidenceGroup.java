package com.saga.be.dto.project;

import java.util.List;

public record TaskEvidenceGroup(long total, List<TaskEvidenceItem> items) {}
