package com.saga.be.dto.auth;

public record ResetPasswordResponse(String message) {

	public static ResetPasswordResponse success() {
		return new ResetPasswordResponse("Mật khẩu đã được cập nhật. Vui lòng đăng nhập lại.");
	}
}
