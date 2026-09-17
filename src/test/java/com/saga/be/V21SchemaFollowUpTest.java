package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V21SchemaFollowUpTest {

	private static final String V1_SHA256 =
			"78959f026ce93d32cbfbd93061fd795fdc065acce8639b3cd1974baa293605b2";
	private static final String V20_SHA256 =
			"8ee68dd251ee827995aaeec029f071c566d1821e70df03d84bb5066423ce9205";

	@Test
	void v1AndV20ChecksumsRemainUnchanged() throws Exception {
		assertEquals(V1_SHA256, sha256("/db/migration/V1__initial_schema.sql"));
		assertEquals(V20_SHA256, sha256("/db/migration/V20__firebase_installation_fcm_token.sql"));
	}

	@Test
	void v21AddsNullableProjectTeamSnapshotsWithoutTouchingV1ToV20() throws IOException {
		String v1 = read("/db/migration/V1__initial_schema.sql");
		String v4 = read("/db/migration/V4__integration_identity_audit_attribution_foundation.sql");
		String v20 = read("/db/migration/V20__firebase_installation_fcm_token.sql");
		String v21 = read("/db/migration/V21__audit_project_team_snapshots.sql");

		assertFalse(v1.contains("context_project_name_snapshot"));
		assertFalse(v1.contains("context_team_name_snapshot"));
		assertTrue(v4.contains("CREATE TABLE audit_log"));
		assertTrue(v4.contains("context_project_id"));
		assertTrue(v4.contains("context_team_id"));
		assertFalse(v4.contains("context_project_name_snapshot"));
		assertFalse(v4.contains("context_team_no_snapshot"));
		assertFalse(v4.contains("context_team_name_snapshot"));
		assertFalse(v20.contains("context_project_name_snapshot"));
		assertFalse(v20.contains("V21"));

		assertTrue(v21.contains("ADD COLUMN context_team_no_snapshot INT NULL"));
		assertTrue(v21.contains("ADD COLUMN context_team_name_snapshot VARCHAR(255) NULL"));
		assertTrue(v21.contains("ADD COLUMN context_project_name_snapshot VARCHAR(255) NULL"));
		assertFalse(v21.toUpperCase().contains("NOT NULL"));
		assertFalse(v21.toUpperCase().contains("CREATE INDEX"));
		assertFalse(v21.toUpperCase().contains("ADD INDEX"));
		assertFalse(v21.toUpperCase().contains("ADD KEY"));
		assertFalse(v21.contains("projectSnapshot"));
		assertFalse(v21.contains("teamSnapshot"));
		assertFalse(v21.toUpperCase().contains("DROP TABLE AUDIT_LOG"));
		assertFalse(v21.toUpperCase().contains("AUTO_INCREMENT"));
		assertFalse(v21.contains("V22"));
	}

	private static String sha256(String classpath) throws Exception {
		return MigrationChecksumSupport.sha256Lf(V21SchemaFollowUpTest.class, classpath);
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V21SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
