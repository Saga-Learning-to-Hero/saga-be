package com.saga.be.dto.academic;

import java.util.List;

public record AcademicClassPageResponse(List<AcademicClassResponse> items, int page, int size, long total) {}
