package com.saga.be.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.saga.be.integration.crypto.TokenEncryptor;
import com.saga.be.integration.oauth.InMemoryOAuthStateStore;
import com.saga.be.integration.oauth.InMemoryPendingJiraConnectStore;
import com.saga.be.integration.oauth.OAuthStateService;
import com.saga.be.integration.oauth.OAuthStateStore;
import com.saga.be.integration.oauth.PendingJiraConnectStore;
import com.saga.be.service.audit.AuditRedactor;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
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
	public OAuthStateService oauthStateService(OAuthStateStore store, IntegrationProperties properties) {
		return new OAuthStateService(store, properties.getOauthStateTtl());
	}

	@Bean
	public RestClient integrationRestClient() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(5));
		factory.setReadTimeout(Duration.ofSeconds(20));
		return RestClient.builder().requestFactory(factory).build();
	}
}
