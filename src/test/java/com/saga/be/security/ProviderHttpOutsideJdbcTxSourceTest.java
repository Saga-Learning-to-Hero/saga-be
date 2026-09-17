package com.saga.be.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ProviderHttpOutsideJdbcTxSourceTest {

	@Test
	void jiraEvidenceSyncHasNoMethodLevelTransactionalAroundHttp() throws Exception {
		String source = normalize(Path.of("src/main/java/com/saga/be/service/jira/JiraIssueEvidenceSyncService.java"));
		assertTrue(source.contains("JdbcTransactionGuard.requireInactive(\"jira evidence search\")"));
		assertTrue(source.contains("JdbcTransactionGuard.requireInactive(\"jira evidence issue get\")"));
		assertTrue(source.contains("JdbcTransactionGuard.requireInactive(\"jira evidence remote links\")"));
		assertTrue(source.contains("JdbcTransactionGuard.requireInactive(\"jira evidence attachment download\")"));
		assertFalse(annotated("public int syncIntegration", source));
		assertFalse(annotated("public void syncIssue", source));
	}

	@Test
	void jiraTeamTokenRefreshHttpIsGuardedAndNotMethodTransactional() throws Exception {
		String source = normalize(Path.of("src/main/java/com/saga/be/integration/jira/JiraTeamTokenService.java"));
		assertTrue(source.contains("JdbcTransactionGuard.requireInactive(\"jira token refresh\")"));
		assertTrue(source.contains("lockById"));
		assertFalse(annotated("public String accessToken", source));
	}

	@Test
	void personalOauthCompleteHttpIsOutsideTransactional() throws Exception {
		String source = normalize(Path.of("src/main/java/com/saga/be/service/identity/PersonalIntegrationService.java"));
		assertTrue(source.contains("JdbcTransactionGuard.requireInactive(\"github oauth exchange\")"));
		assertTrue(source.contains("JdbcTransactionGuard.requireInactive(\"jira oauth exchange\")"));
		assertFalse(annotated("public String completeGithub", source));
		assertFalse(annotated("public void completeJira", source));
	}

	@Test
	void hardBanFilterStillLooksUpAccountStatusOnEveryProtectedRequest() throws Exception {
		String filter = normalize(Path.of("src/main/java/com/saga/be/security/AccountStatusEnforcementFilter.java"));
		assertTrue(filter.contains("users.findById("));
		assertTrue(filter.contains("principal.getUserId()"));
		assertFalse(filter.contains("SessionRepository"));
		assertFalse(filter.contains("request.setAttribute"));
	}

	@Test
	void hikariPoolSizeUnchanged() throws Exception {
		String dev = normalize(Path.of("src/main/resources/application-dev.properties"));
		String local = normalize(Path.of("src/main/resources/application-local.properties"));
		assertTrue(dev.contains("spring.datasource.hikari.maximum-pool-size=5"));
		assertTrue(local.contains("spring.datasource.hikari.maximum-pool-size=5"));
	}

	private static boolean annotated(String signature, String source) {
		return Pattern.compile("@Transactional(?:\\([^)]*\\))?\\s+" + Pattern.quote(signature)).matcher(source).find();
	}

	private static String normalize(Path path) throws java.io.IOException {
		return Files.readString(path).replace("\r\n", "\n");
	}
}
