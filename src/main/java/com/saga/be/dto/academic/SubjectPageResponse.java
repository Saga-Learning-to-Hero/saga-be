package com.saga.be.dto.academic;

import java.util.List;

public record SubjectPageResponse(List<SubjectResponse> items, int page, int size, long total) {}
