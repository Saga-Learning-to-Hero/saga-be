package com.saga.be.security;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.util.StringUtils;

public final class SagaAuthentications {

	private SagaAuthentications() {}

	public static Authentication authenticated(UserAccount account) {
		boolean setupRequired = isGooglePasswordSetupRequired(account);
		SagaUserPrincipal principal = new SagaUserPrincipal(
				account.getId(),
				account.getEmail(),
				account.getUsername(),
				account.getFullName(),
				account.getAvatarUrl(),
				account.getAccountRole(),
				setupRequired);
		List<SimpleGrantedAuthority> authorities =
				List.of(new SimpleGrantedAuthority("ROLE_" + account.getAccountRole().name()));
		return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
	}

	public static boolean isGooglePasswordSetupRequired(UserAccount account) {
		if (account == null) {
			return false;
		}
		AccountRole role = account.getAccountRole();
		if (role != AccountRole.STUDENT && role != AccountRole.LECTURER) {
			return false;
		}
		return StringUtils.hasText(account.getGoogleSubject()) && !StringUtils.hasText(account.getPasswordHash());
	}
}
