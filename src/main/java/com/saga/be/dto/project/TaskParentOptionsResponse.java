package com.saga.be.dto.project;

import java.util.List;

public record TaskParentOptionsResponse(List<TaskParentOptionItem> items, int page, int size, long total) {}
