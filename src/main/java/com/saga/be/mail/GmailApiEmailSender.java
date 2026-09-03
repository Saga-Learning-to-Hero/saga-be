package com.saga.be.mail;

import com.saga.be.config.SagaMailProperties;
import java.time.Instant;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

public class GmailApiEmailSender implements EmailSender {

	private static final Logger log = LoggerFactory.getLogger(GmailApiEmailSender.class);
	private static final int EXPIRY_SKEW_SECONDS = 60;

	private final GmailApiTransport transport;
	private final SagaMailProperties properties;
	private final Object tokenLock = new Object();
	private String cachedAccessToken;
	private Instant accessTokenExpiresAt = Instant.EPOCH;

	public GmailApiEmailSender(GmailApiTransport transport, SagaMailProperties properties) {
		this.transport = transport;
		this.properties = properties;
	}

	@Override
	public boolean isEnabled() {
		return properties.isEnabled()
				&& StringUtils.hasText(properties.getFrom())
				&& properties.getGmailApi().isConfigured();
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
			byte[] rfc822 = GmailMime.rfc822(properties.getFrom().trim(), message);
			String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(rfc822);
			transport.sendRaw(accessToken(), raw);
			log.info("email result=sent provider=gmail-api");
		} catch (EmailSendException ex) {
			throw ex;
		} catch (Exception ex) {
			String code = EmailFailureCodes.from(ex);
			log.warn("email result=failure category={} provider=gmail-api", code);
			throw new EmailSendException(code, "Mail provider rejected the message.", ex);
		}
	}

	private String accessToken() {
		synchronized (tokenLock) {
			Instant now = Instant.now();
			if (cachedAccessToken != null && now.isBefore(accessTokenExpiresAt.minusSeconds(EXPIRY_SKEW_SECONDS))) {
				return cachedAccessToken;
			}
			GmailApiTransport.AccessToken token = transport.refreshAccessToken(
					properties.getGmailApi().getClientId().trim(),
					properties.getGmailApi().getClientSecret().trim(),
					properties.getGmailApi().getRefreshToken().trim());
			if (token == null || !StringUtils.hasText(token.value())) {
				throw new EmailSendException(EmailFailureCodes.PROVIDER_AUTH, "Mail provider rejected the message.");
			}
			int ttl = token.expiresInSeconds() > 0 ? token.expiresInSeconds() : 3600;
			cachedAccessToken = token.value();
			accessTokenExpiresAt = now.plusSeconds(ttl);
			return cachedAccessToken;
		}
	}
}
