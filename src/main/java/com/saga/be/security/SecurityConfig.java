package com.saga.be.security;

import com.saga.be.auth.AuthErrorCode;
import com.saga.be.auth.GoogleAccountService;
import com.saga.be.config.AuthProperties;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.exception.AuthException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

	@Bean
	public SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	@Bean
	public SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			AuthProperties properties,
			SecurityContextRepository securityContextRepository,
			ObjectProvider<GoogleAccountService> googleAccounts,
			ObjectProvider<ClientRegistrationRepository> clientRegistrations)
			throws Exception {
		CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
		csrfRepo.setCookieName("XSRF-TOKEN");
		csrfRepo.setHeaderName("X-XSRF-TOKEN");

		http.cors(Customizer.withDefaults())
				.csrf(csrf -> csrf.csrfTokenRepository(csrfRepo)
						.csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
						.ignoringRequestMatchers("/login/oauth2/**", "/oauth2/**", "/api/webhooks/github", "/api/webhooks/jira"))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
						.sessionFixation()
						.changeSessionId())
				.securityContext(context -> context.securityContextRepository(securityContextRepository))
				.requestCache(cache -> cache.requestCache(new NullRequestCache()))
				.authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.OPTIONS, "/**")
						.permitAll()
						.requestMatchers("/", "/index.html")
						.permitAll()
						.requestMatchers("/swagger-ui.html", "/swagger-ui/**")
						.permitAll()
						.requestMatchers("/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**")
						.permitAll()
						.requestMatchers("/error")
						.permitAll()
						.requestMatchers("/actuator/health", "/actuator/info")
						.permitAll()
						.requestMatchers("/oauth2/**", "/login/oauth2/**")
						.permitAll()
						.requestMatchers("/api/auth/csrf")
						.permitAll()
						.requestMatchers("/api/auth/me")
						.permitAll()
						.requestMatchers("/api/auth/login")
						.permitAll()
						.requestMatchers("/api/auth/register")
						.permitAll()
						.requestMatchers("/api/auth/logout")
						.permitAll()
						.requestMatchers("/api/webhooks/github", "/api/webhooks/jira")
						.permitAll()
						.requestMatchers("/api/admin/**")
						.hasRole("ADMIN")
						.requestMatchers("/api/lecturer/**")
						.hasAnyRole("LECTURER", "ADMIN")
						.anyRequest()
						.authenticated())
				.exceptionHandling(ex -> ex.authenticationEntryPoint(this::writeUnauthorized)
						.accessDeniedHandler((request, response, denied) -> {
							Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
							if (authentication != null
									&& authentication.getPrincipal() instanceof SagaUserPrincipal principal
									&& principal.isPasswordSetupRequired()) {
								PasswordSetupAuthorizationManager.writePasswordSetupRequired(response);
								return;
							}
							writeForbidden(response);
						}))
				.logout(logout -> logout.disable());

		http.addFilterAfter(new PasswordSetupEnforcementFilter(), UsernamePasswordAuthenticationFilter.class);

		if (properties.getGoogle().isConfigured() && clientRegistrations.getIfAvailable() != null) {
			http.oauth2Login(oauth -> oauth.loginPage("/oauth2/authorization/google")
					.authorizationEndpoint(endpoint ->
							endpoint.authorizationRequestRepository(new HttpSessionOAuth2AuthorizationRequestRepository()))
					.successHandler(googleSuccessHandler(properties, googleAccounts, securityContextRepository))
					.failureHandler(googleFailureHandler(properties)));
		}

		return http.build();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource(AuthProperties properties) {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(properties.getFrontendOrigins());
		config.setAllowCredentials(true);
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "X-Requested-With"));
		config.setExposedHeaders(List.of("X-XSRF-TOKEN"));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}

	@Bean
	@Conditional(GoogleOAuthConfiguredCondition.class)
	public ClientRegistrationRepository clientRegistrationRepository(AuthProperties properties) {
		ClientRegistration google = CommonOAuth2Provider.GOOGLE
				.getBuilder("google")
				.clientId(properties.getGoogle().getClientId())
				.clientSecret(properties.getGoogle().getClientSecret())
				.scope("openid", "profile", "email")
				.build();
		return new InMemoryClientRegistrationRepository(google);
	}

	static final class GoogleOAuthConfiguredCondition implements Condition {
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			return StringUtils.hasText(context.getEnvironment().getProperty("saga.auth.google.client-id"));
		}
	}

	private AuthenticationSuccessHandler googleSuccessHandler(
			AuthProperties properties,
			ObjectProvider<GoogleAccountService> googleAccounts,
			SecurityContextRepository securityContextRepository) {
		return (request, response, authentication) -> {
			GoogleAccountService service = googleAccounts.getIfAvailable();
			if (service == null) {
				redirectFailure(properties, response, AuthErrorCode.GOOGLE_ACCOUNT_NOT_ELIGIBLE);
				return;
			}
			try {
				OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
				boolean verified = Boolean.TRUE.equals(oidcUser.getEmailVerified())
						|| Boolean.TRUE.equals(oidcUser.getClaim("email_verified"));
				String hd = oidcUser.getIdToken() != null ? oidcUser.getIdToken().getClaimAsString("hd") : null;
				GoogleAccountService.GoogleOidcIdentity identity = new GoogleAccountService.GoogleOidcIdentity(
						oidcUser.getSubject(),
						oidcUser.getEmail(),
						verified,
						hd,
						oidcUser.getFullName(),
						oidcUser.getPicture());
				UserAccount account = service.authenticateOrProvision(
						identity, new HashSet<>(properties.getGoogle().getAllowedHostedDomains()));
				Authentication sagaAuth = SagaAuthentications.authenticated(account);
				request.changeSessionId();
				SecurityContext context = SecurityContextHolder.createEmptyContext();
				context.setAuthentication(sagaAuth);
				SecurityContextHolder.setContext(context);
				securityContextRepository.saveContext(context, request, response);
				SagaUserPrincipal principal = (SagaUserPrincipal) sagaAuth.getPrincipal();
				String target = principal.isPasswordSetupRequired()
						? properties.getGoogle().getPasswordSetupUrl()
						: properties.getGoogle().getSuccessUrl();
				response.sendRedirect(target);
			} catch (AuthException ex) {
				log.info("auth method=GOOGLE result=failure category={}", ex.getCode());
				redirectFailure(properties, response, ex.getCode());
			}
		};
	}

	private AuthenticationFailureHandler googleFailureHandler(AuthProperties properties) {
		return (request, response, exception) ->
				redirectFailure(properties, response, AuthErrorCode.GOOGLE_ACCOUNT_NOT_ELIGIBLE);
	}

	private static void redirectFailure(AuthProperties properties, HttpServletResponse response, AuthErrorCode code)
			throws IOException {
		String base = properties.getGoogle().getFailureUrl();
		String separator = base.contains("?") ? "&" : "?";
		response.sendRedirect(base + separator + "error=" + code.name());
	}

	private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response, Exception ex)
			throws IOException {
		response.setStatus(401);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"Authentication failed.\"}");
	}

	private void writeForbidden(HttpServletResponse response) throws IOException {
		response.setStatus(403);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"code\":\"ACCESS_DENIED\",\"message\":\"Access denied.\"}");
	}
}
