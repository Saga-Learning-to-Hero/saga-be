package com.saga.be.dto.academic;

import java.util.List;

public record LecturerDirectoryPageResponse(
		List<LecturerDirectoryResponse> items, int page, int size, long total) {}
