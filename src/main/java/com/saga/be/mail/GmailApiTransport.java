package com.saga.be.mail;

public interface GmailApiTransport {

	AccessToken refreshAccessToken(String clientId, String clientSecret, String refreshToken);

	void sendRaw(String accessToken, String rawRfc822Base64Url);

	record AccessToken(String value, int expiresInSeconds) {}
}
