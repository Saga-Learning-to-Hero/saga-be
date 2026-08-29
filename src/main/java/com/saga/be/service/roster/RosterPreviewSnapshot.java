package com.saga.be.service.roster;

import com.saga.be.dto.roster.RosterPreviewRow;
import java.util.List;
import java.util.UUID;

public record RosterPreviewSnapshot(UUID adminUserId, UUID courseId, String classCode, List<RosterPreviewRow> rows) {}
