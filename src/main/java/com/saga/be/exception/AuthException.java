package com.saga.be.exception;

import com.saga.be.auth.AuthErrorCode;
import org.springframework.http.HttpStatus;

public class AuthException extends RuntimeException {

	private final AuthErrorCode code;
	private final HttpStatus status;

	public AuthException(AuthErrorCode code, HttpStatus status, String message) {
		super(message);
		this.code = code;
		this.status = status;
	}

	public AuthErrorCode getCode() {
		return code;
	}

	public HttpStatus getStatus() {
		return status;
	}
}
