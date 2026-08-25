package com.saga.be.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;

class LettuceJdkDnsConfigTest {

	@Test
	void customizerInstallsJvmSystemAddressResolver() {
		ClientResourcesBuilderCustomizer customizer = new LettuceJdkDnsConfig().jdkDnsAddressResolver();
		DefaultClientResources.Builder builder = DefaultClientResources.builder();
		customizer.customize(builder);
		ClientResources resources = builder.build();
		try {
			assertSame(DefaultAddressResolverGroup.INSTANCE, resources.addressResolverGroup());
			assertFalse(resources.addressResolverGroup().getClass().getName().contains("dns.DnsAddressResolverGroup"));
		} finally {
			resources.shutdown();
		}
	}
}
