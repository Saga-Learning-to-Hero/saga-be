package com.saga.be.security.webauthn;

import com.saga.be.config.IntegrationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

@Configuration
@Profile("!test")
public class WebAuthnConfiguration {

	@Bean
	@ConditionalOnProperty(prefix = "saga.integration.webauthn", name = "enabled", havingValue = "true")
	public WebAuthnSettingsValidator webAuthnSettingsValidator(IntegrationProperties properties) {
		IntegrationProperties.WebAuthn webauthn = properties.getWebauthn();
		if (!StringUtils.hasText(webauthn.getRpId())
				|| webauthn.getAllowedOrigins() == null
				|| webauthn.getAllowedOrigins().isEmpty()) {
			throw new IllegalStateException("WebAuthn is enabled but RP ID / allowed origins are not configured.");
		}
		return new WebAuthnSettingsValidator(webauthn.getRpId(), webauthn.getRpName(), webauthn.getAllowedOrigins());
	}

	public record WebAuthnSettingsValidator(String rpId, String rpName, java.util.List<String> allowedOrigins) {}
}
