package com.saga.be.dto.ai;

import java.util.List;

/** Full replace of the course provider bindings. Omitted/null primary or secondary binding resets
 * that role to the legacy OpenAI behaviour; {@code fallbackBindings} is ordered (max 3). */
public record CourseAiBindingsUpdateRequest(AiProviderBindingDto primaryBinding, Boolean fallbackEnabled, List<AiProviderBindingDto> fallbackBindings, AiProviderBindingDto secondaryBinding) {}
