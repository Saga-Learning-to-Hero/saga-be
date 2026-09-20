package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.entity.enums.JiraFailoverItemStatus;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * H2 proof of V25 {@code uk_jira_failover_item_outbound_claim} semantics using the same generated
 * column expression as Flyway MySQL. Proves cross-run exclusivity without holding JDBC locks across
 * provider HTTP.
 */
class JiraFailoverOutboundClaimConstraintTest {

	private Connection connection;

	@BeforeEach
	void openDb() throws SQLException {
		connection = DriverManager.getConnection(
				"jdbc:h2:mem:failover_claim_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
				"sa",
				"");
		try (Statement st = connection.createStatement()) {
			st.execute(
					"""
					CREATE TABLE jira_task_failover_item (
					  id CHAR(36) NOT NULL,
					  run_id CHAR(36) NOT NULL,
					  source_task_id CHAR(36) NOT NULL,
					  status VARCHAR(32) NOT NULL,
					  outbound_claim_task_id CHAR(36)
					    AS (
					      CASE
					        WHEN status IN (
					          'PENDING',
					          'CREATING',
					          'REMOTE_OUTCOME_UNKNOWN',
					          'REMOTE_BOUND',
					          'SUCCEEDED'
					        ) THEN source_task_id
					        ELSE NULL
					      END
					    ),
					  PRIMARY KEY (id),
					  UNIQUE (run_id, source_task_id),
					  UNIQUE (outbound_claim_task_id)
					)
					""");
		}
	}

	@AfterEach
	void closeDb() throws SQLException {
		if (connection != null) {
			connection.close();
		}
	}

	@Test
	void secondPendingClaimForSameSourceIsRejected() throws SQLException {
		UUID source = UUID.randomUUID();
		insert(UUID.randomUUID(), UUID.randomUUID(), source, "PENDING");
		assertThatThrownBy(() -> insert(UUID.randomUUID(), UUID.randomUUID(), source, "PENDING"))
				.isInstanceOf(SQLException.class);
	}

	@Test
	void creatingBlocksSecondClaim() throws SQLException {
		UUID source = UUID.randomUUID();
		insert(UUID.randomUUID(), UUID.randomUUID(), source, "CREATING");
		assertThatThrownBy(() -> insert(UUID.randomUUID(), UUID.randomUUID(), source, "PENDING"))
				.isInstanceOf(SQLException.class);
	}

	@Test
	void remoteOutcomeUnknownBlocksSecondClaim() throws SQLException {
		UUID source = UUID.randomUUID();
		insert(UUID.randomUUID(), UUID.randomUUID(), source, "REMOTE_OUTCOME_UNKNOWN");
		assertThatThrownBy(() -> insert(UUID.randomUUID(), UUID.randomUUID(), source, "PENDING"))
				.isInstanceOf(SQLException.class);
	}

	@Test
	void remoteBoundBlocksSecondClaim() throws SQLException {
		UUID source = UUID.randomUUID();
		insert(UUID.randomUUID(), UUID.randomUUID(), source, "REMOTE_BOUND");
		assertThatThrownBy(() -> insert(UUID.randomUUID(), UUID.randomUUID(), source, "PENDING"))
				.isInstanceOf(SQLException.class);
	}

	@Test
	void succeededPermanentlyBlocksSameSourceAsOutbound() throws SQLException {
		UUID sourceA = UUID.randomUUID();
		insert(UUID.randomUUID(), UUID.randomUUID(), sourceA, "SUCCEEDED");
		assertThatThrownBy(() -> insert(UUID.randomUUID(), UUID.randomUUID(), sourceA, "PENDING"))
				.isInstanceOf(SQLException.class);
	}

	@Test
	void failedReleasesClaimForRetry() throws SQLException {
		UUID source = UUID.randomUUID();
		insert(UUID.randomUUID(), UUID.randomUUID(), source, "FAILED");
		insert(UUID.randomUUID(), UUID.randomUUID(), source, "PENDING");
		assertThat(countClaims(source)).isEqualTo(1);
	}

	@Test
	void skippedReleasesClaimForRetry() throws SQLException {
		UUID source = UUID.randomUUID();
		insert(UUID.randomUUID(), UUID.randomUUID(), source, "SKIPPED");
		insert(UUID.randomUUID(), UUID.randomUUID(), source, "PENDING");
		assertThat(countClaims(source)).isEqualTo(1);
	}

	@Test
	void abandonedReleasesClaimForRetry() throws SQLException {
		UUID source = UUID.randomUUID();
		insert(UUID.randomUUID(), UUID.randomUUID(), source, "ABANDONED");
		insert(UUID.randomUUID(), UUID.randomUUID(), source, "PENDING");
		assertThat(countClaims(source)).isEqualTo(1);
	}

	@Test
	void succeededSourceADoesNotBlockSuccessorBAsNewSource() throws SQLException {
		UUID sourceA = UUID.randomUUID();
		UUID sourceB = UUID.randomUUID();
		insert(UUID.randomUUID(), UUID.randomUUID(), sourceA, "SUCCEEDED");
		insert(UUID.randomUUID(), UUID.randomUUID(), sourceB, "PENDING");
		assertThat(countClaims(sourceA)).isEqualTo(1);
		assertThat(countClaims(sourceB)).isEqualTo(1);
	}

	private void insert(UUID id, UUID runId, UUID sourceTaskId, String status) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"INSERT INTO jira_task_failover_item (id, run_id, source_task_id, status) VALUES (?,?,?,?)")) {
			ps.setString(1, id.toString());
			ps.setString(2, runId.toString());
			ps.setString(3, sourceTaskId.toString());
			ps.setString(4, status);
			ps.executeUpdate();
		}
	}

	private int countClaims(UUID sourceTaskId) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT COUNT(*) FROM jira_task_failover_item WHERE outbound_claim_task_id = ?")) {
			ps.setString(1, sourceTaskId.toString());
			try (var rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}
}
