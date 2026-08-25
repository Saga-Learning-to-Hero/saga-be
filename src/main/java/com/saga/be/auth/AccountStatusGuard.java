package com.saga.be.auth;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.AuthException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AccountStatusGuard {

	public void requireActive(UserAccount account) {
		if (account.getAccountStatus() != AccountStatus.ACTIVE) {
			throw new AuthException(AuthErrorCode.ACCOUNT_DISABLED, HttpStatus.FORBIDDEN, "Account is not available.");
		}
	}
}
