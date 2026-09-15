package com.saga.be.config;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

final class TestFcmCredentials {

	private TestFcmCredentials() {}

	static String rsaPrivateKeyPem() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			KeyPair pair = generator.generateKeyPair();
			String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(pair.getPrivate().getEncoded());
			return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	static String escapedPem(String pem) {
		return pem.replace("\n", "\\n");
	}
}
