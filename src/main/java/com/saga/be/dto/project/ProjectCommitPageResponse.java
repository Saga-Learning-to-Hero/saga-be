package com.saga.be.dto.project;

import java.util.List;

public record ProjectCommitPageResponse(List<ProjectCommitResponse> items, int page, int size, long total) {}
