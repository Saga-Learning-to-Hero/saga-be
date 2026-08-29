package com.saga.be.dto.academic;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record SyllabusStructureRequest(
		@NotNull List<@Valid LearningOutcomeInput> learningOutcomes,
		List<@Valid LearningUnitInput> learningUnits,
		@NotNull List<@Valid PhaseInput> phases) {}
