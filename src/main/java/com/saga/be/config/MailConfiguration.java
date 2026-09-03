package com.saga.be.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.mail.DisabledEmailSender;
import com.saga.be.mail.EmailSender;
import com.saga.be.mail.GmailApiEmailSender;
import com.saga.be.mail.RestClientGmailApiTransport;
import com.saga.be.mail.SmtpEmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
	public EmailSender emailSender(
			ObjectProvider<JavaMailSender> mailSenders,
			ObjectProvider<ObjectMapper> mappers,
			SagaMailProperties properties) {
		if (!properties.isEnabled()) {
			log.info("mail sender implementation={} provider=disabled", DisabledEmailSender.class.getSimpleName());
			return new DisabledEmailSender();
		}
		MailProvider provider = resolveProvider(properties);
		return switch (provider) {
			case SMTP -> smtpSender(mailSenders, properties);
			case GMAIL_API -> gmailApiSender(mappers, properties);
		};
	}

	private static MailProvider resolveProvider(SagaMailProperties properties) {
		try {
			return properties.resolvedProvider();
		} catch (IllegalArgumentException ex) {
			throw new IllegalStateException("saga.mail.provider must be smtp or gmail-api.", ex);
		}
	}

	private static EmailSender smtpSender(ObjectProvider<JavaMailSender> mailSenders, SagaMailProperties properties) {
		if (!StringUtils.hasText(properties.getFrom()) || !StringUtils.hasText(properties.getHost())) {
			throw new IllegalStateException(
					"saga.mail.enabled=true with provider=smtp requires saga.mail.from and saga.mail.host.");
		}
		JavaMailSender mailSender = mailSenders.getIfAvailable();
		if (mailSender == null) {
			throw new IllegalStateException("saga.mail.enabled=true with provider=smtp requires JavaMailSender.");
		}
		log.info(
				"mail sender implementation={} provider=smtp javaMailSenderPresent=true ready={}",
				SmtpEmailSender.class.getSimpleName(),
				properties.isReadyToSend());
		return new SmtpEmailSender(mailSender, properties);
	}

	private static EmailSender gmailApiSender(ObjectProvider<ObjectMapper> mappers, SagaMailProperties properties) {
		if (!StringUtils.hasText(properties.getFrom()) || !properties.getGmailApi().isConfigured()) {
			throw new IllegalStateException(
					"saga.mail.enabled=true with provider=gmail-api requires saga.mail.from, GMAIL_CLIENT_ID, GMAIL_CLIENT_SECRET, and GMAIL_REFRESH_TOKEN.");
		}
		ObjectMapper mapper = mappers.getIfAvailable(ObjectMapper::new);
		log.info(
				"mail sender implementation={} provider=gmail-api ready={}",
				GmailApiEmailSender.class.getSimpleName(),
				properties.isReadyToSend());
		return new GmailApiEmailSender(new RestClientGmailApiTransport(mapper), properties);
	}
}
