package com.saga.be.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.saga.be.config.SagaMailProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpEmailSenderTest {

	@Test
	void sendDoesNotEnableWhenHostOrFromMissing() {
		SagaMailProperties properties = new SagaMailProperties();
		properties.setEnabled(true);
		properties.setFrom("");
		properties.setHost("smtp.example.com");
		SmtpEmailSender sender = new SmtpEmailSender(Mockito.mock(JavaMailSender.class), properties);
		assertFalse(sender.isEnabled());
	}

	@Test
	void successfulSendUsesConfiguredFromAndOmitsPassword() {
		SagaMailProperties properties = ready();
		JavaMailSender mail = Mockito.mock(JavaMailSender.class);
		SmtpEmailSender sender = new SmtpEmailSender(mail, properties);
		sender.send(new EmailMessage("to@example.com", "Hello", "Body", null));
		ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mail).send(captor.capture());
		assertEquals("saga@example.com", captor.getValue().getFrom());
		assertEquals("to@example.com", captor.getValue().getTo()[0]);
		assertFalse(String.valueOf(captor.getValue()).toLowerCase().contains("password"));
	}

	@Test
	void providerExceptionIsMappedWithoutLeakingSecret() {
		SagaMailProperties properties = ready();
		JavaMailSender mail = Mockito.mock(JavaMailSender.class);
		doThrow(new MailAuthenticationException("535 Authentication failed for user=secret password=super-secret"))
				.when(mail)
				.send(Mockito.any(SimpleMailMessage.class));
		SmtpEmailSender sender = new SmtpEmailSender(mail, properties);
		EmailSendException ex = assertThrows(
				EmailSendException.class,
				() -> sender.send(new EmailMessage("to@example.com", "Hello", "Body", null)));
		assertEquals(EmailFailureCodes.SMTP_AUTH, ex.getFailureCode());
		assertFalse(ex.getMessage().contains("super-secret"));
		assertFalse(ex.getMessage().contains("password=super-secret"));
		assertEquals("Mail provider rejected the message.", ex.getMessage());
	}

	@Test
	void disabledSenderNeverSends() {
		DisabledEmailSender sender = new DisabledEmailSender();
		assertFalse(sender.isEnabled());
		EmailSendException ex = assertThrows(
				EmailSendException.class, () -> sender.send(new EmailMessage("to@example.com", "x", "y", null)));
		assertEquals(EmailFailureCodes.MAIL_DISABLED, ex.getFailureCode());
	}

	@Test
	void failureCodeFromSecretMessageDoesNotKeepTheSecret() {
		RuntimeException error = new RuntimeException("MAIL_PASSWORD=hunter2 auth failed");
		assertEquals(EmailFailureCodes.SMTP_AUTH, EmailFailureCodes.from(error));
		assertTrue(EmailFailureCodes.containsSecret("password=hunter2"));
	}

	private static SagaMailProperties ready() {
		SagaMailProperties properties = new SagaMailProperties();
		properties.setEnabled(true);
		properties.setFrom("saga@example.com");
		properties.setHost("smtp.example.com");
		return properties;
	}
}
