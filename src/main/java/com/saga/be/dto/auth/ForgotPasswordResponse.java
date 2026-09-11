package com.saga.be.dto.auth;

/** Always the same shape/message regardless of whether the account exists. */
public record ForgotPasswordResponse(String message) {

	private static final String PUBLIC_MESSAGE = "Nếu email tồn tại, hướng dẫn đặt lại mật khẩu đã được gửi.";

	public static ForgotPasswordResponse generic() {
		return new ForgotPasswordResponse(PUBLIC_MESSAGE);
	}
}
