package com.saga.be.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.service.team.RedisTeamPreviewStore;
import com.saga.be.service.team.TeamPreviewStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@Profile("!test")
public class TeamConfiguration {

	@Bean
	public TeamPreviewStore teamPreviewStore(StringRedisTemplate redis, ObjectMapper mapper) {
		return new RedisTeamPreviewStore(redis, mapper);
	}
}
