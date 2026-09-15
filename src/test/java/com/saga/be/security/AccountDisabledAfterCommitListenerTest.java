package com.saga.be.security;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.saga.be.realtime.ProjectSseHub;
import com.saga.be.realtime.UserSseHub;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@ExtendWith(MockitoExtension.class)
class AccountDisabledAfterCommitListenerTest {

	@Mock
	private IndexedSessionRevocationService sessions;
	@Mock
	private UserSseHub userSse;
	@Mock
	private ProjectSseHub projectSse;

	@Test
	void revocationFailureStillNotifiesSseAndDoesNotRethrow() {
		UUID userId = UUID.randomUUID();
		doThrow(new IllegalStateException("redis down")).when(sessions).revokeAllForUser(userId);
		AccountDisabledAfterCommitListener listener =
				new AccountDisabledAfterCommitListener(sessions, userSse, projectSse);
		listener.onAccountDisabled(new AccountDisabledEvent(userId, Instant.parse("2026-09-15T12:00:00Z")));
		verify(sessions).revokeAllForUser(userId);
		verify(userSse).notifyDisabled(userId, Instant.parse("2026-09-15T12:00:00Z"));
		verify(projectSse).closeForUser(userId);
	}

	@Test
	void nullEventIsIgnored() {
		AccountDisabledAfterCommitListener listener =
				new AccountDisabledAfterCommitListener(sessions, userSse, projectSse);
		listener.onAccountDisabled(null);
		verify(sessions, never()).revokeAllForUser(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void listenerRunsAfterCommitOnly() throws Exception {
		Method method = AccountDisabledAfterCommitListener.class.getMethod("onAccountDisabled", AccountDisabledEvent.class);
		TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);
		org.junit.jupiter.api.Assertions.assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
	}
}
