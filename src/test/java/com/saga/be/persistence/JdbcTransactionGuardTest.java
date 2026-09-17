package com.saga.be.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcTransactionGuardTest {

	@AfterEach
	void clearTxFlag() {
		TransactionSynchronizationManager.setActualTransactionActive(false);
	}

	@Test
	void requireInactive_allowsWhenNoTransaction() {
		JdbcTransactionGuard.requireInactive("probe");
	}

	@Test
	void requireInactive_throwsWhenTransactionActive() {
		TransactionSynchronizationManager.setActualTransactionActive(true);
		assertThatThrownBy(() -> JdbcTransactionGuard.requireInactive("jira token refresh"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("jira token refresh")
				.hasMessageContaining("must not run inside a database transaction");
	}

	@Test
	void trackingManager_setsActualTransactionActiveOnlyInsideTemplate() {
		TrackingPlatformTransactionManager tm = new TrackingPlatformTransactionManager();
		TransactionTemplate writes = new TransactionTemplate(tm);
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		writes.executeWithoutResult(status -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
			assertThat(tm.openCount()).isEqualTo(1);
		});
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		assertThat(tm.openCount()).isZero();
	}
}
