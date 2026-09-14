package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.assessment.RubricTemplate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;

class RubricTemplateMappingTest {

	@Test
	void v1CreatesNullableRubricTemplateWeight() throws IOException {
		String v1 = readClasspath("/db/migration/V1__initial_schema.sql");
		assertTrue(v1.contains("CREATE TABLE rubric_template"));
		assertTrue(v1.contains("weight DECIMAL(10, 4) NULL"));
		assertTrue(v1.contains("CONSTRAINT fk_rubric_subject"));
	}

	@Test
	void noVersionedFlywayMigrationAltersRubricTemplate() throws IOException {
		for (int version = 2; version <= 17; version++) {
			String resource = switch (version) {
				case 2 -> "/db/migration/V2__user_account_password_hash_and_comment_task.sql";
				case 3 -> "/db/migration/V3__auth_v1_account_identity.sql";
				case 4 -> "/db/migration/V4__integration_identity_audit_attribution_foundation.sql";
				case 5 -> "/db/migration/V5__subject_syllabus_academic_foundation.sql";
				case 6 -> "/db/migration/V6__academic_runtime_foundation.sql";
				case 7 -> "/db/migration/V7__course_roster_invitation_identity.sql";
				case 8 -> "/db/migration/V8__lecturer_team_management.sql";
				case 9 -> "/db/migration/V9__task_web_link.sql";
				case 10 -> "/db/migration/V10__task_file.sql";
				case 11 -> "/db/migration/V11__jira_task_evidence_source.sql";
				case 12 -> "/db/migration/V12__github_project_installation.sql";
				case 13 -> "/db/migration/V13__password_reset_token.sql";
				case 14 -> "/db/migration/V14__jira_active_scoped_cloud_project_uniqueness.sql";
				case 15 -> "/db/migration/V15__github_active_scoped_repository_uniqueness.sql";
				case 16 -> "/db/migration/V16__task_jira_parent_identity.sql";
				case 17 -> "/db/migration/V17__task_jira_start_date.sql";
				default -> throw new IllegalStateException("unexpected version " + version);
			};
			String sql = readClasspath(resource);
			assertFalse(
					sql.contains("ALTER TABLE rubric_template"),
					"V" + version + " must not alter rubric_template");
			assertFalse(sql.contains("DROP COLUMN weight"), "V" + version + " must not drop weight");
		}
	}

	@Test
	void rubricTemplateEntityDoesNotMapWeight() throws IOException {
		String source = Files.readString(Path.of("src/main/java/com/saga/be/entity/assessment/RubricTemplate.java"));
		assertFalse(source.contains("name = \"weight\""));
		assertFalse(source.contains("private BigDecimal weight"));
		assertTrue(source.contains("criteria_name"));
		assertTrue(source.contains("subject_id"));
	}

	@Test
	void hibernateValidatePassesWhenWeightColumnIsMissing() throws Exception {
		validateAgainstDdl(
				"""
				CREATE TABLE subject (
				  id CHAR(36) NOT NULL PRIMARY KEY,
				  subject_code VARCHAR(64) NOT NULL,
				  name VARCHAR(255) NOT NULL,
				  name_vietnamese VARCHAR(255),
				  status VARCHAR(32) NOT NULL,
				  deleted_at TIMESTAMP,
				  created_at TIMESTAMP NOT NULL,
				  updated_at TIMESTAMP NOT NULL
				);
				CREATE TABLE rubric_template (
				  id CHAR(36) NOT NULL PRIMARY KEY,
				  subject_id CHAR(36),
				  criteria_name VARCHAR(255),
				  description VARCHAR(1000),
				  deleted_at TIMESTAMP,
				  created_at TIMESTAMP NOT NULL,
				  updated_at TIMESTAMP NOT NULL
				);
				""");
	}

	@Test
	void hibernateValidatePassesWhenUnmappedV1WeightColumnRemains() throws Exception {
		validateAgainstDdl(
				"""
				CREATE TABLE subject (
				  id CHAR(36) NOT NULL PRIMARY KEY,
				  subject_code VARCHAR(64) NOT NULL,
				  name VARCHAR(255) NOT NULL,
				  name_vietnamese VARCHAR(255),
				  status VARCHAR(32) NOT NULL,
				  deleted_at TIMESTAMP,
				  created_at TIMESTAMP NOT NULL,
				  updated_at TIMESTAMP NOT NULL
				);
				CREATE TABLE rubric_template (
				  id CHAR(36) NOT NULL PRIMARY KEY,
				  subject_id CHAR(36),
				  criteria_name VARCHAR(255),
				  weight DECIMAL(10, 4),
				  description VARCHAR(1000),
				  deleted_at TIMESTAMP,
				  created_at TIMESTAMP NOT NULL,
				  updated_at TIMESTAMP NOT NULL
				);
				""");
	}

	@Test
	void orphanRubricDropScriptIsNotAFlywayVersionedMigration() {
		String filename = "v4_delete_fk_subjectid_from_rubric_table.sql";
		assertFalse(filename.matches("V\\d+__.*\\.sql"));
		assertTrue(Files.exists(Path.of("src/main/resources/db/migration").resolve(filename)));
	}

	private static void validateAgainstDdl(String ddl) {
		String jdbcUrl = "jdbc:h2:mem:rubric_validate_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
		StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
				.applySetting("hibernate.connection.driver_class", "org.h2.Driver")
				.applySetting("hibernate.connection.url", jdbcUrl)
				.applySetting("hibernate.connection.username", "sa")
				.applySetting("hibernate.connection.password", "")
				.applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.applySetting("hibernate.hbm2ddl.auto", "validate")
				.applySetting("hibernate.type.preferred_uuid_jdbc_type", "CHAR")
				.build();
		try {
			try (var connection = java.sql.DriverManager.getConnection(jdbcUrl, "sa", "")) {
				for (String statement : ddl.split(";")) {
					String sql = statement.trim();
					if (!sql.isEmpty()) {
						connection.createStatement().execute(sql);
					}
				}
			} catch (java.sql.SQLException ex) {
				throw new IllegalStateException(ex);
			}
			new MetadataSources(registry)
					.addAnnotatedClass(BaseEntity.class)
					.addAnnotatedClass(Subject.class)
					.addAnnotatedClass(RubricTemplate.class)
					.buildMetadata()
					.buildSessionFactory()
					.close();
		} finally {
			StandardServiceRegistryBuilder.destroy(registry);
		}
	}

	private static String readClasspath(String classpath) throws IOException {
		try (InputStream in = RubricTemplateMappingTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
