package com.saga.be.service.admin;

import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import org.springframework.http.HttpStatus;

public final class AdminPaging {

	static final int DEFAULT_PAGE = 0;
	static final int DEFAULT_SIZE = 50;
	static final int MAX_SIZE = 200;
	static final int SEARCH_MAX_LENGTH = 255;

	private AdminPaging() {}

	public static int page(Integer page) {
		int value = page == null ? DEFAULT_PAGE : page;
		if (value < 0) {
			throw invalidPage();
		}
		return value;
	}

	public static int size(Integer size) {
		int value = size == null ? DEFAULT_SIZE : size;
		if (value < 1 || value > MAX_SIZE) {
			throw invalidPage();
		}
		return value;
	}

	public static AcademicException invalidPage() {
		return new AcademicException(
				AcademicErrorCode.REQUEST_INVALID,
				HttpStatus.BAD_REQUEST,
				"page must be >= 0 and size must be between 1 and " + MAX_SIZE + ".");
	}

	public static AcademicException invalidRequest(String message) {
		return new AcademicException(AcademicErrorCode.REQUEST_INVALID, HttpStatus.BAD_REQUEST, message);
	}
}
