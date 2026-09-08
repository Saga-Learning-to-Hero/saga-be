package com.saga.be.dto.team;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ReplaceTeamLeaderRequest(@NotNull UUID teamMemberId) {}
