package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.AiAnalysisProperties;
import com.saga.be.entity.enums.AiProviderRole;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Regression coverage for the production constructor-resolution bug: with two constructors and
 * neither marked {@code @Autowired}, Spring fell back to a no-arg instantiation attempt and
 * failed with {@code NoSuchMethodException: RemoteAiModelProvider.<init>()} whenever
 * saga.ai.enabled=true and saga.ai.primary-provider=remote were both set.
 */
class RemoteAiModelProviderContextTest {

	private final ApplicationContextRunner runner =
			new ApplicationContextRunner()
					.withUserConfiguration(PropertiesConfig.class, RemoteAiModelProvider.class)
					.withBean(ObjectMapper.class, ObjectMapper::new);

	@Test
	void springAutowiresTheProductionConstructorWhenRemoteRuntimeIsSelected() {
		runner.withPropertyValues(
						"saga.ai.enabled=true",
						"saga.ai.primary-provider=remote",
						"saga.ai.runtime.enabled=true",
						"saga.ai.runtime.base-url=http://localhost:1",
						"saga.ai.runtime.internal-token=test-token")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(RemoteAiModelProvider.class);
					RemoteAiModelProvider provider = context.getBean(RemoteAiModelProvider.class);
					assertThat(provider.role()).isEqualTo(AiProviderRole.PRIMARY);
					assertThat(provider.providerKey()).isEqualTo("saga-ai");
				});
	}

	@Test
	void beanIsAbsentWhenPrimaryProviderIsNotRemote() {
		runner.withPropertyValues("saga.ai.enabled=true", "saga.ai.primary-provider=openai")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).doesNotHaveBean(RemoteAiModelProvider.class);
				});
	}

	@Configuration
	@EnableConfigurationProperties(AiAnalysisProperties.class)
	static class PropertiesConfig {}
}
