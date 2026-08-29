package com.saga.be.dto.academic;

import java.util.List;
import java.util.UUID;

public record DeliverableResponse(
		UUID id,
		String code,
		String name,
		String description,
		int orderIndex,
		List<String> learningOutcomeCodes) {}
