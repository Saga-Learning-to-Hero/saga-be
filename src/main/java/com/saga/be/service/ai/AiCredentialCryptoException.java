package com.saga.be.service.ai;

/** Deliberately safe: message is always a static code, never plaintext, key material, or the
 * underlying JCE exception's own message (which can include algorithm/padding internals). */
public class AiCredentialCryptoException extends RuntimeException {
	private final String safeCode;
	public AiCredentialCryptoException(String safeCode) { super(safeCode); this.safeCode = safeCode; }
	public String safeCode() { return safeCode; }
}
