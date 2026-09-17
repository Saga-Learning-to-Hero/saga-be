package com.saga.be.persistence;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Test-only TM that binds {@link TransactionSynchronizationManager#isActualTransactionActive()}
 * for the duration of {@code TransactionTemplate} callbacks.
 */
public final class TrackingPlatformTransactionManager extends AbstractPlatformTransactionManager {

	private int open;

	public int openCount() {
		return open;
	}

	@Override
	protected Object doGetTransaction() throws TransactionException {
		return new SimpleTransactionStatus();
	}

	@Override
	protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
		open++;
		TransactionSynchronizationManager.setActualTransactionActive(true);
	}

	@Override
	protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
		close();
	}

	@Override
	protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
		close();
	}

	private void close() {
		open = Math.max(0, open - 1);
		if (open == 0) {
			TransactionSynchronizationManager.setActualTransactionActive(false);
		}
	}
}
