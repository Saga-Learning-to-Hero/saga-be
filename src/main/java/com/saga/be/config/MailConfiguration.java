package com.saga.be.config;

import com.saga.be.mail.DisabledEmailSender;
import com.saga.be.mail.EmailSender;
import com.saga.be.mail.SmtpEmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

@Configuration
@Profile("!test")
@EnableConfigurationProperties(SagaMailProperties.class)
public class MailConfiguration {

	private static final Logger log = LoggerFactory.getLogger(MailConfiguration.class);

	@Bean
	@ConditionalOnProperty(prefix = "saga.mail", name = "enabled", havingValue = "true")
	public EmailSender smtpEmailSender(JavaMailSender mailSender, SagaMailProperties properties) {
		if (!StringUtils.hasText(properties.getFrom()) || !StringUtils.hasText(properties.getHost())) {
			throw new IllegalStateException("saga.mail.enabled=true requires saga.mail.from and saga.mail.host.");
		}
		log.info(
				"mail sender implementation={} javaMailSenderPresent=true ready={}",
				SmtpEmailSender.class.getSimpleName(),
				properties.isReadyToSend());
		return new SmtpEmailSender(mailSender, properties);
	}

	@Bean
	@ConditionalOnProperty(prefix = "saga.mail", name = "enabled", havingValue = "false", matchIfMissing = true)
	public EmailSender disabledEmailSender() {
		log.info("mail sender implementation={}", DisabledEmailSender.class.getSimpleName());
		return new DisabledEmailSender();
	}
}
