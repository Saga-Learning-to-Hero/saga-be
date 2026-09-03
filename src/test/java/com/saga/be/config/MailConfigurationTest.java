package com.saga.be.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.mail.DisabledEmailSender;
import com.saga.be.mail.EmailSender;
import com.saga.be.mail.GmailApiEmailSender;
import com.saga.be.mail.SmtpEmailSender;
import com.saga.be.service.mail.EmailOutboxWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailHealthContributorAutoConfiguration;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

class MailConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
			.withUserConfiguration(MailConfiguration.class);

	@Test
	void enabledMailDefaultsToSmtpSender() {
		runner.withPropertyValues(
						"saga.mail.enabled=true",
						"saga.mail.from=test@example.com",
						"saga.mail.host=smtp.gmail.com",
						"spring.mail.host=smtp.gmail.com")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(EmailSender.class);
					assertThat(context.getBean(EmailSender.class)).isInstanceOf(SmtpEmailSender.class);
					assertThat(context.getBean(EmailSender.class).isEnabled()).isTrue();
					assertThat(context.getBeansOfType(EmailSender.class)).hasSize(1);
				});
	}

	@Test
	void smtpProviderDoesNotRequireGmailApiCredentials() {
		runner.withPropertyValues(
						"saga.mail.enabled=true",
						"saga.mail.provider=smtp",
						"saga.mail.from=test@example.com",
						"saga.mail.host=smtp.gmail.com",
						"spring.mail.host=smtp.gmail.com")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(EmailSender.class)).isInstanceOf(SmtpEmailSender.class);
					assertThat(context).hasSingleBean(JavaMailSender.class);
				});
	}

	@Test
	void smtpIsSelectedEvenWhenGmailApiCredentialsAreAlsoPresent() {
		runner.withPropertyValues(
						"saga.mail.enabled=true",
						"saga.mail.provider=smtp",
						"saga.mail.from=test@example.com",
						"saga.mail.host=smtp.gmail.com",
						"spring.mail.host=smtp.gmail.com",
						"saga.mail.gmail-api.client-id=client",
						"saga.mail.gmail-api.client-secret=secret",
						"saga.mail.gmail-api.refresh-token=refresh")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(EmailSender.class)).isInstanceOf(SmtpEmailSender.class);
				});
	}

	@Test
	void disabledMailUsesDisabledSender() {
		runner.withPropertyValues("saga.mail.enabled=false", "saga.mail.provider=gmail-api")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(EmailSender.class);
					assertThat(context.getBean(EmailSender.class)).isInstanceOf(DisabledEmailSender.class);
					assertThat(context.getBean(EmailSender.class).isEnabled()).isFalse();
				});
	}

	@Test
	void gmailApiProviderDoesNotRequireSmtpOrJavaMailSender() {
		new ApplicationContextRunner()
				.withUserConfiguration(MailConfiguration.class)
				.withPropertyValues(
						"saga.mail.enabled=true",
						"saga.mail.provider=gmail-api",
						"saga.mail.from=test@example.com",
						"saga.mail.gmail-api.client-id=client",
						"saga.mail.gmail-api.client-secret=secret",
						"saga.mail.gmail-api.refresh-token=refresh")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(EmailSender.class);
					assertThat(context).doesNotHaveBean(JavaMailSender.class);
					assertThat(context.getBean(EmailSender.class)).isInstanceOf(GmailApiEmailSender.class);
					assertThat(context.getBean(EmailSender.class).isEnabled()).isTrue();
				});
	}

	@Test
	void gmailApiIsSelectedEvenWhenSmtpHostIsAlsoPresent() {
		runner.withPropertyValues(
						"saga.mail.enabled=true",
						"saga.mail.provider=gmail-api",
						"saga.mail.from=test@example.com",
						"saga.mail.host=smtp.gmail.com",
						"spring.mail.host=smtp.gmail.com",
						"saga.mail.gmail-api.client-id=client",
						"saga.mail.gmail-api.client-secret=secret",
						"saga.mail.gmail-api.refresh-token=refresh")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(EmailSender.class)).isInstanceOf(GmailApiEmailSender.class);
					assertThat(context.getBeansOfType(EmailSender.class)).hasSize(1);
				});
	}

	@Test
	void enabledSmtpWithoutJavaMailSenderFailsInsteadOfSilentDisable() {
		new ApplicationContextRunner()
				.withUserConfiguration(MailConfiguration.class)
				.withPropertyValues(
						"saga.mail.enabled=true",
						"saga.mail.provider=smtp",
						"saga.mail.from=test@example.com",
						"saga.mail.host=smtp.gmail.com")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void enabledGmailApiWithoutCredentialsFailsInsteadOfSilentDisable() {
		new ApplicationContextRunner()
				.withUserConfiguration(MailConfiguration.class)
				.withPropertyValues(
						"saga.mail.enabled=true",
						"saga.mail.provider=gmail-api",
						"saga.mail.from=test@example.com",
						"saga.mail.host=smtp.gmail.com")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void testProfileDoesNotRegisterRuntimeMailSender() {
		new ApplicationContextRunner()
				.withInitializer(context -> context.getEnvironment().addActiveProfile("test"))
				.withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
				.withUserConfiguration(MailConfiguration.class)
				.withPropertyValues(
						"saga.mail.enabled=true",
						"saga.mail.provider=gmail-api",
						"saga.mail.from=test@example.com",
						"spring.mail.host=smtp.gmail.com")
				.run(context -> assertThat(context).doesNotHaveBean(EmailSender.class));
	}

	@Test
	void mailHealthIndicatorIsOffByDefaultAndDoesNotRegisterContributor() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(
						MailSenderAutoConfiguration.class, MailHealthContributorAutoConfiguration.class))
				.withPropertyValues(
						"spring.mail.host=smtp.gmail.com",
						"spring.mail.port=587",
						"management.health.mail.enabled=false")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).doesNotHaveBean("mailHealthIndicator");
					assertThat(context).doesNotHaveBean("mailHealthContributor");
				});
	}

	@Test
	void outboxWorkerIsScheduledWithConfiguredDelay() throws Exception {
		Scheduled scheduled = EmailOutboxWorker.class.getMethod("processDue").getAnnotation(Scheduled.class);
		assertThat(scheduled).isNotNull();
		assertThat(scheduled.fixedDelayString()).isEqualTo("${saga.mail.worker-delay-ms:15000}");
		assertThat(IntegrationConfiguration.class.getAnnotation(EnableScheduling.class)).isNotNull();
	}
}
