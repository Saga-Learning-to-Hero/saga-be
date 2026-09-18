package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.TreeMap;
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
 * Disposable MySQL 8: V1→V22 seed a git_commit, V23 adds nullable parent_count. Opt-in:
 * {@code saga.verify.mysql=true}.
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
class GitCommitParentCountV23MysqlIT {

	private static final String HISTORICAL_COMMIT_ID = "d0000000-0000-4000-8000-000000000023";

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
	private javax.sql.DataSource dataSource;

	private Map<Integer, Integer> checksumsThroughV22;

	@BeforeEach
	void migrateV1ThroughV22SeedCommitThenV23() throws Exception {
		Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.cleanDisabled(false)
				.load()
				.clean();
		Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.target("22")
				.load()
				.migrate();
		checksumsThroughV22 = checksumsByVersion();
		seedHistoricalCommit();
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
	}

	@Test
	void v23KeepsHistoricalParentCountNullWithoutParentCountIndex() throws Exception {
		Map<Integer, Integer> after = checksumsByVersion();
		for (int version = 1; version <= 22; version++) {
			assertEquals(checksumsThroughV22.get(version), after.get(version), "V" + version + " checksum changed");
		}
		assertTrue(after.containsKey(23));
		assertEquals(23, after.size());

		try (Connection connection = dataSource.getConnection()) {
			assertEquals("YES", nullable(connection, "parent_count"));
			assertEquals("int", dataType(connection, "parent_count"));
			assertThat(columnDefault(connection, "parent_count")).isNull();
			try (PreparedStatement row = connection.prepareStatement(
					"SELECT parent_count, sha_hash FROM git_commit WHERE id = ?")) {
				row.setString(1, HISTORICAL_COMMIT_ID);
				try (ResultSet rs = row.executeQuery()) {
					assertTrue(rs.next());
					assertEquals("deadbeefcafefeed0001", rs.getString("sha_hash"));
					rs.getObject("parent_count");
					assertTrue(rs.wasNull());
				}
			}
			String createTable;
			try (PreparedStatement show = connection.prepareStatement("SHOW CREATE TABLE git_commit");
					ResultSet rs = show.executeQuery()) {
				assertTrue(rs.next());
				createTable = rs.getString(2);
			}
			assertThat(createTable).contains("parent_count");
			assertThat(createTable.toUpperCase()).doesNotContain("DEFAULT 1");
			assertThat(createTable.toUpperCase()).doesNotContain("KEY `IX_GIT_COMMIT_PARENT_COUNT`");

			try (PreparedStatement insert = connection.prepareStatement(
					"""
					INSERT INTO git_commit (id, repo_id, sha_hash, message, head_ref)
					VALUES (?, 'c0000000-0000-4000-8000-000000000023', ?, 'transition', 'main')
					""")) {
				insert.setString(1, "d0000000-0000-4000-8000-000000000024");
				insert.setString(2, "deadbeefcafefeed0002");
				insert.executeUpdate();
				insert.setString(1, "d0000000-0000-4000-8000-000000000025");
				insert.setString(2, "deadbeefcafefeed0003");
				insert.executeUpdate();
				insert.setString(1, "d0000000-0000-4000-8000-000000000026");
				insert.setString(2, "deadbeefcafefeed0004");
				insert.executeUpdate();
			}
			try (PreparedStatement update = connection.prepareStatement(
					"UPDATE git_commit SET parent_count = ? WHERE id = ?")) {
				update.setInt(1, 0);
				update.setString(2, "d0000000-0000-4000-8000-000000000024");
				update.executeUpdate();
				update.setInt(1, 1);
				update.setString(2, "d0000000-0000-4000-8000-000000000025");
				update.executeUpdate();
				update.setInt(1, 2);
				update.setString(2, "d0000000-0000-4000-8000-000000000026");
				update.executeUpdate();
			}
			assertEquals(0, parentCountOf(connection, "d0000000-0000-4000-8000-000000000024"));
			assertEquals(1, parentCountOf(connection, "d0000000-0000-4000-8000-000000000025"));
			assertEquals(2, parentCountOf(connection, "d0000000-0000-4000-8000-000000000026"));

			try (PreparedStatement known = connection.prepareStatement(
					"UPDATE git_commit SET parent_count = 2 WHERE id = ?")) {
				known.setString(1, HISTORICAL_COMMIT_ID);
				known.executeUpdate();
			}
			try (PreparedStatement preserve = connection.prepareStatement(
					"UPDATE git_commit SET parent_count = COALESCE(CAST(NULL AS SIGNED), parent_count) WHERE id = ?")) {
				preserve.setString(1, HISTORICAL_COMMIT_ID);
				preserve.executeUpdate();
			}
			assertEquals(2, parentCountOf(connection, HISTORICAL_COMMIT_ID));
		}
	}

	private static Integer parentCountOf(Connection connection, String id) throws Exception {
		try (PreparedStatement statement =
				connection.prepareStatement("SELECT parent_count FROM git_commit WHERE id = ?")) {
			statement.setString(1, id);
			try (ResultSet rs = statement.executeQuery()) {
				assertTrue(rs.next());
				Object value = rs.getObject("parent_count");
				return value == null ? null : ((Number) value).intValue();
			}
		}
	}

	private void seedHistoricalCommit() throws Exception {
		try (Connection connection = dataSource.getConnection();
				java.sql.Statement statement = connection.createStatement()) {
			statement.executeUpdate(
					"INSERT INTO semester (id, code, name) VALUES ('11111111-0000-4000-8000-000000000023','FA24','Fall 2024')");
			statement.executeUpdate(
					"INSERT INTO subject (id, subject_code, name, status) VALUES ('22222222-0000-4000-8000-000000000023','SWP391','Software Project','ACTIVE')");
			statement.executeUpdate(
					"INSERT INTO academic_class (id, class_code, name, semester_id) VALUES ('33333333-0000-4000-8000-000000000023','SE1801','SE1801','11111111-0000-4000-8000-000000000023')");
			statement.executeUpdate(
					"""
					INSERT INTO course (id, subject_id, academic_class_id, semester_id, name)
					VALUES ('44444444-0000-4000-8000-000000000023','22222222-0000-4000-8000-000000000023','33333333-0000-4000-8000-000000000023','11111111-0000-4000-8000-000000000023','Course A')
					""");
			statement.executeUpdate(
					"INSERT INTO project (id, course_id, name) VALUES ('a0000000-0000-4000-8000-000000000023','44444444-0000-4000-8000-000000000023','Project A')");
			statement.executeUpdate(
					"""
					INSERT INTO git_repo (id, project_id, provider, repository_id, full_name, connection_status, consecutive_failures, version)
					VALUES ('c0000000-0000-4000-8000-000000000023','a0000000-0000-4000-8000-000000000023','GITHUB',555000123,'saga/repo-r','ACTIVE',0,0)
					""");
			statement.executeUpdate(
					"""
					INSERT INTO git_commit (id, repo_id, sha_hash, message, head_ref)
					VALUES ('d0000000-0000-4000-8000-000000000023','c0000000-0000-4000-8000-000000000023','deadbeefcafefeed0001','Historical commit','main')
					""");
		}
	}

	private Map<Integer, Integer> checksumsByVersion() throws Exception {
		Map<Integer, Integer> checksums = new TreeMap<>();
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement =
						connection.prepareStatement(
								"SELECT version, checksum FROM flyway_schema_history WHERE version IS NOT NULL");
				ResultSet rs = statement.executeQuery()) {
			while (rs.next()) {
				checksums.put(Integer.parseInt(rs.getString("version")), rs.getInt("checksum"));
			}
		}
		return checksums;
	}

	private static String nullable(Connection connection, String column) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'git_commit' AND column_name = ?")) {
			statement.setString(1, column);
			try (ResultSet rs = statement.executeQuery()) {
				assertTrue(rs.next());
				return rs.getString(1);
			}
		}
	}

	private static String dataType(Connection connection, String column) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'git_commit' AND column_name = ?")) {
			statement.setString(1, column);
			try (ResultSet rs = statement.executeQuery()) {
				assertTrue(rs.next());
				return rs.getString(1);
			}
		}
	}

	private static String columnDefault(Connection connection, String column) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT COLUMN_DEFAULT FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'git_commit' AND column_name = ?")) {
			statement.setString(1, column);
			try (ResultSet rs = statement.executeQuery()) {
				assertTrue(rs.next());
				return rs.getString(1);
			}
		}
	}
}
