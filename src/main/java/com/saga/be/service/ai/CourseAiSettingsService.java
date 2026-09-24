package com.saga.be.service.ai;

import com.saga.be.ai.AiProviderBinding;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.ai.CourseAiFallbackBinding;
import com.saga.be.entity.ai.CourseAiSettings;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.CourseAiFallbackBindingRepository;
import com.saga.be.repository.CourseAiSettingsRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Absence of a row IS the safe default (automation OFF, platform fallback OFF, legacy OpenAI
 * binding, course fallback OFF) -- read paths never materialize a row just to answer a GET, so a
 * course a lecturer never touched costs nothing. Pure DB; never calls a provider. */
@Service @Profile("!test")
public class CourseAiSettingsService {
	public static final int MAX_FALLBACK_BINDINGS = 3;

	private final CourseAiSettingsRepository settings;
	private final CourseAiFallbackBindingRepository fallbacks;
	private final AiModelCatalog catalog;

	public CourseAiSettingsService(CourseAiSettingsRepository settings, CourseAiFallbackBindingRepository fallbacks, AiModelCatalog catalog) {
		this.settings = settings; this.fallbacks = fallbacks; this.catalog = catalog;
	}

	/** {@code primaryBinding}/{@code secondaryBinding} null = legacy (OpenAI credential, platform
	 * model). {@code fallbackBindings} is only populated while {@code fallbackEnabled}. */
	public record Settings(boolean automationEnabled, boolean allowPlatformFallback, AiProviderBinding primaryBinding, boolean fallbackEnabled, List<AiProviderBinding> fallbackBindings, AiProviderBinding secondaryBinding) {
		static final Settings SAFE_DEFAULT = new Settings(false, false);
		public Settings(boolean automationEnabled, boolean allowPlatformFallback) { this(automationEnabled, allowPlatformFallback, null, false, List.of(), null); }
		public Settings { fallbackBindings = fallbackBindings == null ? List.of() : List.copyOf(fallbackBindings); }
	}

	/** Raw, not-yet-validated binding input from the API. */
	public record BindingInput(String provider, String modelId) {}

	@Transactional(readOnly = true)
	public Settings get(java.util.UUID courseId) {
		return settings.findByCourse_Id(courseId).map(this::toSettings).orElse(Settings.SAFE_DEFAULT);
	}

	@Transactional
	public Settings update(Course course, boolean automationEnabled, boolean allowPlatformFallback) {
		CourseAiSettings row = rowFor(course);
		row.setAutomationEnabled(automationEnabled);
		row.setAllowPlatformFallback(allowPlatformFallback);
		return toSettings(settings.save(row));
	}

	/** Full replace of the provider bindings, validated against the server-side catalog before
	 * anything is written. Never touches automation/platform-fallback flags or any credential. */
	@Transactional
	public Settings updateBindings(Course course, BindingInput primary, boolean fallbackEnabled, List<BindingInput> fallbackInputs, BindingInput secondary) {
		AiProviderBinding primaryBinding = primary == null ? null : catalog.requireBindable(primary.provider(), primary.modelId());
		AiProviderBinding secondaryBinding = secondary == null ? null : catalog.requireBindable(secondary.provider(), secondary.modelId());
		List<BindingInput> rawFallbacks = fallbackInputs == null ? List.of() : fallbackInputs;
		if (rawFallbacks.size() > MAX_FALLBACK_BINDINGS) throw invalid("At most " + MAX_FALLBACK_BINDINGS + " fallback bindings are allowed.");
		List<AiProviderBinding> fallbackBindings = new ArrayList<>();
		Set<AiProviderBinding> seen = new HashSet<>();
		for (BindingInput input : rawFallbacks) {
			if (input == null) throw invalid("Fallback binding must not be null.");
			AiProviderBinding binding = catalog.requireBindable(input.provider(), input.modelId());
			if (binding.equals(primaryBinding)) throw invalid("The primary binding must not be repeated in the fallback chain.");
			if (!seen.add(binding)) throw invalid("Duplicate fallback binding.");
			fallbackBindings.add(binding);
		}
		if (fallbackEnabled && primaryBinding == null) throw invalid("A primary binding is required before enabling fallback.");
		if (fallbackEnabled && fallbackBindings.isEmpty()) throw invalid("Fallback is enabled but no fallback binding was given.");

		CourseAiSettings row = rowFor(course);
		row.setPrimaryProvider(primaryBinding == null ? null : primaryBinding.provider());
		row.setPrimaryModelId(primaryBinding == null ? null : primaryBinding.modelId());
		row.setFallbackEnabled(fallbackEnabled);
		row.setSecondaryProvider(secondaryBinding == null ? null : secondaryBinding.provider());
		row.setSecondaryModelId(secondaryBinding == null ? null : secondaryBinding.modelId());
		row = settings.save(row);
		fallbacks.deleteAllForCourse(course.getId());
		int order = 1;
		for (AiProviderBinding binding : fallbackBindings) {
			CourseAiFallbackBinding entry = new CourseAiFallbackBinding();
			entry.setCourse(course); entry.setAttemptOrder(order++); entry.setProvider(binding.provider()); entry.setModelId(binding.modelId());
			fallbacks.save(entry);
		}
		return new Settings(row.isAutomationEnabled(), row.isAllowPlatformFallback(), primaryBinding, fallbackEnabled, fallbackEnabled ? fallbackBindings : List.of(), secondaryBinding);
	}

	/** The stored chain regardless of the enabled flag, so a lecturer who disables fallback can
	 * still see (and re-enable) what they configured. */
	@Transactional(readOnly = true)
	public List<AiProviderBinding> storedFallbackBindings(java.util.UUID courseId) {
		return fallbacks.findByCourse_IdOrderByAttemptOrderAsc(courseId).stream().map(b -> new AiProviderBinding(b.getProvider(), b.getModelId())).toList();
	}

	private CourseAiSettings rowFor(Course course) {
		return settings.findByCourse_Id(course.getId()).orElseGet(() -> { CourseAiSettings created = new CourseAiSettings(); created.setCourse(course); return created; });
	}

	private Settings toSettings(CourseAiSettings row) {
		AiProviderBinding primary = row.getPrimaryProvider() == null || row.getPrimaryModelId() == null ? null : new AiProviderBinding(row.getPrimaryProvider(), row.getPrimaryModelId());
		AiProviderBinding secondary = row.getSecondaryProvider() == null || row.getSecondaryModelId() == null ? null : new AiProviderBinding(row.getSecondaryProvider(), row.getSecondaryModelId());
		// The chain is only read when it can matter, keeping the common (fallback OFF) path at one query.
		List<AiProviderBinding> chain = row.isFallbackEnabled() && row.getCourse() != null ? storedFallbackBindings(row.getCourse().getId()) : List.of();
		return new Settings(row.isAutomationEnabled(), row.isAllowPlatformFallback(), primary, row.isFallbackEnabled(), chain, secondary);
	}

	private static IntegrationException invalid(String message) {
		return new IntegrationException(IntegrationErrorCode.AI_BINDING_INVALID, HttpStatus.BAD_REQUEST, message);
	}
}
