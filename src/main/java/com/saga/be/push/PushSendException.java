package com.saga.be.push;

public class PushSendException extends RuntimeException {

	private final String failureCode;
	private final boolean retryable;
	private final boolean tokenInvalid;

	public PushSendException(String failureCode, boolean retryable, boolean tokenInvalid, String message) {
		this(failureCode, retryable, tokenInvalid, message, null);
	}

	public PushSendException(
			String failureCode, boolean retryable, boolean tokenInvalid, String message, Throwable cause) {
		super(message, cause);
		this.failureCode = failureCode == null ? FcmFailureCodes.UNKNOWN : failureCode;
		this.retryable = retryable;
		this.tokenInvalid = tokenInvalid;
	}

	public String getFailureCode() {
		return failureCode;
	}

	public boolean isRetryable() {
		return retryable;
	}

	public boolean isTokenInvalid() {
		return tokenInvalid;
	}
}
