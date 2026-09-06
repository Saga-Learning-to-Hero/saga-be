package com.saga.be.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
@ConditionalOnClass(CookieSerializer.class)
public class SessionCookieConfig {

	private static final Logger log = LoggerFactory.getLogger(SessionCookieConfig.class);

	@Bean
	public CookieSerializer cookieSerializer(AuthProperties properties) {
		DefaultCookieSerializer serializer = new DefaultCookieSerializer();
		serializer.setCookieName("SAGA_SESSION");
		serializer.setUseHttpOnlyCookie(true);
		serializer.setUseSecureCookie(properties.getCookie().isSecure());
		serializer.setSameSite(properties.getCookie().getSameSite());
		serializer.setCookiePath("/");
		log.info(
				"auth cookie policy sameSite={} secure={}",
				properties.getCookie().getSameSite(),
				properties.getCookie().isSecure());
		return serializer;
	}
}
