package com.saga.be.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
@ConditionalOnClass(CookieSerializer.class)
@ConditionalOnBean(RedisConnectionFactory.class)
public class SessionCookieConfig {

	@Bean
	public CookieSerializer cookieSerializer(AuthProperties properties) {
		DefaultCookieSerializer serializer = new DefaultCookieSerializer();
		serializer.setCookieName("SAGA_SESSION");
		serializer.setUseHttpOnlyCookie(true);
		serializer.setUseSecureCookie(properties.getCookie().isSecure());
		serializer.setSameSite(properties.getCookie().getSameSite());
		serializer.setCookiePath("/");
		return serializer;
	}
}
