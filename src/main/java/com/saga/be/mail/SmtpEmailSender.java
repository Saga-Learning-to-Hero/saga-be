package com.saga.be.mail;

import com.saga.be.config.SagaMailProperties;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.util.StringUtils;

public class SmtpEmailSender implements EmailSender {

	private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

	private final JavaMailSender mailSender;
	private final SagaMailProperties properties;

	public SmtpEmailSender(JavaMailSender mailSender, SagaMailProperties properties) {
		this.mailSender = mailSender;
		this.properties = properties;
	}

	@Override
	public boolean isEnabled() {
		return properties.isReadyToSend();
	}

	@Override
	public void send(EmailMessage message) {
		if (!isEnabled()) {
			throw new EmailSendException(EmailFailureCodes.MAIL_DISABLED, "Mail sender is disabled.");
		}
		if (message == null || !StringUtils.hasText(message.to())) {
			throw new EmailSendException(EmailFailureCodes.INVALID_ADDRESS, "Recipient is required.");
		}
		try {
			if (StringUtils.hasText(message.htmlBody())) {
				MimeMessage mime = mailSender.createMimeMessage();
				MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
				helper.setFrom(properties.getFrom().trim());
				helper.setTo(message.to().trim());
				helper.setSubject(message.subject() == null ? "" : message.subject());
				boolean html = true;
				String text = StringUtils.hasText(message.textBody()) ? message.textBody() : message.htmlBody();
				helper.setText(text, html);
				mailSender.send(mime);
			} else {
				SimpleMailMessage simple = new SimpleMailMessage();
				simple.setFrom(properties.getFrom().trim());
				simple.setTo(message.to().trim());
				simple.setSubject(message.subject() == null ? "" : message.subject());
				simple.setText(message.textBody() == null ? "" : message.textBody());
				mailSender.send(simple);
			}
			log.info("email result=sent");
		} catch (EmailSendException ex) {
			throw ex;
		} catch (Exception ex) {
			String code = EmailFailureCodes.from(ex);
			log.warn("email result=failure category={}", code);
			throw new EmailSendException(code, "Mail provider rejected the message.", ex);
		}
	}
}
