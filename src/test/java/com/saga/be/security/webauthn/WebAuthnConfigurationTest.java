package com.saga.be.security.webauthn;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.saga.be.config.IntegrationProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebAuthnConfigurationTest {

	@Test
	void disabledModeDoesNotValidateRp() {
		IntegrationProperties properties = new IntegrationProperties();
		properties.getWebauthn().setEnabled(false);
		assertDoesNotThrow(() -> {
			if (properties.getWebauthn().isEnabled()) {
				new WebAuthnConfiguration().webAuthnSettingsValidator(properties);
			}
		});
	}

	@Test
	void enabledModeRequiresRpIdAndOrigins() {
		IntegrationProperties properties = new IntegrationProperties();
		properties.getWebauthn().setEnabled(true);
		WebAuthnConfiguration config = new WebAuthnConfiguration();
		assertThrows(IllegalStateException.class, () -> config.webAuthnSettingsValidator(properties));
		properties.getWebauthn().setRpId("saga.local");
		properties.getWebauthn().setAllowedOrigins(List.of("https://saga.local"));
		assertDoesNotThrow(() -> config.webAuthnSettingsValidator(properties));
	}
}
