package com.saga.be.exception;

import com.saga.be.integration.IntegrationErrorCode;
import org.springframework.http.HttpStatus;

public class IntegrationException extends RuntimeException {

	private final IntegrationErrorCode code;
	private final HttpStatus status;
	private final Object details;

	public IntegrationException(IntegrationErrorCode code, HttpStatus status, String message) {
		this(code, status, message, null);
	}

	public IntegrationException(IntegrationErrorCode code, HttpStatus status, String message, Object details) {
		super(message);
		this.code = code;
		this.status = status;
		this.details = details;
	}

	public IntegrationErrorCode getCode() {
		return code;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public Object getDetails() {
		return details;
	}
}
