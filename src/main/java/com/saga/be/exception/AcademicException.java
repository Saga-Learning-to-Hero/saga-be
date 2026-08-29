package com.saga.be.exception;

import org.springframework.http.HttpStatus;

public class AcademicException extends RuntimeException {

	private final AcademicErrorCode code;
	private final HttpStatus status;

	public AcademicException(AcademicErrorCode code, HttpStatus status, String message) {
		super(message);
		this.code = code;
		this.status = status;
	}

	public AcademicErrorCode getCode() {
		return code;
	}

	public HttpStatus getStatus() {
		return status;
	}
}
