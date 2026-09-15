package com.saga.be.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.firebase.FirebaseApp;
import com.saga.be.push.DisabledPushNotificationSender;
import com.saga.be.push.FirebasePushNotificationSender;
import com.saga.be.push.PushNotificationSender;
import com.saga.be.service.notification.FcmDeliveryWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;

class FcmConfigurationTest {

	private static final String CLIENT_EMAIL = "firebase-adminsdk@saga-fcm-test.iam.gserviceaccount.com";

	private final ApplicationContextRunner runner =
			new ApplicationContextRunner().withUserConfiguration(FcmConfiguration.class);

	@AfterEach
	void deleteSagaFirebaseApp() {
		for (FirebaseApp app : FirebaseApp.getApps()) {
			if (FcmConfiguration.APP_NAME.equals(app.getName())) {
				app.delete();
			}
		}
	}

	@Test
	void disabledModeStartsWithoutCredentialEnvs() {
		runner.withPropertyValues(
						"saga.fcm.enabled=false",
						"saga.fcm.project-id=",
						"saga.fcm.client-email=",
						"saga.fcm.private-key=")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(PushNotificationSender.class);
					assertThat(context.getBean(PushNotificationSender.class))
							.isInstanceOf(DisabledPushNotificationSender.class);
					assertThat(context.getBean(PushNotificationSender.class).isEnabled()).isFalse();
					assertThat(FirebaseApp.getApps())
							.noneMatch(app -> FcmConfiguration.APP_NAME.equals(app.getName()));
				});
	}

	@Test
	void enabledWithoutProjectIdFailsAtStartup() {
		String pem = TestFcmCredentials.rsaPrivateKeyPem();
		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
			FcmProperties properties = enabledProperties("", CLIENT_EMAIL, pem);
			FcmConfiguration.loadCredentials(properties);
		});
		assertThat(ex.getMessage()).contains("SAGA_FCM_PROJECT_ID");
		assertThat(ex.getMessage()).doesNotContain(pem);
		assertThat(ex.getMessage()).doesNotContain("BEGIN PRIVATE");
		runner.withPropertyValues(
						"saga.fcm.enabled=true",
						"saga.fcm.project-id=",
						"saga.fcm.client-email=" + CLIENT_EMAIL,
						"saga.fcm.private-key=" + TestFcmCredentials.escapedPem(pem))
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void enabledWithoutClientEmailFailsAtStartup() {
		String pem = TestFcmCredentials.rsaPrivateKeyPem();
		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
			FcmProperties properties = enabledProperties("saga-fcm-test", "", pem);
			FcmConfiguration.loadCredentials(properties);
		});
		assertThat(ex.getMessage()).contains("SAGA_FCM_CLIENT_EMAIL");
		assertThat(ex.getMessage()).doesNotContain(pem);
		runner.withPropertyValues(
						"saga.fcm.enabled=true",
						"saga.fcm.project-id=saga-fcm-test",
						"saga.fcm.client-email=",
						"saga.fcm.private-key=" + TestFcmCredentials.escapedPem(pem))
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void enabledWithoutPrivateKeyFailsAtStartup() {
		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
			FcmProperties properties = enabledProperties("saga-fcm-test", CLIENT_EMAIL, "");
			FcmConfiguration.loadCredentials(properties);
		});
		assertThat(ex.getMessage()).contains("SAGA_FCM_PRIVATE_KEY");
		assertThat(ex.getMessage()).doesNotContain(CLIENT_EMAIL);
		runner.withPropertyValues(
						"saga.fcm.enabled=true",
						"saga.fcm.project-id=saga-fcm-test",
						"saga.fcm.client-email=" + CLIENT_EMAIL,
						"saga.fcm.private-key=")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void escapedLiteralNewlinesAreNormalizedToPem() {
		String pem = TestFcmCredentials.rsaPrivateKeyPem();
		String escaped = TestFcmCredentials.escapedPem(pem);
		assertThat(escaped).contains("\\n");
		assertThat(escaped).doesNotContain("\n");
		assertThat(FcmConfiguration.normalizePrivateKey(escaped)).isEqualTo(pem.replace("\r\n", "\n"));
	}

	@Test
	void malformedPrivateKeyFailsWithoutLeakingSecret() {
		String malformed = "-----BEGIN PRIVATE KEY-----\nnot-a-real-pkcs8-key\n-----END PRIVATE KEY-----\n";
		FcmProperties properties = enabledProperties("saga-fcm-test", CLIENT_EMAIL, malformed);
		IllegalStateException ex =
				assertThrows(IllegalStateException.class, () -> FcmConfiguration.loadCredentials(properties));
		assertThat(ex.getMessage()).isEqualTo("saga.fcm.enabled=true but FCM credentials are invalid.");
		assertThat(ex.getMessage()).doesNotContain("BEGIN PRIVATE");
		assertThat(ex.getMessage()).doesNotContain("not-a-real-pkcs8-key");
		assertThat(String.valueOf(ex.getCause())).doesNotContain("not-a-real-pkcs8-key");
	}

	@Test
	void enabledModeInitializesFirebaseSenderWithoutLoggingSecrets() {
		Logger logger = (Logger) LoggerFactory.getLogger(FcmConfiguration.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		String pem = TestFcmCredentials.rsaPrivateKeyPem();
		try {
			FcmProperties properties = enabledProperties("saga-fcm-test", CLIENT_EMAIL, TestFcmCredentials.escapedPem(pem));
			ServiceAccountCredentials credentials =
					(ServiceAccountCredentials) FcmConfiguration.loadCredentials(properties);
			assertThat(credentials.getClientEmail()).isEqualTo(CLIENT_EMAIL);
			assertThat(credentials.getProjectId()).isEqualTo("saga-fcm-test");
			FirebaseApp app = FcmConfiguration.firebaseApp(properties);
			assertThat(app.getName()).isEqualTo(FcmConfiguration.APP_NAME);
			runner.withPropertyValues(
							"saga.fcm.enabled=true",
							"saga.fcm.project-id=saga-fcm-test",
							"saga.fcm.client-email=" + CLIENT_EMAIL,
							"saga.fcm.private-key=" + TestFcmCredentials.escapedPem(pem))
					.run(context -> {
						assertThat(context).hasNotFailed();
						assertThat(context.getBean(PushNotificationSender.class))
								.isInstanceOf(FirebasePushNotificationSender.class);
						assertThat(context.getBean(PushNotificationSender.class).isEnabled()).isTrue();
					});
			String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + b);
			assertThat(logs).contains("Firebase Cloud Messaging initialized for project saga-fcm-test");
			assertThat(logs).doesNotContain(pem);
			assertThat(logs).doesNotContain("BEGIN PRIVATE");
			assertThat(logs).doesNotContain(CLIENT_EMAIL);
			assertThat(logs).doesNotContain("private_key");
			assertThat(logs).doesNotContain("SAGA_FCM_PRIVATE_KEY");
		} finally {
			logger.detachAppender(appender);
		}
	}

	@Test
	void testProfileDoesNotRegisterFcmBeans() {
		new ApplicationContextRunner()
				.withInitializer(context -> context.getEnvironment().addActiveProfile("test"))
				.withUserConfiguration(FcmConfiguration.class)
				.withPropertyValues("saga.fcm.enabled=true", "saga.fcm.project-id=x")
				.run(context -> assertThat(context).doesNotHaveBean(PushNotificationSender.class));
	}

	@Test
	void workerIsScheduledWithConfiguredDelay() throws Exception {
		Scheduled scheduled = FcmDeliveryWorker.class.getMethod("processDue").getAnnotation(Scheduled.class);
		assertThat(scheduled).isNotNull();
		assertThat(scheduled.fixedDelayString()).isEqualTo("${saga.fcm.worker.poll-delay:15s}");
	}

	private static FcmProperties enabledProperties(String projectId, String clientEmail, String privateKey) {
		FcmProperties properties = new FcmProperties();
		properties.setEnabled(true);
		properties.setProjectId(projectId);
		properties.setClientEmail(clientEmail);
		properties.setPrivateKey(privateKey);
		return properties;
	}
}
