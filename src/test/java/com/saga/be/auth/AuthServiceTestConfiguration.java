package com.saga.be.auth;

import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("test")
public class AuthServiceTestConfiguration {

	@Bean
	@Primary
	LocalAuthService localAuthService() {
		return Mockito.mock(LocalAuthService.class);
	}

	@Bean
	@Primary
	PasswordSetupService passwordSetupService() {
		return Mockito.mock(PasswordSetupService.class);
	}

	@Bean
	@Primary
	StudentRegistrationService studentRegistrationService() {
		return Mockito.mock(StudentRegistrationService.class);
	}
}
