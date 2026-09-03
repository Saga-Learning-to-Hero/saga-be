package com.saga.be.integration.github;

import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.Base64;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

/**
 * Parses {@code SAGA_GITHUB_PRIVATE_KEY_BASE64} as Base64 of a complete PEM file.
 * Supports GitHub-native PKCS#1 ({@code BEGIN RSA PRIVATE KEY}) and PKCS#8 ({@code BEGIN PRIVATE KEY}).
 */
public class GitHubAppPrivateKeyParser {

	private static final Logger log = LoggerFactory.getLogger(GitHubAppPrivateKeyParser.class);

	public enum KeyFormat {
		PKCS1,
		PKCS8,
		UNKNOWN
	}

	public PrivateKey parse(String privateKeyBase64) {
		boolean present = StringUtils.hasText(privateKeyBase64);
		KeyFormat format = KeyFormat.UNKNOWN;
		try {
			if (!present) {
				throw parseFailure();
			}
			String pem = decodeOuterPem(privateKeyBase64);
			format = detectFormat(pem);
			if (format == KeyFormat.UNKNOWN) {
				throw parseFailure();
			}
			byte[] der = decodePemBody(pem);
			PrivateKey key = switch (format) {
				case PKCS1 -> parsePkcs1(der);
				case PKCS8 -> parsePkcs8(der);
				case UNKNOWN -> throw parseFailure();
			};
			log.info(
					"githubPrivateKeyPresent=true githubPrivateKeyFormat={} githubPrivateKeyParseSuccess=true",
					format);
			return key;
		} catch (IntegrationException ex) {
			log.warn(
					"githubPrivateKeyPresent={} githubPrivateKeyFormat={} githubPrivateKeyParseSuccess=false",
					present,
					format);
			throw ex;
		} catch (Exception ex) {
			log.warn(
					"githubPrivateKeyPresent={} githubPrivateKeyFormat={} githubPrivateKeyParseSuccess=false",
					present,
					format);
			throw parseFailure();
		}
	}

	static KeyFormat detectFormat(String pem) {
		if (pem.contains("BEGIN RSA PRIVATE KEY")) {
			return KeyFormat.PKCS1;
		}
		if (pem.contains("BEGIN PRIVATE KEY")) {
			return KeyFormat.PKCS8;
		}
		return KeyFormat.UNKNOWN;
	}

	private static String decodeOuterPem(String privateKeyBase64) {
		try {
			byte[] decoded = Base64.getMimeDecoder().decode(privateKeyBase64.trim());
			String pem = new String(decoded, StandardCharsets.UTF_8).replace("\uFEFF", "");
			if (!pem.contains("BEGIN")) {
				throw parseFailure();
			}
			return pem;
		} catch (IllegalArgumentException ex) {
			throw parseFailure();
		}
	}

	private static byte[] decodePemBody(String pem) {
		String normalized = pem.replace("-----BEGIN RSA PRIVATE KEY-----", "")
				.replace("-----END RSA PRIVATE KEY-----", "")
				.replace("-----BEGIN PRIVATE KEY-----", "")
				.replace("-----END PRIVATE KEY-----", "")
				.replaceAll("\\s", "");
		if (!StringUtils.hasText(normalized)) {
			throw parseFailure();
		}
		try {
			return Base64.getMimeDecoder().decode(normalized);
		} catch (IllegalArgumentException ex) {
			throw parseFailure();
		}
	}

	private static PrivateKey parsePkcs1(byte[] der) throws Exception {
		RSAPrivateKey rsa = RSAPrivateKey.getInstance(der);
		if (rsa == null || rsa.getModulus() == null || rsa.getPrivateExponent() == null) {
			throw parseFailure();
		}
		RSAPrivateCrtKeySpec spec = new RSAPrivateCrtKeySpec(
				rsa.getModulus(),
				rsa.getPublicExponent(),
				rsa.getPrivateExponent(),
				rsa.getPrime1(),
				rsa.getPrime2(),
				rsa.getExponent1(),
				rsa.getExponent2(),
				rsa.getCoefficient());
		return KeyFactory.getInstance("RSA").generatePrivate(spec);
	}

	private static PrivateKey parsePkcs8(byte[] der) throws Exception {
		PrivateKeyInfo info = PrivateKeyInfo.getInstance(der);
		if (info == null
				|| info.getPrivateKeyAlgorithm() == null
				|| !PKCSObjectIdentifiers.rsaEncryption.equals(info.getPrivateKeyAlgorithm().getAlgorithm())) {
			throw parseFailure();
		}
		return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
	}

	private static IntegrationException parseFailure() {
		return new IntegrationException(
				IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
				HttpStatus.SERVICE_UNAVAILABLE,
				"GitHub App private key could not be parsed.");
	}
}
