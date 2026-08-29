package com.saga.be.dto.academic;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SetActiveSemesterRequest(@NotNull UUID semesterId) {}
