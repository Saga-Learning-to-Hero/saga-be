package com.saga.be.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.saga.be.integration.crypto.TokenEncryptor;
import com.saga.be.integration.oauth.GithubReconnectCandidateStore;
import com.saga.be.integration.oauth.InMemoryGithubReconnectCandidateStore;
import com.saga.be.integration.oauth.InMemoryOAuthStateStore;
import com.saga.be.integration.oauth.InMemoryPendingJiraConnectStore;
import com.saga.be.integration.oauth.OAuthStateService;
import com.saga.be.integration.oauth.OAuthStateStore;
import com.saga.be.integration.oauth.PendingJiraConnectStore;
import com.saga.be.service.audit.AuditRedactor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@Configuration
@EnableScheduling
public class IntegrationConfiguration {

	@Bean
	@ConditionalOnMissingBean(ObjectMapper.class)
	public ObjectMapper objectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule());
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		return mapper;
	}

	@Bean
	public TokenEncryptor tokenEncryptor(IntegrationProperties properties) {
		return new TokenEncryptor(properties.getTokenEncryptionKey());
	}

	@Bean
	public AuditRedactor auditRedactor(ObjectMapper objectMapper) {
		return new AuditRedactor(objectMapper);
	}

	@Bean
	@ConditionalOnMissingBean(OAuthStateStore.class)
	public OAuthStateStore inMemoryOAuthStateStore() {
		return new InMemoryOAuthStateStore();
	}

	@Bean
	@ConditionalOnMissingBean(PendingJiraConnectStore.class)
	public PendingJiraConnectStore inMemoryPendingJiraConnectStore() {
		return new InMemoryPendingJiraConnectStore();
	}

	@Bean
	@ConditionalOnMissingBean(GithubReconnectCandidateStore.class)
	public GithubReconnectCandidateStore inMemoryGithubReconnectCandidateStore() {
		return new InMemoryGithubReconnectCandidateStore();
	}

	@Bean
	@Primary
	@ConditionalOnBean(StringRedisTemplate.class)
	public OAuthStateStore redisOAuthStateStore(
			StringRedisTemplate redis, ObjectMapper mapper, IntegrationProperties properties) {
		return new RedisOAuthStateStore(redis, mapper, properties.getOauthStateTtl());
	}

	@Bean
	@Primary
	@ConditionalOnBean(StringRedisTemplate.class)
	public PendingJiraConnectStore redisPendingJiraConnectStore(
			StringRedisTemplate redis, TokenEncryptor encryptor, ObjectMapper mapper, IntegrationProperties properties) {
		return new RedisPendingJiraConnectStore(redis, encryptor, mapper, properties.getOauthStateTtl());
	}

	@Bean
	@Primary
	@ConditionalOnBean(StringRedisTemplate.class)
	public GithubReconnectCandidateStore redisGithubReconnectCandidateStore(
			StringRedisTemplate redis, ObjectMapper mapper, IntegrationProperties properties) {
		return new RedisGithubReconnectCandidateStore(redis, mapper, properties.getOauthStateTtl());
	}

	@Bean
	public OAuthStateService oauthStateService(OAuthStateStore store, IntegrationProperties properties) {
		return new OAuthStateService(store, properties.getOauthStateTtl());
	}

	/**
	 * Explicit converter list, not Spring Boot's defaults: this codebase's Jira/GitHub clients read
	 * and write {@code com.fasterxml.jackson.databind} (Jackson 2) tree types ({@code JsonNode}/
	 * {@code ObjectNode}) and Jackson-2-annotated records directly against this bean. With Jackson 3
	 * also on the classpath (Spring Boot 4's default), {@code RestClient.builder()}'s own default
	 * converter negotiation prefers a Jackson-3-based converter that does not recognize Jackson 2's
	 * tree types as its own -- for reads that silently fails as {@code HttpMessageConversionException}
	 * on {@code JsonNode.class}; for writes it silently serializes an {@code ObjectNode} via generic
	 * bean reflection (its {@code isArray()}/{@code isObject()}/... getters) instead of as the JSON
	 * tree it actually holds, so Jira receives a body with no {@code "fields"} key at all and rejects
	 * the write. Registering {@link MappingJackson2HttpMessageConverter} bound to this module's own
	 * {@link ObjectMapper} bean (the same one every Jira/GitHub client already uses) makes JSON
	 * handling deterministic and correct for both directions, on top of the byte/string/resource/form
	 * converters {@link RestClient} needs for binary bodies, string reads, and GitHub's
	 * form-urlencoded OAuth token exchange.
	 */
	@Bean
	public RestClient integrationRestClient(ObjectMapper objectMapper) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(5));
		factory.setReadTimeout(Duration.ofSeconds(20));
		return RestClient.builder()
				.requestFactory(factory)
				.messageConverters(converters -> {
					converters.clear();
					converters.add(new ByteArrayHttpMessageConverter());
					converters.add(new StringHttpMessageConverter(StandardCharsets.UTF_8));
					converters.add(new ResourceHttpMessageConverter());
					converters.add(new FormHttpMessageConverter());
					converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
				})
				.build();
	}
}
