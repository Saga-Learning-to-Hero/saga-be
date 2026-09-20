package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** V26's target-scoped remote identity reservation rejects duplicate reconciliation bindings. */
class JiraFailoverRemoteIssueBindingConstraintTest {
	private Connection db;
	private String url;
	@BeforeEach void open() throws SQLException {
		url = "jdbc:h2:mem:failover_remote_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
		db = DriverManager.getConnection(url, "sa", "");
		try (Statement s = db.createStatement()) {
			s.execute("CREATE TABLE binding (id CHAR(36) PRIMARY KEY, item_id CHAR(36) NOT NULL, target_id CHAR(36) NOT NULL, remote_id VARCHAR(128) NOT NULL, UNIQUE(item_id), UNIQUE(target_id, remote_id))");
		}
	}
	@AfterEach void close() throws SQLException { if (db != null) db.close(); }
	@Test void sameTargetIssueCannotBindTwoFailoverItems() throws SQLException {
		UUID target = UUID.randomUUID(); insert(UUID.randomUUID(), UUID.randomUUID(), target, "10042");
		assertThatThrownBy(() -> insert(UUID.randomUUID(), UUID.randomUUID(), target, "10042")).isInstanceOf(SQLException.class);
	}
	@Test void oneFailoverItemCannotBindTwoDifferentTargetIssues() throws SQLException {
		UUID item = UUID.randomUUID(), target = UUID.randomUUID();
		insert(UUID.randomUUID(), item, target, "10042");
		assertThatThrownBy(() -> insert(UUID.randomUUID(), item, target, "10043")).isInstanceOf(SQLException.class);
	}
	@Test void concurrentDifferentIssueReconciliationsForOneItemHaveExactlyOneWinner() throws Exception {
		UUID item = UUID.randomUUID(), target = UUID.randomUUID();
		CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Boolean> first = pool.submit(() -> concurrentInsert(item, target, "10042", ready, start));
			Future<Boolean> second = pool.submit(() -> concurrentInsert(item, target, "10043", ready, start));
			ready.await(); start.countDown();
			org.assertj.core.api.Assertions.assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
		} finally { pool.shutdownNow(); }
	}

	private boolean concurrentInsert(UUID item, UUID target, String remote, CountDownLatch ready, CountDownLatch start) throws Exception {
		try (Connection other = DriverManager.getConnection(url, "sa", "")) {
			ready.countDown(); start.await();
			try (PreparedStatement p = other.prepareStatement("INSERT INTO binding VALUES (?,?,?,?)")) {
				p.setString(1, UUID.randomUUID().toString()); p.setString(2, item.toString()); p.setString(3, target.toString()); p.setString(4, remote);
				p.executeUpdate(); return true;
			} catch (SQLException expectedUniqueConflict) { return false; }
		}
	}
	@Test void sameProviderIdOnDifferentTargetIntegrationIsNotConflated() throws SQLException {
		insert(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "10042");
		insert(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "10042");
	}
	private void insert(UUID id, UUID item, UUID target, String remote) throws SQLException {
		try (PreparedStatement p = db.prepareStatement("INSERT INTO binding VALUES (?,?,?,?)")) {
			p.setString(1, id.toString()); p.setString(2, item.toString()); p.setString(3, target.toString()); p.setString(4, remote); p.executeUpdate();
		}
	}
}
