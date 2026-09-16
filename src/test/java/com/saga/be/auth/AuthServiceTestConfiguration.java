package com.saga.be.auth;

import com.saga.be.service.admin.AdminAuditLogQueryService;
import com.saga.be.service.admin.AdminUserCommandService;
import com.saga.be.service.admin.AdminUserQueryService;
import com.saga.be.service.notification.NotificationService;
import com.saga.be.service.notification.PushInstallationService;
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

	@Bean
	@Primary
	PasswordResetService passwordResetService() {
		return Mockito.mock(PasswordResetService.class);
	}

	@Bean
	@Primary
	UserProfileService userProfileService() {
		return Mockito.mock(UserProfileService.class);
	}

	@Bean
	@Primary
	AdminUserQueryService adminUserQueryService() {
		return Mockito.mock(AdminUserQueryService.class);
	}

	@Bean
	@Primary
	AdminAuditLogQueryService adminAuditLogQueryService() {
		return Mockito.mock(AdminAuditLogQueryService.class);
	}

	@Bean
	@Primary
	AdminUserCommandService adminUserCommandService() {
		return Mockito.mock(AdminUserCommandService.class);
	}

	@Bean
	@Primary
	NotificationService notificationService() {
		return Mockito.mock(NotificationService.class);
	}

	@Bean
	@Primary
	PushInstallationService pushInstallationService() {
		return Mockito.mock(PushInstallationService.class);
	}

	@Bean
	@Primary
	com.saga.be.service.notification.ManualNotificationService manualNotificationService() {
		return Mockito.mock(com.saga.be.service.notification.ManualNotificationService.class);
	}

	@Bean
	@Primary
	com.saga.be.service.student.StudentProjectService studentProjectService() {
		return Mockito.mock(com.saga.be.service.student.StudentProjectService.class);
	}
}
