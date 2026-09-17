package com.saga.be.workload;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkloadIsolationSourceTest {

	@Test
	void graphGetStillJoinsRebuildAndUsesOneWorker() throws Exception {
		String projector = read("src/main/java/com/saga/be/graph/ProjectGraphProjector.java");
		assertTrue(projector.contains("return flying.join();"));
		assertTrue(projector.contains("return rebuildAsync(projectId).join();"));
		assertTrue(projector.contains("MAX_REBUILD_WORKERS = 1"));
	}

	@Test
	void scheduledWorkersStayOnDefaultSingleThreadScheduler() throws Exception {
		assertTrue(read("src/main/java/com/saga/be/config/IntegrationConfiguration.java")
				.contains("@EnableScheduling"));
		assertFalse(read("src/main/java/com/saga/be/service/notification/FcmDeliveryWorker.java")
				.contains("SchedulingConfigurer"));
		assertFalse(read("src/main/java/com/saga/be/service/mail/EmailOutboxWorker.java")
				.contains("new ThreadPoolTaskScheduler"));
		assertFalse(read("src/main/java/com/saga/be/scheduler/JiraInitialIssueSyncWorker.java")
				.contains("setPoolSize"));
	}

	@Test
	void hardBanFilterStillLooksUpAccountStatusOnEveryProtectedRequest() throws Exception {
		String filter = read("src/main/java/com/saga/be/security/AccountStatusEnforcementFilter.java");
		assertTrue(filter.contains("users.findById("));
		assertTrue(filter.contains("principal.getUserId()"));
		assertFalse(filter.contains("SessionRepository"));
		assertFalse(filter.contains("request.setAttribute"));
	}

	@Test
	void hikariPoolSizeUnchanged() throws Exception {
		assertTrue(read("src/main/resources/application-dev.properties")
				.contains("spring.datasource.hikari.maximum-pool-size=5"));
		assertTrue(read("src/main/resources/application-local.properties")
				.contains("spring.datasource.hikari.maximum-pool-size=5"));
	}

	@Test
	void sseSubscribeStillHasNoJdbcAroundEmitter() throws Exception {
		String user = read("src/main/java/com/saga/be/realtime/UserSseHub.java");
		String project = read("src/main/java/com/saga/be/realtime/ProjectSseHub.java");
		assertTrue(user.contains("reconnectTime(3000L)"));
		assertTrue(project.contains("reconnectTime(3000L)"));
		assertFalse(user.contains("@Transactional"));
		assertFalse(project.contains("@Transactional"));
	}

	private static String read(String path) throws java.io.IOException {
		return Files.readString(Path.of(path)).replace("\r\n", "\n");
	}
}
