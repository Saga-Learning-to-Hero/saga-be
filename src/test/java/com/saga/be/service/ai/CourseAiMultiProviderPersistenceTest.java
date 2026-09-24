package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.ai.AiProviderBinding;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.ai.AiAnalysisProviderDecision;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.ai.CourseAiSettings;
import com.saga.be.entity.enums.*;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.*;
import jakarta.persistence.EntityManager;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Real H2 + real services: several provider credentials per course and role coexist, secrets are
 * never stored in plaintext, bindings are validated against the server-side catalog, and the
 * resolver only ever pairs a binding with the same provider's credential of the same role.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("tx-it")
@TestPropertySource(
		properties = {
			"spring.flyway.enabled=false",
			"spring.jpa.hibernate.ddl-auto=create-drop",
			"spring.jpa.open-in-view=false",
			"spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=CHAR",
			"saga.auth.bootstrap-admin.enabled=false"
		})
class CourseAiMultiProviderPersistenceTest {

	@SpringBootConfiguration
	@EnableAutoConfiguration(
			excludeName = {
				"org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
				"org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration",
				"org.springframework.boot.session.autoconfigure.SessionAutoConfiguration",
				"org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
				"org.springframework.boot.neo4j.autoconfigure.Neo4jAutoConfiguration",
				"org.springframework.boot.data.neo4j.autoconfigure.DataNeo4jAutoConfiguration",
				"org.springframework.boot.data.neo4j.autoconfigure.DataNeo4jRepositoriesAutoConfiguration",
				"org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
				"org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration",
				"org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration"
			})
	@EntityScan(basePackages = "com.saga.be.entity")
	@EnableJpaRepositories(basePackages = "com.saga.be.repository")
	static class TxSlice {}

	private static final String MASTER_KEY = Base64.getEncoder().encodeToString("M".repeat(32).getBytes());
	private static final String TRANSPORT_KEY = Base64.getEncoder().encodeToString("T".repeat(32).getBytes());
	private static final String OPENAI_KEY = "sk-openai-raw-secret-000111";
	private static final String GEMINI_KEY = "AIza-gemini-raw-secret-222333";
	private static final String OPENROUTER_KEY = "sk-or-openrouter-raw-secret-444555";

	@Autowired private SemesterRepository semesters;
	@Autowired private AcademicClassRepository academicClasses;
	@Autowired private SubjectRepository subjects;
	@Autowired private CourseRepository courses;
	@Autowired private ProjectRepository projects;
	@Autowired private AiAnalysisRunRepository runs;
	@Autowired private AiAnalysisProviderDecisionRepository decisions;
	@Autowired private CourseAiProviderCredentialRepository credentialRows;
	@Autowired private CourseAiSettingsRepository settingsRows;
	@Autowired private CourseAiFallbackBindingRepository fallbackRows;
	@Autowired private EntityManager entityManager;

	private CourseAiCredentialService credentials;
	private CourseAiSettingsService settings;
	private AiCredentialResolver resolver;
	private Course course;
	private Course otherCourse;

	@BeforeEach
	void setUp() {
		credentials = new CourseAiCredentialService(credentialRows, new AiCredentialCipher(MASTER_KEY));
		settings = new CourseAiSettingsService(settingsRows, fallbackRows, new AiModelCatalog());
		resolver = new AiCredentialResolver(credentialRows, settings, credentials, new AiCredentialTransportCipher(TRANSPORT_KEY), List.of(), new AiModelCatalog());
		Semester semester = semesters.save(semester());
		AcademicClass academicClass = academicClasses.save(academicClass(semester));
		Subject subject = subjects.save(subject());
		course = courses.save(course(academicClass, subject, semester));
		otherCourse = courses.save(course(academicClass, subjects.save(subject()), semester));
	}

	// ---- credentials ----

	@Test
	void openAiGeminiAndOpenRouterCredentialsCoexistForTheSameCourseAndRole() {
		var openai = credentials.save(course, AiProviderRole.PRIMARY, AiProvider.OPENAI, OPENAI_KEY, null);
		var gemini = credentials.save(course, AiProviderRole.PRIMARY, AiProvider.GEMINI, GEMINI_KEY, null);
		var openrouter = credentials.save(course, AiProviderRole.PRIMARY, AiProvider.OPENROUTER, OPENROUTER_KEY, null);
		entityManager.flush();

		assertThat(List.of(openai, gemini, openrouter)).allSatisfy(meta -> {
			assertThat(meta.configured()).isTrue();
			assertThat(meta.status()).isEqualTo(AiCredentialStatus.UNVERIFIED); // saving never calls a provider
			assertThat(meta.createdAt()).isNotNull();
		});
		assertThat(credentials.list(course)).extracting(CourseAiCredentialService.SafeMetadata::provider)
				.containsExactlyInAnyOrder(AiProvider.OPENAI, AiProvider.GEMINI, AiProvider.OPENROUTER);
		assertThat(gemini.lastFour()).isEqualTo("2333");

		// Replacing the Gemini key rewrites the Gemini row only.
		credentials.save(course, AiProviderRole.PRIMARY, AiProvider.GEMINI, "AIza-rotated-gemini-9999", null);
		entityManager.flush();
		assertThat(credentialRows.findByCourse_IdOrderByProviderRoleAscProviderAsc(course.getId())).hasSize(3);
		assertThat(credentials.safeMetadata(course, AiProviderRole.PRIMARY, AiProvider.OPENAI).lastFour()).isEqualTo("0111");
	}

	@Test
	void plaintextKeysAreAbsentFromEveryStoredColumnAndFromSafeMetadata() {
		credentials.save(course, AiProviderRole.PRIMARY, AiProvider.OPENAI, OPENAI_KEY, null);
		credentials.save(course, AiProviderRole.PRIMARY, AiProvider.GEMINI, GEMINI_KEY, null);
		credentials.save(course, AiProviderRole.SECONDARY, AiProvider.OPENROUTER, OPENROUTER_KEY, null);
		entityManager.flush();

		@SuppressWarnings("unchecked")
		List<Object[]> rows = entityManager.createNativeQuery("select * from ai_course_provider_credential").getResultList();
		String stored = rows.stream().map(java.util.Arrays::deepToString).reduce("", String::concat);
		assertThat(rows).hasSize(3);
		assertThat(stored).doesNotContain(OPENAI_KEY, GEMINI_KEY, OPENROUTER_KEY, "raw-secret");
		String metadata = credentials.list(course).toString();
		assertThat(metadata).doesNotContain(OPENAI_KEY, GEMINI_KEY, OPENROUTER_KEY, "raw-secret");
		credentialRows.findAll().forEach(row -> assertThat(metadata).doesNotContain(row.getEncryptedSecret(), row.getEncryptionNonce(), row.getFingerprint()));
	}

	@Test
	void revokingOneProviderLeavesTheOtherProvidersUsable() {
		credentials.save(course, AiProviderRole.PRIMARY, AiProvider.OPENAI, OPENAI_KEY, null);
		credentials.save(course, AiProviderRole.PRIMARY, AiProvider.GEMINI, GEMINI_KEY, null);
		credentials.revoke(course, AiProviderRole.PRIMARY, AiProvider.GEMINI);
		entityManager.flush();

		assertThat(resolver.usableCourseCredential(course.getId(), AiProviderRole.PRIMARY, AiProvider.GEMINI)).isEmpty();
		assertThat(resolver.usableCourseCredential(course.getId(), AiProviderRole.PRIMARY, AiProvider.OPENAI)).isPresent();
	}

	// ---- bindings validation (server-side catalog) ----

	@Test
	void validBindingsArePersistedAndReadBackInOrder() {
		var saved = settings.updateBindings(course, input("GEMINI", "gemini-3.8-flash"), true,
				List.of(input("OPENROUTER", "openrouter/free"), input("OPENAI", "gpt-5.6-luna")), input("OPENAI", "gpt-5.6-terra"));
		entityManager.flush();
		entityManager.clear();

		var read = settings.get(course.getId());
		assertThat(read).isEqualTo(saved);
		assertThat(read.primaryBinding()).isEqualTo(new AiProviderBinding(AiProvider.GEMINI, "gemini-3.8-flash"));
		assertThat(read.fallbackBindings()).containsExactly(new AiProviderBinding(AiProvider.OPENROUTER, "openrouter/free"), new AiProviderBinding(AiProvider.OPENAI, "gpt-5.6-luna"));
		assertThat(read.secondaryBinding()).isEqualTo(new AiProviderBinding(AiProvider.OPENAI, "gpt-5.6-terra"));

		// Disabling fallback keeps the chain stored (for re-enable) but it is no longer active.
		settings.updateBindings(course, input("gemini", "gemini-3.8-flash"), false, List.of(input("OPENROUTER", "openrouter/free")), null);
		entityManager.flush();
		entityManager.clear();
		assertThat(settings.get(course.getId()).fallbackBindings()).isEmpty();
		assertThat(settings.storedFallbackBindings(course.getId())).containsExactly(new AiProviderBinding(AiProvider.OPENROUTER, "openrouter/free"));
		assertThat(resolver.primaryFallbackChain(course.getId())).isEmpty();
		assertThat(settings.get(course.getId()).secondaryBinding()).isNull();
	}

	@Test
	void arbitraryModelUnknownProviderAndMismatchedPairsAreRejectedBeforeAnyWrite() {
		assertRejected(() -> settings.updateBindings(course, input("OPENAI", "gpt-4o"), false, List.of(), null), IntegrationErrorCode.AI_MODEL_NOT_SUPPORTED);
		assertRejected(() -> settings.updateBindings(course, input("ANTHROPIC", "claude"), false, List.of(), null), IntegrationErrorCode.AI_PROVIDER_NOT_SUPPORTED);
		assertRejected(() -> settings.updateBindings(course, input("GEMINI", "gpt-5.6-sol"), false, List.of(), null), IntegrationErrorCode.AI_MODEL_NOT_SUPPORTED);
		assertRejected(() -> settings.updateBindings(course, input("OPENROUTER", "openai/gpt-5.6-sol"), false, List.of(), null), IntegrationErrorCode.AI_MODEL_NOT_SUPPORTED);
		assertRejected(() -> settings.updateBindings(course, input("OPENAI", "gpt-5.6-sol"), false, List.of(), input("OPENAI", "")), IntegrationErrorCode.AI_BINDING_INVALID);
		entityManager.flush();
		assertThat(settingsRows.findByCourse_Id(course.getId())).isEmpty();
	}

	@Test
	void duplicateFallbackPrimaryRepeatedInChainOversizedChainAndFallbackWithoutPrimaryAreRejected() {
		var primary = input("GEMINI", "gemini-3.8-flash");
		assertRejected(() -> settings.updateBindings(course, primary, true, List.of(input("OPENROUTER", "openrouter/free"), input("openrouter", "openrouter/free")), null), IntegrationErrorCode.AI_BINDING_INVALID);
		assertRejected(() -> settings.updateBindings(course, primary, true, List.of(input("GEMINI", "gemini-3.8-flash")), null), IntegrationErrorCode.AI_BINDING_INVALID);
		assertRejected(() -> settings.updateBindings(course, primary, true, List.of(input("OPENAI", "gpt-5.6-luna"), input("OPENAI", "gpt-5.6-terra"), input("OPENAI", "gpt-5.6-sol"), input("GEMINI", "gemini-3.7-flash")), null), IntegrationErrorCode.AI_BINDING_INVALID);
		assertRejected(() -> settings.updateBindings(course, null, true, List.of(input("OPENAI", "gpt-5.6-luna")), null), IntegrationErrorCode.AI_BINDING_INVALID);
		assertRejected(() -> settings.updateBindings(course, primary, true, List.of(), null), IntegrationErrorCode.AI_BINDING_INVALID);
	}

	@Test
	void modelWithoutStructuredOutputCapabilityIsRejected() {
		var limited = new AiModelCatalog(List.of(new AiModelCatalog.Model(AiProvider.GEMINI, "gemini-text-only", "Text only", true, false, false, Set.of(AiAnalysisType.PROGRESS_NARRATIVE))));
		var limitedSettings = new CourseAiSettingsService(settingsRows, fallbackRows, limited);
		assertRejected(() -> limitedSettings.updateBindings(course, input("GEMINI", "gemini-text-only"), false, List.of(), null), IntegrationErrorCode.AI_MODEL_CAPABILITY_UNSUPPORTED);
	}

	@Test
	void catalogMarksFreeTierAndAutomationMetadataExactlyAsSpecified() {
		Map<String, AiModelCatalog.Model> byId = new java.util.HashMap<>();
		new AiModelCatalog().models().forEach(m -> byId.put(m.modelId(), m));
		assertThat(byId.keySet()).containsExactlyInAnyOrder(
				"gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.6-sol",
				"gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.1-pro-preview",
				"openrouter/free");
		assertThat(byId.values()).allSatisfy(m -> assertThat(m.supportsEveryAnalysisType()).isTrue());
		// Informational only, per each provider's current pricing page: the OpenAI API has no free
		// tier; Gemini 3.1 Pro Preview is paid-tier only; the OpenRouter router is free by design.
		assertThat(List.of("gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.6-sol", "gemini-3.1-pro-preview")).allSatisfy(id -> assertThat(byId.get(id).freeTierEligible()).isFalse());
		assertThat(List.of("gemini-3.8-flash", "gemini-3.5-flash-lite", "openrouter/free")).allSatisfy(id -> assertThat(byId.get(id).freeTierEligible()).isTrue());
		assertThat(byId.get("openrouter/free").recommendedForAutomation()).isFalse();
		assertThat(byId.get("gemini-3.1-pro-preview").recommendedForAutomation()).isFalse();
		assertThat(byId.get("gemini-3.8-flash").displayName()).isEqualTo("Gemini 3.8 Flash");
		assertThat(byId.get("gemini-3.5-flash-lite").displayName()).isEqualTo("Gemini 3.5 Flash-Lite");
		assertThat(byId.get("gemini-3.1-pro-preview").displayName()).isEqualTo("Gemini 3.1 Pro");
	}

	@org.junit.jupiter.params.ParameterizedTest(name = "{0} {1} is bindable")
	@org.junit.jupiter.params.provider.CsvSource({
		"OPENAI, gpt-5.6-sol", "OPENAI, gpt-5.6-terra", "OPENAI, gpt-5.6-luna",
		"GEMINI, gemini-3.8-flash", "GEMINI, gemini-3.5-flash-lite", "GEMINI, gemini-3.1-pro-preview",
		"OPENROUTER, openrouter/free"
	})
	void everyProductCatalogModelIsBindableUnderItsOwnProvider(String provider, String modelId) {
		var saved = settings.updateBindings(course, input(provider, modelId), false, List.of(), input(provider, modelId));
		assertThat(saved.primaryBinding()).isEqualTo(new AiProviderBinding(AiProvider.valueOf(provider), modelId));
		assertThat(saved.secondaryBinding()).isEqualTo(new AiProviderBinding(AiProvider.valueOf(provider), modelId));
	}

	@org.junit.jupiter.params.ParameterizedTest(name = "{0} {1} is rejected")
	@org.junit.jupiter.params.provider.CsvSource({
		// Removed from the SAGA product catalog (it may still exist at the provider).
		"GEMINI, gemini-3.7-flash",
		// Invented / alias ids that SAGA does not expose.
		"GEMINI, gemini-3.1-pro", "GEMINI, gemini-3.8", "OPENAI, gpt-5.6", "OPENAI, gpt-6-sol",
		// A real catalog model bound under the wrong provider.
		"OPENAI, gemini-3.8-flash", "GEMINI, gpt-5.6-terra", "OPENROUTER, gemini-3.5-flash-lite", "GEMINI, openrouter/free"
	})
	void modelsOutsideTheProductCatalogOrUnderTheWrongProviderAreRejected(String provider, String modelId) {
		assertRejected(() -> settings.updateBindings(course, input(provider, modelId), false, List.of(), null), IntegrationErrorCode.AI_MODEL_NOT_SUPPORTED);
		assertRejected(() -> settings.updateBindings(course, input("OPENAI", "gpt-5.6-sol"), true, List.of(input(provider, modelId)), null), IntegrationErrorCode.AI_MODEL_NOT_SUPPORTED);
		assertRejected(() -> settings.updateBindings(course, null, false, List.of(), input(provider, modelId)), IntegrationErrorCode.AI_MODEL_NOT_SUPPORTED);
	}

	@Test
	void persistedLegacyGeminiBindingRemainsRuntimeCompatibleWithoutBecomingSelectable() {
		CourseAiSettings legacy = new CourseAiSettings();
		legacy.setCourse(course);
		legacy.setPrimaryProvider(AiProvider.GEMINI);
		legacy.setPrimaryModelId("gemini-3.7-flash");
		settingsRows.saveAndFlush(legacy);
		credentials.save(course, AiProviderRole.PRIMARY, AiProvider.GEMINI, GEMINI_KEY, null);

		assertRejected(() -> settings.updateBindings(course, input("GEMINI", "gemini-3.7-flash"), false, List.of(), null), IntegrationErrorCode.AI_MODEL_NOT_SUPPORTED);
		var resolution = resolver.resolve(course.getId(), AiAnalysisType.PROGRESS_NARRATIVE, AiProviderRole.PRIMARY, AiInvocationOrigin.AUTOMATION);

		assertThat(resolution.binding()).isEqualTo(new AiProviderBinding(AiProvider.GEMINI, "gemini-3.7-flash"));
		assertThat(settings.get(course.getId()).primaryBinding()).isEqualTo(new AiProviderBinding(AiProvider.GEMINI, "gemini-3.7-flash"));
		assertThat(settingsRows.findByCourse_Id(course.getId()).orElseThrow().getPrimaryModelId()).isEqualTo("gemini-3.7-flash");
	}

	@Test
	void arbitraryPersistedModelIsRejectedBeforeRuntimeDispatch() {
		CourseAiSettings invalid = new CourseAiSettings();
		invalid.setCourse(course);
		invalid.setPrimaryProvider(AiProvider.GEMINI);
		invalid.setPrimaryModelId("gemini-made-up");
		settingsRows.saveAndFlush(invalid);
		credentials.save(course, AiProviderRole.PRIMARY, AiProvider.GEMINI, GEMINI_KEY, null);

		assertRejected(() -> resolver.resolve(course.getId(), AiAnalysisType.PROGRESS_NARRATIVE, AiProviderRole.PRIMARY, AiInvocationOrigin.AUTOMATION), IntegrationErrorCode.AI_MODEL_NOT_SUPPORTED);
	}

	@Test
	void historicalProvenanceNamingARemovedModelIsNeverRewritten() {
		Project project = projects.save(project(course));
		AiAnalysisRun run = runs.save(run(project));
		AiAnalysisProviderDecision decision = new AiAnalysisProviderDecision();
		decision.setAnalysisRun(run); decision.setProviderRole(AiProviderRole.PRIMARY); decision.setProviderKey("saga-ai"); decision.setProviderConfigHash("cfg");
		decision.setModelId("gemini-3.7-flash"); decision.setAiProvider(AiProvider.GEMINI); decision.setRoute(AiProviderRoute.NORMAL); decision.setStatus(AiProviderDecisionStatus.COMPLETED);
		decision.setFallbackAttemptsJson("[{\"provider\":\"GEMINI\",\"modelId\":\"gemini-3.7-flash\",\"outcome\":\"SUCCEEDED\"}]");
		decisions.saveAndFlush(decision);
		entityManager.clear();

		var reloaded = decisions.findById(decision.getId()).orElseThrow();
		assertThat(reloaded.getModelId()).isEqualTo("gemini-3.7-flash");
		assertThat(reloaded.getFallbackAttemptsJson()).contains("gemini-3.7-flash");
	}

	// ---- resolver: a binding is only ever paired with the same provider's credential ----

	@Test
	void legacyCourseWithoutBindingsStillResolvesItsOpenAiCredentialWithTheLegacyIdentity() {
		var openai = credentials.save(course, AiProviderRole.PRIMARY, AiProvider.OPENAI, OPENAI_KEY, null);
		credentials.save(course, AiProviderRole.PRIMARY, AiProvider.GEMINI, GEMINI_KEY, null);
		entityManager.flush();

		var resolution = resolver.resolve(course.getId(), AiAnalysisType.COMMIT_INTELLIGENCE, AiProviderRole.PRIMARY, AiInvocationOrigin.AUTOMATION);

		assertThat(resolution.outcome()).isEqualTo(AiCredentialResolver.Outcome.COURSE);
		assertThat(resolution.binding()).isNull();
		assertThat(resolution.courseCredentialId()).isEqualTo(credentialRows.findByCourse_IdAndProviderRoleAndProvider(course.getId(), AiProviderRole.PRIMARY, AiProvider.OPENAI).orElseThrow().getId());
		assertThat(resolution.identityFingerprint()).isEqualTo(resolution.credentialFingerprint()); // pre multi-provider idempotency preserved
		assertThat(openai.provider()).isEqualTo(AiProvider.OPENAI);
	}

	@Test
	void boundPrimaryUsesOnlyThatProvidersCredentialAndNeverSubstitutesAnother() {
		credentials.save(course, AiProviderRole.PRIMARY, AiProvider.OPENAI, OPENAI_KEY, null);
		settings.updateBindings(course, input("GEMINI", "gemini-3.8-flash"), false, List.of(), null);
		entityManager.flush();

		// No Gemini credential: unavailable, even though a usable OpenAI credential exists.
		assertThat(resolver.resolve(course.getId(), AiAnalysisType.TASK_INTELLIGENCE, AiProviderRole.PRIMARY, AiInvocationOrigin.AUTOMATION)).isEqualTo(AiCredentialResolver.Resolution.UNAVAILABLE);

		credentials.save(course, AiProviderRole.PRIMARY, AiProvider.GEMINI, GEMINI_KEY, null);
		entityManager.flush();
		var resolution = resolver.resolve(course.getId(), AiAnalysisType.TASK_INTELLIGENCE, AiProviderRole.PRIMARY, AiInvocationOrigin.AUTOMATION);
		assertThat(resolution.outcome()).isEqualTo(AiCredentialResolver.Outcome.COURSE);
		assertThat(resolution.binding()).isEqualTo(new AiProviderBinding(AiProvider.GEMINI, "gemini-3.8-flash"));
		assertThat(resolution.courseCredentialId()).isEqualTo(credentialRows.findByCourse_IdAndProviderRoleAndProvider(course.getId(), AiProviderRole.PRIMARY, AiProvider.GEMINI).orElseThrow().getId());
		assertThat(resolution.identityFingerprint()).endsWith("@GEMINI:gemini-3.8-flash");

		AiAnalysisProviderDecision decision = new AiAnalysisProviderDecision();
		decision.setModelId("platform-model");
		AiCredentialResolver.applyBinding(decision, resolution);
		assertThat(decision.getAiProvider()).isEqualTo(AiProvider.GEMINI);
		assertThat(decision.getModelId()).isEqualTo("gemini-3.8-flash");
	}

	@Test
	void secondaryIsIndependentAndNeverReusesAPrimaryCredential() {
		credentials.save(course, AiProviderRole.PRIMARY, AiProvider.OPENAI, OPENAI_KEY, null);
		settings.updateBindings(course, input("OPENAI", "gpt-5.6-sol"), false, List.of(), input("OPENAI", "gpt-5.6-terra"));
		entityManager.flush();

		// Only a PRIMARY OpenAI key exists: SECONDARY is unavailable, never PRIMARY's key or platform.
		assertThat(resolver.resolve(course.getId(), AiAnalysisType.RISK_ANALYSIS, AiProviderRole.SECONDARY, AiInvocationOrigin.USER_REQUEST)).isEqualTo(AiCredentialResolver.Resolution.UNAVAILABLE);

		credentials.save(course, AiProviderRole.SECONDARY, AiProvider.OPENAI, "sk-secondary-key-7777", null);
		entityManager.flush();
		var secondary = resolver.resolve(course.getId(), AiAnalysisType.RISK_ANALYSIS, AiProviderRole.SECONDARY, AiInvocationOrigin.AUTOMATION);
		var primary = resolver.resolve(course.getId(), AiAnalysisType.RISK_ANALYSIS, AiProviderRole.PRIMARY, AiInvocationOrigin.AUTOMATION);
		assertThat(secondary.binding()).isEqualTo(new AiProviderBinding(AiProvider.OPENAI, "gpt-5.6-terra"));
		assertThat(secondary.courseCredentialId()).isNotEqualTo(primary.courseCredentialId());
		assertThat(secondary.credentialFingerprint()).isNotEqualTo(primary.credentialFingerprint());
	}

	@Test
	void envelopeCanOnlyBeBuiltForTheCredentialsOwnCourseAndRole() {
		credentials.save(course, AiProviderRole.PRIMARY, AiProvider.GEMINI, GEMINI_KEY, null);
		entityManager.flush();
		UUID credentialId = resolver.usableCourseCredential(course.getId(), AiProviderRole.PRIMARY, AiProvider.GEMINI).orElseThrow().id();

		assertThat(resolver.buildEnvelope(credentialId, AiProviderRole.PRIMARY, course.getId()).ciphertext()).doesNotContain(GEMINI_KEY);
		assertThatThrownBy(() -> resolver.buildEnvelope(credentialId, AiProviderRole.PRIMARY, otherCourse.getId())).isInstanceOf(AiCredentialCryptoException.class);
		assertThatThrownBy(() -> resolver.buildEnvelope(credentialId, AiProviderRole.SECONDARY, course.getId())).isInstanceOf(AiCredentialCryptoException.class);
		assertThatThrownBy(() -> resolver.buildEnvelope(null, AiProviderRole.PRIMARY, course.getId())).isInstanceOf(AiCredentialCryptoException.class);
	}

	// ---- provenance ----

	@Test
	void actualProviderModelCredentialAndAttemptsArePersistedOnThePrimaryDecision() {
		credentials.save(course, AiProviderRole.PRIMARY, AiProvider.OPENROUTER, OPENROUTER_KEY, null);
		entityManager.flush();
		var credential = resolver.usableCourseCredential(course.getId(), AiProviderRole.PRIMARY, AiProvider.OPENROUTER).orElseThrow();
		Project project = projects.save(project(course));
		AiAnalysisRun run = runs.save(run(project));
		AiAnalysisProviderDecision decision = new AiAnalysisProviderDecision();
		decision.setAnalysisRun(run); decision.setProviderRole(AiProviderRole.PRIMARY); decision.setProviderKey("saga-ai"); decision.setProviderConfigHash("cfg");
		decision.setModelId("gemini-3.8-flash"); decision.setAiProvider(AiProvider.GEMINI); decision.setRoute(AiProviderRoute.NORMAL); decision.setStatus(AiProviderDecisionStatus.RUNNING);
		decisions.saveAndFlush(decision);

		String attempts = "[{\"provider\":\"GEMINI\",\"modelId\":\"gemini-3.8-flash\",\"outcome\":\"AI_PROVIDER_QUOTA_EXHAUSTED\"},{\"provider\":\"OPENROUTER\",\"modelId\":\"openrouter/free\",\"outcome\":\"SUCCEEDED\"}]";
		assertThat(decisions.recordPrimaryProvenance(run.getId(), AiProvider.OPENROUTER, "openrouter/free", credential.id(), credential.fingerprint(), attempts)).isEqualTo(1);

		AiAnalysisProviderDecision reloaded = decisions.findById(decision.getId()).orElseThrow();
		assertThat(reloaded.getAiProvider()).isEqualTo(AiProvider.OPENROUTER);
		assertThat(reloaded.getModelId()).isEqualTo("openrouter/free");
		assertThat(reloaded.getCourseCredentialId()).isEqualTo(credential.id());
		assertThat(reloaded.getFallbackAttemptsJson()).isEqualTo(attempts).doesNotContain(OPENROUTER_KEY);
	}

	// ---- helpers ----

	private static CourseAiSettingsService.BindingInput input(String provider, String modelId) { return new CourseAiSettingsService.BindingInput(provider, modelId); }

	private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, IntegrationErrorCode code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(IntegrationException.class, e -> assertThat(e.getCode()).isEqualTo(code));
	}

	private static Semester semester() {
		Semester semester = new Semester();
		semester.setCode("SEM-" + UUID.randomUUID());
		semester.setName("Test Semester");
		return semester;
	}

	private static AcademicClass academicClass(Semester semester) {
		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode("SE" + UUID.randomUUID().toString().substring(0, 6));
		academicClass.setName("Test Class");
		return academicClass;
	}

	private static Subject subject() {
		Subject subject = new Subject();
		subject.setSubjectCode("SWP" + UUID.randomUUID().toString().substring(0, 8));
		subject.setName("Software Project");
		return subject;
	}

	private static Course course(AcademicClass academicClass, Subject subject, Semester semester) {
		Course course = new Course();
		course.setName("Course");
		course.setAcademicClass(academicClass);
		course.setSubject(subject);
		course.setSemester(semester);
		return course;
	}

	private static Project project(Course course) {
		Project project = new Project();
		project.setName("Project");
		project.setCourse(course);
		return project;
	}

	private static AiAnalysisRun run(Project project) {
		AiAnalysisRun run = new AiAnalysisRun();
		run.setProject(project);
		run.setArtifactType(AiArtifactType.TASK);
		run.setArtifactId(UUID.randomUUID());
		run.setArtifactRevision("rev-" + UUID.randomUUID());
		run.setAnalysisType(AiAnalysisType.TASK_INTELLIGENCE);
		run.setStatus(AiAnalysisStatus.RUNNING);
		run.setEvidenceHash("hash-" + UUID.randomUUID());
		run.setPolicyVersion("policy-v1");
		run.setPromptVersion("task-intelligence-v1");
		run.setSchemaVersion("schema-v1");
		run.setProviderConfigHash("cfg");
		run.setIdempotencyKey("idem-" + UUID.randomUUID());
		return run;
	}

}
