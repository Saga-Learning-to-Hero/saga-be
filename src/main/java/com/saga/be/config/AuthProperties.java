package com.saga.be.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "saga.auth")
public class AuthProperties {

	private List<String> frontendOrigins = new ArrayList<>(List.of("http://localhost:3000"));
	private final Cookie cookie = new Cookie();
	private final Google google = new Google();
	private final BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();
	private final Password password = new Password();

	public List<String> getFrontendOrigins() {
		return frontendOrigins;
	}

	public void setFrontendOrigins(List<String> frontendOrigins) {
		this.frontendOrigins = frontendOrigins;
	}

	public Cookie getCookie() {
		return cookie;
	}

	public Google getGoogle() {
		return google;
	}

	public BootstrapAdmin getBootstrapAdmin() {
		return bootstrapAdmin;
	}

	public Password getPassword() {
		return password;
	}

	public static class Cookie {
		private String sameSite = "Lax";
		private boolean secure = false;

		public String getSameSite() {
			return sameSite;
		}

		public void setSameSite(String sameSite) {
			this.sameSite = sameSite;
		}

		public boolean isSecure() {
			return secure;
		}

		public void setSecure(boolean secure) {
			this.secure = secure;
		}
	}

	public static class Google {
		private String clientId = "";
		private String clientSecret = "";
		private List<String> allowedHostedDomains = new ArrayList<>(List.of("fpt.edu.vn", "fe.edu.vn"));
		private String successUrl = "http://localhost:3000/dashboard";
		private String passwordSetupUrl = "http://localhost:3000/auth/setup-password";
		private String failureUrl = "http://localhost:3000/login";

		public boolean isConfigured() {
			return clientId != null && !clientId.isBlank();
		}

		public String getClientId() {
			return clientId;
		}

		public void setClientId(String clientId) {
			this.clientId = clientId;
		}

		public String getClientSecret() {
			return clientSecret;
		}

		public void setClientSecret(String clientSecret) {
			this.clientSecret = clientSecret;
		}

		public List<String> getAllowedHostedDomains() {
			return allowedHostedDomains;
		}

		public void setAllowedHostedDomains(List<String> allowedHostedDomains) {
			this.allowedHostedDomains = allowedHostedDomains;
		}

		public String getSuccessUrl() {
			return successUrl;
		}

		public void setSuccessUrl(String successUrl) {
			this.successUrl = successUrl;
		}

		public String getPasswordSetupUrl() {
			return passwordSetupUrl;
		}

		public void setPasswordSetupUrl(String passwordSetupUrl) {
			this.passwordSetupUrl = passwordSetupUrl;
		}

		public String getFailureUrl() {
			return failureUrl;
		}

		public void setFailureUrl(String failureUrl) {
			this.failureUrl = failureUrl;
		}
	}

	public static class BootstrapAdmin {
		private boolean enabled = false;
		private String username = "admin";
		private String password = "";
		private String email = "admin@saga.local";

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getUsername() {
			return username;
		}

		public void setUsername(String username) {
			this.username = username;
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password;
		}

		public String getEmail() {
			return email;
		}

		public void setEmail(String email) {
			this.email = email;
		}
	}

	public static class Password {
		private int minLength = 10;
		private int maxLength = 128;

		public int getMinLength() {
			return minLength;
		}

		public void setMinLength(int minLength) {
			this.minLength = minLength;
		}

		public int getMaxLength() {
			return maxLength;
		}

		public void setMaxLength(int maxLength) {
			this.maxLength = maxLength;
		}
	}
}
