package com.saga.be.service.ai;

/** Deliberately safe provider failure category; never carries a provider response body. */
public class AiProviderException extends RuntimeException {
	private final String safeCode;
	public AiProviderException(String safeCode) { super(safeCode); this.safeCode = safeCode; }
	public AiProviderException(String safeCode, Throwable cause) { super(safeCode, cause); this.safeCode = safeCode; }
	public String safeCode() { return safeCode; }
}
