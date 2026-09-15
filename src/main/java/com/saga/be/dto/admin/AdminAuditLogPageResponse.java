package com.saga.be.dto.admin;

import java.util.List;

public record AdminAuditLogPageResponse(List<AdminAuditLogResponse> items, int page, int size, long total) {}
