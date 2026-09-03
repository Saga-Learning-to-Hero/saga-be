package com.saga.be.exception;

import com.saga.be.auth.AuthErrorCode;
import com.saga.be.dto.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(AuthException.class)
	public ResponseEntity<ApiErrorResponse> handleAuth(AuthException ex) {
		log.info("auth method=API result=failure category={}", ex.getCode());
		return ResponseEntity.status(ex.getStatus()).body(new ApiErrorResponse(ex.getCode().name(), ex.getMessage()));
	}

	@ExceptionHandler(IntegrationException.class)
	public ResponseEntity<ApiErrorResponse> handleIntegration(IntegrationException ex) {
		log.info("integration result=failure category={}", ex.getCode());
		return ResponseEntity.status(ex.getStatus()).body(new ApiErrorResponse(ex.getCode().name(), ex.getMessage()));
	}

	@ExceptionHandler(AcademicException.class)
	public ResponseEntity<ApiErrorResponse> handleAcademic(AcademicException ex) {
		log.info("academic result=failure category={}", ex.getCode());
		return ResponseEntity.status(ex.getStatus()).body(new ApiErrorResponse(ex.getCode().name(), ex.getMessage()));
	}

	@ExceptionHandler({
		MethodArgumentNotValidException.class,
		HandlerMethodValidationException.class,
		HttpMessageNotReadableException.class
	})
	public ResponseEntity<ApiErrorResponse> handleValidation(Exception ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ApiErrorResponse("REQUEST_INVALID", "Request is invalid."));
	}

	@ExceptionHandler({RedisConnectionFailureException.class, RedisSystemException.class})
	public ResponseEntity<ApiErrorResponse> handleSessionStore(RuntimeException ex) {
		log.error("auth method=SESSION result=failure category=REDIS_UNAVAILABLE type={}", ex.getClass().getSimpleName());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(new ApiErrorResponse("SESSION_STORE_UNAVAILABLE", "Session store is temporarily unavailable."));
	}
}
