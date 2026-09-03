package com.saga.be.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

public class RestClientGmailApiTransport implements GmailApiTransport {

	static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
	static final String SEND_URL = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";

	private final RestClient client;
	private final ObjectMapper mapper;

	public RestClientGmailApiTransport(ObjectMapper mapper) {
		this(defaultClient(), mapper);
	}

	RestClientGmailApiTransport(RestClient client, ObjectMapper mapper) {
		this.client = client;
		this.mapper = mapper;
	}

	private static RestClient defaultClient() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(5));
		factory.setReadTimeout(Duration.ofSeconds(20));
		return RestClient.builder().requestFactory(factory).build();
	}

	@Override
	public AccessToken refreshAccessToken(String clientId, String clientSecret, String refreshToken) {
		LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "refresh_token");
		form.add("client_id", clientId);
		form.add("client_secret", clientSecret);
		form.add("refresh_token", refreshToken);
		try {
			String body = client.post()
					.uri(TOKEN_URL)
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.body(form)
					.retrieve()
					.body(String.class);
			JsonNode node = mapper.readTree(body == null ? "{}" : body);
			String token = node.path("access_token").asText(null);
			if (token == null || token.isBlank()) {
				throw new EmailSendException(EmailFailureCodes.PROVIDER_AUTH, "Mail provider rejected the message.");
			}
			int expiresIn = node.path("expires_in").asInt(3600);
			return new AccessToken(token, expiresIn);
		} catch (EmailSendException ex) {
			throw ex;
		} catch (RestClientResponseException ex) {
			int status = ex.getStatusCode().value();
			String code = status == 400 || status == 401 || status == 403
					? EmailFailureCodes.PROVIDER_AUTH
					: EmailFailureCodes.fromHttpStatus(status);
			throw new EmailSendException(code, "Mail provider rejected the message.", ex);
		} catch (ResourceAccessException ex) {
			throw new EmailSendException(EmailFailureCodes.PROVIDER_TIMEOUT, "Mail provider rejected the message.", ex);
		} catch (Exception ex) {
			throw new EmailSendException(EmailFailureCodes.from(ex), "Mail provider rejected the message.", ex);
		}
	}

	@Override
	public void sendRaw(String accessToken, String rawRfc822Base64Url) {
		Map<String, String> payload = new LinkedHashMap<>();
		payload.put("raw", rawRfc822Base64Url);
		try {
			String json = mapper.writeValueAsString(payload);
			client.post()
					.uri(SEND_URL)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
					.contentType(MediaType.APPLICATION_JSON)
					.body(json)
					.retrieve()
					.toBodilessEntity();
		} catch (EmailSendException ex) {
			throw ex;
		} catch (RestClientResponseException ex) {
			int status = ex.getStatusCode().value();
			String code = status == 401 || status == 403
					? EmailFailureCodes.PROVIDER_AUTH
					: EmailFailureCodes.fromHttpStatus(status);
			throw new EmailSendException(code, "Mail provider rejected the message.", ex);
		} catch (ResourceAccessException ex) {
			throw new EmailSendException(EmailFailureCodes.PROVIDER_TIMEOUT, "Mail provider rejected the message.", ex);
		} catch (Exception ex) {
			throw new EmailSendException(EmailFailureCodes.from(ex), "Mail provider rejected the message.", ex);
		}
	}
}
