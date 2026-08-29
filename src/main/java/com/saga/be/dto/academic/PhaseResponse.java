package com.saga.be.dto.academic;

import java.util.List;
import java.util.UUID;

public record PhaseResponse(
		UUID id,
		String code,
		String name,
		String description,
		int orderIndex,
		List<String> learningOutcomeCodes,
		List<ActivityResponse> activities,
		List<DeliverableResponse> deliverables) {}
