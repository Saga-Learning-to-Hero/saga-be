package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V20SchemaFollowUpTest {

	private static final String V1_SHA256 =
			"78959f026ce93d32cbfbd93061fd795fdc065acce8639b3cd1974baa293605b2";
	private static final String V19_SHA256 =
			"2dbb169839e7c0450339fffebe08ce1fdfc85f81c719091d107f83d89ee3f25a";

	@Test
	void v1AndV19ChecksumsRemainUnchanged() throws Exception {
		assertEquals(V1_SHA256, sha256("/db/migration/V1__initial_schema.sql"));
		assertEquals(V19_SHA256, sha256("/db/migration/V19__peer_review_default_rubric.sql"));
	}

	@Test
	void v20AddsFcmTokenWithoutTouchingV1ToV19OrFid() throws IOException {
		String v1 = read("/db/migration/V1__initial_schema.sql");
		String v19 = read("/db/migration/V19__peer_review_default_rubric.sql");
		String v20 = read("/db/migration/V20__firebase_installation_fcm_token.sql");

		assertTrue(v1.contains("CREATE TABLE firebase_installation"));
		assertTrue(v1.contains("firebase_installation_id VARCHAR(255) NOT NULL"));
		assertFalse(v1.contains("fcm_token"));
		assertFalse(v19.contains("fcm_token"));
		assertFalse(v19.contains("firebase_installation"));

		assertTrue(v20.contains("MODIFY COLUMN firebase_installation_id VARCHAR(255)"));
		assertTrue(v20.contains("CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"));
		assertTrue(v20.contains("ADD COLUMN fcm_token VARCHAR(512)"));
		assertTrue(v20.contains("ADD COLUMN platform VARCHAR(16) NULL"));
		assertTrue(v20.contains("ADD UNIQUE KEY uk_firebase_installation_fcm_token (fcm_token)"));
		assertFalse(v20.contains("COLLATE utf8mb4_0900_ai_ci"));
		assertFalse(v20.toUpperCase().contains("DROP COLUMN FIREBASE_INSTALLATION_ID"));
		assertFalse(v20.toUpperCase().contains("DROP TABLE FIREBASE_INSTALLATION"));
		assertFalse(v20.contains("V21"));
		assertFalse(v20.toUpperCase().contains("AUTO_INCREMENT"));
	}

	private static String sha256(String classpath) throws Exception {
		return MigrationChecksumSupport.sha256Lf(V20SchemaFollowUpTest.class, classpath);
	}

	private static String read(String classpath) throws IOException {
		try (InputStream in = V20SchemaFollowUpTest.class.getResourceAsStream(classpath)) {
			assertTrue(in != null, "missing " + classpath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
