package com.saga.be.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.saga.be.config.SagaMailProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

class GmailApiEmailSenderTest {

	private static final String REFRESH = "test-refresh-token-never-log";
	private static final String ACCESS = "test-access-token-never-log";
	private static final String SECRET = "test-gmail-client-secret";

	private ListAppender<ILoggingEvent> appender;
	private Logger logger;

	@BeforeEach
	void captureLogs() {
		logger = (Logger) LoggerFactory.getLogger(GmailApiEmailSender.class);
		appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	void detachLogs() {
		logger.detachAppender(appender);
	}

	@Test
	void sendUsesGmailApiRawMimeWithFromToSubjectHtmlAndText() throws Exception {
		GmailApiTransport transport = Mockito.mock(GmailApiTransport.class);
		when(transport.refreshAccessToken(eq("client-id"), eq(SECRET), eq(REFRESH)))
				.thenReturn(new GmailApiTransport.AccessToken(ACCESS, 3600));
		GmailApiEmailSender sender = new GmailApiEmailSender(transport, ready());
		assertTrue(sender.isEnabled());
		sender.send(new EmailMessage("to@example.com", "Hello", "plain body", "<p>html body</p>"));
		ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
		verify(transport).sendRaw(eq(ACCESS), raw.capture());
		String mime = new String(Base64.getUrlDecoder().decode(raw.getValue()), StandardCharsets.UTF_8);
		assertTrue(mime.contains("From: saga@example.com"));
		assertTrue(mime.contains("To: to@example.com"));
		assertTrue(mime.contains("Hello"));
		assertTrue(mime.contains("plain body"));
		assertTrue(mime.contains("<p>html body</p>"));
		assertNoSecretInLogs();
	}

	@Test
	void providerSuccessDoesNotLogTokens() {
		GmailApiTransport transport = Mockito.mock(GmailApiTransport.class);
		when(transport.refreshAccessToken(any(), any(), any()))
				.thenReturn(new GmailApiTransport.AccessToken(ACCESS, 3600));
		new GmailApiEmailSender(transport, ready()).send(new EmailMessage("to@example.com", "Hi", "text", null));
		assertNoSecretInLogs();
	}

	@Test
	void provider4xxIsMappedWithoutLeakingSecret() {
		GmailApiTransport transport = Mockito.mock(GmailApiTransport.class);
		when(transport.refreshAccessToken(any(), any(), any()))
				.thenReturn(new GmailApiTransport.AccessToken(ACCESS, 3600));
		Mockito.doThrow(new EmailSendException(EmailFailureCodes.PROVIDER_AUTH, "Mail provider rejected the message."))
				.when(transport)
				.sendRaw(any(), any());
		GmailApiEmailSender sender = new GmailApiEmailSender(transport, ready());
		EmailSendException ex = assertThrows(
				EmailSendException.class,
				() -> sender.send(new EmailMessage("to@example.com", "Hi", "text", "<p>x</p>")));
		assertEquals(EmailFailureCodes.PROVIDER_AUTH, ex.getFailureCode());
		assertEquals("Mail provider rejected the message.", ex.getMessage());
		assertFalse(ex.getMessage().contains(REFRESH));
		assertFalse(ex.getMessage().contains(ACCESS));
		assertNoSecretInLogs();
	}

	@Test
	void provider5xxIsMappedToProviderError() {
		GmailApiTransport transport = Mockito.mock(GmailApiTransport.class);
		when(transport.refreshAccessToken(any(), any(), any()))
				.thenReturn(new GmailApiTransport.AccessToken(ACCESS, 3600));
		Mockito.doThrow(new EmailSendException(EmailFailureCodes.PROVIDER_ERROR, "Mail provider rejected the message."))
				.when(transport)
				.sendRaw(any(), any());
		EmailSendException ex = assertThrows(
				EmailSendException.class,
				() -> new GmailApiEmailSender(transport, ready())
						.send(new EmailMessage("to@example.com", "Hi", "text", null)));
		assertEquals(EmailFailureCodes.PROVIDER_ERROR, ex.getFailureCode());
		assertNoSecretInLogs();
	}

	@Test
	void timeoutIsMappedSafely() {
		GmailApiTransport transport = Mockito.mock(GmailApiTransport.class);
		when(transport.refreshAccessToken(any(), any(), any()))
				.thenThrow(new ResourceAccessException("I/O error on POST"));
		EmailSendException ex = assertThrows(
				EmailSendException.class,
				() -> new GmailApiEmailSender(transport, ready())
						.send(new EmailMessage("to@example.com", "Hi", "text", null)));
		assertEquals(EmailFailureCodes.PROVIDER_TIMEOUT, ex.getFailureCode());
		assertEquals("Mail provider rejected the message.", ex.getMessage());
		verify(transport, never()).sendRaw(any(), any());
		assertNoSecretInLogs();
	}

	@Test
	void unreadySenderIsDisabledWithoutCallingGmail() {
		SagaMailProperties properties = new SagaMailProperties();
		properties.setEnabled(true);
		properties.setProvider("gmail-api");
		properties.setFrom("saga@example.com");
		GmailApiTransport transport = Mockito.mock(GmailApiTransport.class);
		GmailApiEmailSender sender = new GmailApiEmailSender(transport, properties);
		assertFalse(sender.isEnabled());
		assertThrows(EmailSendException.class, () -> sender.send(new EmailMessage("to@example.com", "x", "y", null)));
		verify(transport, never()).refreshAccessToken(any(), any(), any());
		verify(transport, never()).sendRaw(any(), any());
	}

	private void assertNoSecretInLogs() {
		String joined = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + " " + b);
		assertFalse(joined.contains(REFRESH));
		assertFalse(joined.contains(ACCESS));
		assertFalse(joined.contains(SECRET));
		assertFalse(joined.toLowerCase().contains("bearer "));
	}

	private static SagaMailProperties ready() {
		SagaMailProperties properties = new SagaMailProperties();
		properties.setEnabled(true);
		properties.setProvider("gmail-api");
		properties.setFrom("saga@example.com");
		properties.getGmailApi().setClientId("client-id");
		properties.getGmailApi().setClientSecret(SECRET);
		properties.getGmailApi().setRefreshToken(REFRESH);
		return properties;
	}
}
