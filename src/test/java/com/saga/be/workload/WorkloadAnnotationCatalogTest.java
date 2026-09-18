package com.saga.be.workload;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.controller.AuthController;
import com.saga.be.controller.LecturerCourseController;
import com.saga.be.controller.ProjectGraphController;
import com.saga.be.controller.ProjectProjectionController;
import com.saga.be.controller.ProjectRealtimeController;
import com.saga.be.controller.ProviderWebhookController;
import com.saga.be.controller.TeamContributionController;
import com.saga.be.controller.UserNotificationController;
import com.saga.be.controller.UserRealtimeController;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

class WorkloadAnnotationCatalogTest {

	@Test
	void importantRoutesCarryDeclaredClasses() throws Exception {
		assertClass(UserNotificationController.class, "unreadCount", WorkloadClass.INTERACTIVE_LIGHT);
		assertClass(UserNotificationController.class, "list", WorkloadClass.INTERACTIVE_NORMAL);
		assertClass(AuthController.class, "csrf", WorkloadClass.INTERACTIVE_LIGHT);
		assertClass(AuthController.class, "me", WorkloadClass.INTERACTIVE_LIGHT);
		assertClass(ProjectProjectionController.class, "syncStatus", WorkloadClass.INTERACTIVE_LIGHT);
		assertClass(ProjectProjectionController.class, "tasks", WorkloadClass.INTERACTIVE_NORMAL);
		assertClass(ProjectProjectionController.class, "parentOptions", WorkloadClass.INTERACTIVE_NORMAL);
		assertClass(ProjectProjectionController.class, "taskEvidence", WorkloadClass.INTERACTIVE_NORMAL);
		assertClass(ProjectProjectionController.class, "taskCommits", WorkloadClass.INTERACTIVE_NORMAL);
		assertClass(ProjectProjectionController.class, "sync", WorkloadClass.INTERACTIVE_WRITE);
		assertClass(ProjectProjectionController.class, "projectProgress", WorkloadClass.HEAVY_READ);
		assertClass(LecturerCourseController.class, "roster", WorkloadClass.INTERACTIVE_NORMAL);
		assertClass(ProjectGraphController.class, "overview", WorkloadClass.HEAVY_READ);
		assertClass(TeamContributionController.class, "evaluate", WorkloadClass.HEAVY_READ);
		assertClass(UserRealtimeController.class, "subscribe", WorkloadClass.REALTIME);
		assertClass(ProjectRealtimeController.class, "subscribe", WorkloadClass.REALTIME);
		assertClass(ProviderWebhookController.class, "jira", WorkloadClass.BACKGROUND_SYNC);
	}

	private static void assertClass(Class<?> type, String methodName, WorkloadClass expected) throws Exception {
		java.lang.reflect.Method match = null;
		for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
			if (method.getName().equals(methodName)) {
				match = method;
				break;
			}
		}
		assertThat(match).isNotNull();
		HandlerMethod handler = new HandlerMethod(org.mockito.Mockito.mock(type), match);
		assertThat(WorkloadClassifier.resolve(handler)).isEqualTo(expected);
	}
}
