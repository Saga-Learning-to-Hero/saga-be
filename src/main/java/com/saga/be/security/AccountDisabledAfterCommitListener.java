package com.saga.be.security;

import com.saga.be.realtime.ProjectSseHub;
import com.saga.be.realtime.UserSseHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * After the status row and audit commit: revoke indexed sessions and notify open SSE streams
 * on this instance. Redis failure must not reactivate the account. Successful revoke makes the
 * next request unauthenticated; 403 ACCOUNT_DISABLED is only for leftover authenticated sessions.
 */
@Component
public class AccountDisabledAfterCommitListener {

	private static final Logger log = LoggerFactory.getLogger(AccountDisabledAfterCommitListener.class);

	private final IndexedSessionRevocationService sessions;
	private final UserSseHub userSse;
	private final ProjectSseHub projectSse;

	public AccountDisabledAfterCommitListener(
			IndexedSessionRevocationService sessions, UserSseHub userSse, ProjectSseHub projectSse) {
		this.sessions = sessions;
		this.userSse = userSse;
		this.projectSse = projectSse;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onAccountDisabled(AccountDisabledEvent event) {
		if (event == null || event.userId() == null) {
			return;
		}
		try {
			sessions.revokeAllForUser(event.userId());
		} catch (RuntimeException ex) {
			log.error("auth method=SESSION result=failure category=ACCOUNT_DISABLED", ex);
		}
		try {
			userSse.notifyDisabled(event.userId(), event.occurredAt());
		} catch (RuntimeException ex) {
			log.error("auth method=SSE result=failure category=ACCOUNT_DISABLED", ex);
		}
		try {
			projectSse.closeForUser(event.userId());
		} catch (RuntimeException ex) {
			log.error("auth method=SSE result=failure category=PROJECT_SSE_CLOSE", ex);
		}
	}
}
