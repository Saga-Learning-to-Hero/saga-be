package com.saga.be.dto.academic;

import java.util.List;

public record CoursePageResponse(List<CourseResponse> items, int page, int size, long total) {}
