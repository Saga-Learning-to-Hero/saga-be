package com.saga.be.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Disposable MySQL 8: V1→V20 seed an audit row, then V21 adds nullable snapshots.
 * Opt-in: {@code saga.verify.mysql=true} and {@code saga.verify.mysql.url}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "saga.verify.mysql", matches = "true")
@TestPropertySource(
		properties = {
			"spring.profiles.active=",
			"spring.datasource.url=${saga.verify.mysql.url}",
			"spring.datasource.username=${SAGA_VERIFY_MYSQL_USERNAME:${saga.verify.mysql.username:root}}",
			"spring.datasource.password=${SAGA_VERIFY_MYSQL_PASSWORD:${saga.verify.mysql.password:}}",
			"spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
			"spring.jpa.hibernate.ddl-auto=none",
			"spring.jpa.open-in-view=false",
			"spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=CHAR",
			"spring.flyway.enabled=false",
			"saga.auth.bootstrap-admin.enabled=false"
		})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditLogV21MysqlIT {

	private static final String ACTOR_ID = "aaaaaaaa-0000-4000-8000-000000000021";
	private static final String AUDIT_ID = "bbbbbbbb-0000-4000-8000-000000000021";
	private static final String PROJECT_ID = "cccccccc-0000-4000-8000-000000000021";
	private static final String TEAM_ID = "dddddddd-0000-4000-8000-000000000021";

	@SpringBootConfiguration
	@EnableAutoConfiguration(
			excludeName = {
				"org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
				"org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration",
				"org.springframework.boot.session.autoconfigure.SessionAutoConfiguration",
				"org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
				"org.springframework.boot.neo4j.autoconfigure.Neo4jAutoConfiguration",
				"org.springframework.boot.data.neo4j.autoconfigure.DataNeo4jAutoConfiguration",
				"org.springframework.boot.data.neo4j.autoconfigure.DataNeo4jRepositoriesAutoConfiguration",
				"org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
				"org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration",
				"org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration"
			})
	@EntityScan(basePackages = "com.saga.be.entity")
	@EnableJpaRepositories(basePackages = "com.saga.be.repository")
	static class TxSlice {}

	@Autowired
	private DataSource dataSource;

	private Map<Integer, Integer> checksumsThroughV20;

	@BeforeEach
	void migrateV1ThroughV20ThenSeedAuditRow() throws Exception {
		Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.cleanDisabled(false)
				.load()
				.clean();
		Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.target("20")
				.load()
				.migrate();
		checksumsThroughV20 = checksumsByVersion();
		try (Connection connection = dataSource.getConnection()) {
			try (PreparedStatement user = connection.prepareStatement(
					"""
					INSERT INTO user_account
					  (id, email, username, full_name, account_role, account_status, password_hash, created_at, updated_at)
					VALUES (?, ?, ?, ?, 'ADMIN', 'ACTIVE', 'not-a-secret-in-api', NOW(6), NOW(6))
					""")) {
				user.setString(1, ACTOR_ID);
				user.setString(2, "v21-audit@saga.local");
				user.setString(3, "v21audit");
				user.setString(4, "V21 Audit");
				user.executeUpdate();
			}
			try (PreparedStatement audit = connection.prepareStatement(
					"""
					INSERT INTO audit_log
					  (id, actor_user_id, actor_full_name_snapshot, context_project_id, context_team_id,
					   action, entity_type, entity_id, source, occurred_at, created_at, updated_at)
					VALUES (?, ?, 'V21 Audit', ?, ?, 'GITHUB_REPOSITORY_CONNECTED', 'git_repo', ?, 'API', NOW(6), NOW(6), NOW(6))
					""")) {
				audit.setString(1, AUDIT_ID);
				audit.setString(2, ACTOR_ID);
				audit.setString(3, PROJECT_ID);
				audit.setString(4, TEAM_ID);
				audit.setString(5, PROJECT_ID);
				audit.executeUpdate();
			}
		}
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
	}

	@Test
	void v21KeepsLegacyAuditRowAndAddsNullableSnapshots() throws Exception {
		Map<Integer, Integer> after = checksumsByVersion();
		for (int version = 1; version <= 20; version++) {
			assertEquals(checksumsThroughV20.get(version), after.get(version), "V" + version + " checksum changed");
		}
		assertTrue(after.containsKey(21));

		try (Connection connection = dataSource.getConnection()) {
			assertEquals("YES", nullable(connection, "context_project_name_snapshot"));
			assertEquals("YES", nullable(connection, "context_team_no_snapshot"));
			assertEquals("YES", nullable(connection, "context_team_name_snapshot"));
			assertEquals("varchar", dataType(connection, "context_project_name_snapshot"));
			assertEquals(255, maxLength(connection, "context_project_name_snapshot"));
			assertEquals("int", dataType(connection, "context_team_no_snapshot"));
			assertEquals("varchar", dataType(connection, "context_team_name_snapshot"));
			try (PreparedStatement row = connection.prepareStatement(
					"""
					SELECT context_project_id, context_project_name_snapshot,
					       context_team_id, context_team_no_snapshot, context_team_name_snapshot, action
					FROM audit_log WHERE id = ?
					""")) {
				row.setString(1, AUDIT_ID);
				try (ResultSet rs = row.executeQuery()) {
					assertTrue(rs.next());
					assertEquals(PROJECT_ID, rs.getString("context_project_id"));
					assertEquals(TEAM_ID, rs.getString("context_team_id"));
					assertEquals("GITHUB_REPOSITORY_CONNECTED", rs.getString("action"));
					assertEquals(null, rs.getString("context_project_name_snapshot"));
					assertEquals(null, rs.getObject("context_team_no_snapshot"));
					assertEquals(null, rs.getString("context_team_name_snapshot"));
				}
			}
		}
	}

	private Map<Integer, Integer> checksumsByVersion() throws SQLException {
		Map<Integer, Integer> checksums = new LinkedHashMap<>();
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT version, checksum FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL");
				ResultSet rs = statement.executeQuery()) {
			while (rs.next()) {
				checksums.put(Integer.parseInt(rs.getString("version")), rs.getInt("checksum"));
			}
		}
		return checksums;
	}

	private static String nullable(Connection connection, String column) throws SQLException {
		return columnMeta(connection, column, "IS_NULLABLE");
	}

	private static String dataType(Connection connection, String column) throws SQLException {
		return columnMeta(connection, column, "DATA_TYPE");
	}

	private static Integer maxLength(Connection connection, String column) throws SQLException {
		try (PreparedStatement columns = connection.prepareStatement(
				"""
				SELECT CHARACTER_MAXIMUM_LENGTH
				FROM information_schema.COLUMNS
				WHERE TABLE_SCHEMA = DATABASE()
				  AND TABLE_NAME = 'audit_log'
				  AND COLUMN_NAME = ?
				""")) {
			columns.setString(1, column);
			try (ResultSet rs = columns.executeQuery()) {
				assertTrue(rs.next());
				return rs.getObject(1) == null ? null : rs.getInt(1);
			}
		}
	}

	private static String columnMeta(Connection connection, String column, String field) throws SQLException {
		try (PreparedStatement columns = connection.prepareStatement(
				"""
				SELECT CHARACTER_SET_NAME, COLLATION_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE
				FROM information_schema.COLUMNS
				WHERE TABLE_SCHEMA = DATABASE()
				  AND TABLE_NAME = 'audit_log'
				  AND COLUMN_NAME = ?
				""")) {
			columns.setString(1, column);
			try (ResultSet rs = columns.executeQuery()) {
				assertTrue(rs.next());
				return rs.getString(field);
			}
		}
	}
}
