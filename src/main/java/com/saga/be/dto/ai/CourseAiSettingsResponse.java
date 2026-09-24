package com.saga.be.dto.ai;

import java.util.List;

/** {@code primaryBinding}/{@code secondaryBinding} null = legacy (OpenAI course credential with
 * the platform model). {@code fallbackBindings} is the stored ordered chain, returned even while
 * {@code fallbackEnabled} is false so it can be re-enabled without re-entry. */
public record CourseAiSettingsResponse(boolean automationEnabled, boolean allowPlatformFallback, AiProviderBindingDto primaryBinding, boolean fallbackEnabled, List<AiProviderBindingDto> fallbackBindings, AiProviderBindingDto secondaryBinding) {}
