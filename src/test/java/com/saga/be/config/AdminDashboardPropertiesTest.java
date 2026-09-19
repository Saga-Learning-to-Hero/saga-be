package com.saga.be.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AdminDashboardPropertiesTest {

	@Test
	void defaultZoneIsUtcNotHostDefault() {
		AdminDashboardProperties properties = new AdminDashboardProperties();
		assertThat(properties.getZone()).isEqualTo(ZoneOffset.UTC);
		assertThat(properties.clock().getZone()).isEqualTo(ZoneOffset.UTC);
	}
}
