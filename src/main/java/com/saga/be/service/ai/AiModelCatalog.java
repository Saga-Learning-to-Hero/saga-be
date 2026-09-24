package com.saga.be.service.ai;

import com.saga.be.ai.AiProviderBinding;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiProvider;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The server-side allowlist of models a course may bind: the SAGA PRODUCT catalog, deliberately a
 * subset of what each provider's API supports (a provider model missing here, e.g.
 * gemini-3.7-flash, may be perfectly valid upstream -- SAGA just does not offer it). Nothing outside
 * this list can be persisted as a new binding. A small, explicit legacy runtime set may execute
 * previously persisted bindings without making them selectable again. Historical run/decision
 * provenance naming a since-removed model is never rewritten. Pure data.
 *
 * <p>{@code freeTierEligible} is informational only: external free tiers, their limits, and their
 * data-use terms are set by the provider and may change at any time.
 */
@Component
public class AiModelCatalog {
	private static final Set<AiAnalysisType> ALL_TYPES = Set.copyOf(EnumSet.allOf(AiAnalysisType.class));

	public record Model(AiProvider provider, String modelId, String displayName, boolean freeTierEligible, boolean supportsStructuredOutput, boolean recommendedForAutomation, Set<AiAnalysisType> supportedAnalysisTypes) {
		public boolean supportsEveryAnalysisType() { return supportsStructuredOutput && supportedAnalysisTypes.containsAll(ALL_TYPES); }
	}

	public static final List<Model> DEFAULT_MODELS = List.of(
			new Model(AiProvider.OPENAI, "gpt-5.6-luna", "GPT-5.6 Luna", false, true, true, ALL_TYPES),
			new Model(AiProvider.OPENAI, "gpt-5.6-terra", "GPT-5.6 Terra", false, true, true, ALL_TYPES),
			new Model(AiProvider.OPENAI, "gpt-5.6-sol", "GPT-5.6 Sol", false, true, true, ALL_TYPES),
			new Model(AiProvider.GEMINI, "gemini-3.8-flash", "Gemini 3.8 Flash", true, true, true, ALL_TYPES),
			new Model(AiProvider.GEMINI, "gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", true, true, true, ALL_TYPES),
			// Preview model (no stable gemini-3.1-pro code exists): paid tier only, and a preview may
			// change or be retired on short notice, so it is not recommended for unattended automation.
			new Model(AiProvider.GEMINI, "gemini-3.1-pro-preview", "Gemini 3.1 Pro", false, true, false, ALL_TYPES),
			// Routes each request to some currently-free upstream model: output quality, limits
			// and data terms vary per request, so it is never recommended for unattended automation.
			new Model(AiProvider.OPENROUTER, "openrouter/free", "OpenRouter Free Models Router", true, true, false, ALL_TYPES));

	/**
	 * Provider/model pairs accepted only while executing an already-persisted binding. They are
	 * deliberately excluded from {@link #DEFAULT_MODELS}: a lecturer cannot select or save one
	 * again once it has left SAGA's product catalog.
	 */
	public static final List<Model> LEGACY_RUNTIME_COMPATIBLE_MODELS = List.of(
			new Model(AiProvider.GEMINI, "gemini-3.7-flash", "Gemini 3.7 Flash", true, true, false, ALL_TYPES));

	private final List<Model> models;

	public AiModelCatalog() { this(DEFAULT_MODELS); }

	AiModelCatalog(List<Model> models) { this.models = List.copyOf(models); }

	public List<Model> models() { return models; }

	public Optional<Model> find(AiProvider provider, String modelId) {
		return models.stream().filter(m -> m.provider() == provider && m.modelId().equals(modelId)).findFirst();
	}

	/** Validates a stored binding before it is used for runtime dispatch. */
	public AiProviderBinding requireRuntimeCompatible(AiProviderBinding binding) {
		if (binding == null) throw new IntegrationException(IntegrationErrorCode.AI_BINDING_INVALID, HttpStatus.BAD_REQUEST, "AI binding is required.");
		Model model = find(binding.provider(), binding.modelId())
				.or(() -> LEGACY_RUNTIME_COMPATIBLE_MODELS.stream().filter(m -> m.provider() == binding.provider() && m.modelId().equals(binding.modelId())).findFirst())
				.orElseThrow(() -> unsupportedModel(binding.provider(), binding.modelId()));
		if (!model.supportsEveryAnalysisType()) throw new IntegrationException(IntegrationErrorCode.AI_MODEL_CAPABILITY_UNSUPPORTED, HttpStatus.BAD_REQUEST, "Model does not support structured output for every AI analysis.");
		return binding;
	}

	/** Validates one binding as a lecturer submitted it (raw strings), or throws a safe 400. */
	public AiProviderBinding requireBindable(String rawProvider, String rawModelId) {
		AiProvider provider = AiProvider.parse(rawProvider).orElseThrow(() -> new IntegrationException(IntegrationErrorCode.AI_PROVIDER_NOT_SUPPORTED, HttpStatus.BAD_REQUEST, "Unsupported AI provider."));
		if (rawModelId == null || rawModelId.isBlank()) throw new IntegrationException(IntegrationErrorCode.AI_BINDING_INVALID, HttpStatus.BAD_REQUEST, "modelId is required.");
		String modelId = rawModelId.trim();
		Model model = find(provider, modelId).orElseThrow(() -> unsupportedModel(provider, modelId));
		if (!model.supportsEveryAnalysisType()) throw new IntegrationException(IntegrationErrorCode.AI_MODEL_CAPABILITY_UNSUPPORTED, HttpStatus.BAD_REQUEST, "Model does not support structured output for every AI analysis.");
		return new AiProviderBinding(provider, modelId);
	}

	private IntegrationException unsupportedModel(AiProvider provider, String modelId) {
		boolean knownUnderAnotherProvider = models.stream().anyMatch(m -> m.modelId().equals(modelId))
				|| LEGACY_RUNTIME_COMPATIBLE_MODELS.stream().anyMatch(m -> m.modelId().equals(modelId));
		return new IntegrationException(IntegrationErrorCode.AI_MODEL_NOT_SUPPORTED, HttpStatus.BAD_REQUEST,
				knownUnderAnotherProvider ? "Model does not belong to the selected provider." : "Unsupported AI model.");
	}
}
