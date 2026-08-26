package com.saga.be.exception;

import com.saga.be.integration.IntegrationErrorCode;
import org.springframework.http.HttpStatus;

public class IntegrationException extends RuntimeException {

	private final IntegrationErrorCode code;
	private final HttpStatus status;

	public IntegrationException(IntegrationErrorCode code, HttpStatus status, String message) {
		super(message);
		this.code = code;
		this.status = status;
	}

	public IntegrationErrorCode getCode() {
		return code;
	}

	public HttpStatus getStatus() {
		return status;
	}
}
