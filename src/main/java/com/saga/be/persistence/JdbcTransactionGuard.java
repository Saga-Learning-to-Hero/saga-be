package com.saga.be.persistence;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Fails fast when provider HTTP would otherwise run while a Spring JDBC transaction is open.
 * Production logs stay quiet; tests assert {@code isActualTransactionActive() == false}.
 */
public final class JdbcTransactionGuard {

	private JdbcTransactionGuard() {}

	public static void requireInactive(String operation) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException(operation + " must not run inside a database transaction.");
		}
	}
}
