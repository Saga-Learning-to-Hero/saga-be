package com.saga.be.mail;

public class EmailSendException extends RuntimeException {

	private final String failureCode;

	public EmailSendException(String failureCode, String message) {
		super(message);
		this.failureCode = failureCode;
	}

	public EmailSendException(String failureCode, String message, Throwable cause) {
		super(message, cause);
		this.failureCode = failureCode;
	}

	public String getFailureCode() {
		return failureCode;
	}
}
