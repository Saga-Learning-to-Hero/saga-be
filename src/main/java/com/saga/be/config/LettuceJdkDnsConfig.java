package com.saga.be.config;

import io.lettuce.core.resource.ClientResources;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lettuce 7.5 defaults to Netty {@code DnsAddressResolverGroup}, which on some Windows
 * setups falls back to Google Public DNS (8.8.8.8 / 8.8.4.4) over UDP and times out.
 * This customizer keeps Spring Boot's LettuceConnectionFactory and property-driven Redis
 * (host/port/TLS) but resolves hostnames through the JVM/system DNS path.
 */
@Configuration
@ConditionalOnClass({ClientResources.class, DefaultAddressResolverGroup.class})
public class LettuceJdkDnsConfig {

	private static final Logger log = LoggerFactory.getLogger(LettuceJdkDnsConfig.class);

	@Bean
	public ClientResourcesBuilderCustomizer jdkDnsAddressResolver() {
		return builder -> {
			builder.addressResolverGroup(DefaultAddressResolverGroup.INSTANCE);
			log.info("lettuce dns resolver=JVM/system (DefaultAddressResolverGroup)");
		};
	}
}
