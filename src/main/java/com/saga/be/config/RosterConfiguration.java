package com.saga.be.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.service.roster.RedisRosterPreviewStore;
import com.saga.be.service.roster.RosterPreviewStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@Profile("!test")
public class RosterConfiguration {

	@Bean
	public RosterPreviewStore rosterPreviewStore(StringRedisTemplate redis, ObjectMapper mapper) {
		return new RedisRosterPreviewStore(redis, mapper);
	}
}
