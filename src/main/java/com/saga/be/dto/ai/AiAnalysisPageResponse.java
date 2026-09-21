package com.saga.be.dto.ai;

import java.util.List;

public record AiAnalysisPageResponse(List<AiAnalysisResponse> items, int page, int size, long totalElements) {}
