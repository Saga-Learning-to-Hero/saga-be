package com.saga.be.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.mail.DisabledEmailSender;
import com.saga.be.mail.EmailSender;
import com.saga.be.mail.SmtpEmailSender;
import com.saga.be.service.mail.EmailOutboxWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
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
	void enabledMailUsesSmtpSenderFromBootAutoConfiguration() {
		runner.withPropertyValues(
						"saga.mail.enabled=true",
						"saga.mail.from=test@example.com",
						"saga.mail.host=smtp.gmail.com",
						"spring.mail.host=smtp.gmail.com")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(EmailSender.class);
					assertThat(context).hasSingleBean(JavaMailSender.class);
					assertThat(context.getBean(EmailSender.class)).isInstanceOf(SmtpEmailSender.class);
					assertThat(context.getBean(EmailSender.class).isEnabled()).isTrue();
					assertThat(context.getBeansOfType(EmailSender.class)).hasSize(1);
				});
	}

	@Test
	void disabledMailUsesDisabledSender() {
		runner.withPropertyValues("saga.mail.enabled=false").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(EmailSender.class);
			assertThat(context.getBean(EmailSender.class)).isInstanceOf(DisabledEmailSender.class);
			assertThat(context.getBean(EmailSender.class).isEnabled()).isFalse();
			assertThat(context.getBeansOfType(EmailSender.class)).hasSize(1);
		});
	}

	@Test
	void enabledMailWithoutJavaMailSenderFailsInsteadOfSilentDisable() {
		new ApplicationContextRunner()
				.withUserConfiguration(MailConfiguration.class)
				.withPropertyValues(
						"saga.mail.enabled=true",
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
						"saga.mail.from=test@example.com",
						"saga.mail.host=smtp.gmail.com",
						"spring.mail.host=smtp.gmail.com")
				.run(context -> assertThat(context).doesNotHaveBean(EmailSender.class));
	}

	@Test
	void outboxWorkerIsScheduledWithConfiguredDelay() throws Exception {
		Scheduled scheduled = EmailOutboxWorker.class.getMethod("processDue").getAnnotation(Scheduled.class);
		assertThat(scheduled).isNotNull();
		assertThat(scheduled.fixedDelayString()).isEqualTo("${saga.mail.worker-delay-ms:15000}");
		assertThat(IntegrationConfiguration.class.getAnnotation(EnableScheduling.class)).isNotNull();
	}
}
