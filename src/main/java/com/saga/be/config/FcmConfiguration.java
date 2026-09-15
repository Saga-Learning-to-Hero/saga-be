package com.saga.be.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.saga.be.push.DisabledPushNotificationSender;
import com.saga.be.push.FirebasePushNotificationSender;
import com.saga.be.push.PushNotificationSender;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

/**
 * Railway-first Firebase Admin initialization from {@code SAGA_FCM_ENABLED},
 * {@code SAGA_FCM_PROJECT_ID}, {@code SAGA_FCM_CLIENT_EMAIL}, and {@code SAGA_FCM_PRIVATE_KEY}.
 *
 * <p>Disabled mode needs no credentials. Enabled mode fails at startup if any of the three
 * credential fields is missing or the PKCS#8 PEM cannot be parsed. Credentials are never written
 * to disk. There is no public debug send endpoint.
 *
 * <p>Local/Railway smoke after FE {@code PUT /api/users/me/push-installations}: set the four env
 * vars above. Do not use {@code GOOGLE_APPLICATION_CREDENTIALS} or a service-account JSON file.
 */
@Configuration
@Profile("!test")
@EnableConfigurationProperties(FcmProperties.class)
public class FcmConfiguration {

	static final String APP_NAME = "saga-fcm";
	private static final Logger log = LoggerFactory.getLogger(FcmConfiguration.class);

	@Bean
	public PushNotificationSender pushNotificationSender(FcmProperties properties) {
		if (!properties.isEnabled()) {
			log.info("Firebase Cloud Messaging disabled");
			return new DisabledPushNotificationSender();
		}
		FirebaseApp app = firebaseApp(properties);
		log.info("Firebase Cloud Messaging initialized for project {}", properties.getProjectId().trim());
		return new FirebasePushNotificationSender(FirebaseMessaging.getInstance(app));
	}

	@Bean
	FirebaseAppDisposable firebaseAppDisposable(PushNotificationSender sender) {
		return new FirebaseAppDisposable();
	}

	static FirebaseApp firebaseApp(FcmProperties properties) {
		GoogleCredentials credentials = loadCredentials(properties);
		FirebaseOptions options = FirebaseOptions.builder()
				.setCredentials(credentials)
				.setProjectId(properties.getProjectId().trim())
				.build();
		for (FirebaseApp existing : FirebaseApp.getApps()) {
			if (APP_NAME.equals(existing.getName())) {
				return existing;
			}
		}
		return FirebaseApp.initializeApp(options, APP_NAME);
	}

	static GoogleCredentials loadCredentials(FcmProperties properties) {
		requireEnabledCredentials(properties);
		String pem = normalizePrivateKey(properties.getPrivateKey());
		try {
			return ServiceAccountCredentials.newBuilder()
					.setClientEmail(properties.getClientEmail().trim())
					.setPrivateKeyString(pem)
					.setProjectId(properties.getProjectId().trim())
					.build();
		} catch (IOException | RuntimeException ignored) {
			throw invalidCredentials();
		}
	}

	static void requireEnabledCredentials(FcmProperties properties) {
		if (!StringUtils.hasText(properties.getProjectId())) {
			throw new IllegalStateException(
					"saga.fcm.enabled=true requires saga.fcm.project-id / SAGA_FCM_PROJECT_ID.");
		}
		if (!StringUtils.hasText(properties.getClientEmail())) {
			throw new IllegalStateException(
					"saga.fcm.enabled=true requires saga.fcm.client-email / SAGA_FCM_CLIENT_EMAIL.");
		}
		if (!StringUtils.hasText(properties.getPrivateKey())) {
			throw new IllegalStateException(
					"saga.fcm.enabled=true requires saga.fcm.private-key / SAGA_FCM_PRIVATE_KEY.");
		}
	}

	/**
	 * Railway env vars often store PKCS#8 PEM with a literal {@code \\n} sequence instead of
	 * newlines. PEM base64 never contains a backslash, so replacing that sequence is safe.
	 */
	static String normalizePrivateKey(String raw) {
		if (raw == null) {
			return "";
		}
		String key = raw.trim();
		if (key.length() >= 2) {
			char first = key.charAt(0);
			char last = key.charAt(key.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				key = key.substring(1, key.length() - 1).trim();
			}
		}
		return key.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\r\n", "\n").replace('\r', '\n');
	}

	private static IllegalStateException invalidCredentials() {
		return new IllegalStateException("saga.fcm.enabled=true but FCM credentials are invalid.");
	}

	static final class FirebaseAppDisposable implements DisposableBean {
		@Override
		public void destroy() {
			for (FirebaseApp app : FirebaseApp.getApps()) {
				if (APP_NAME.equals(app.getName())) {
					app.delete();
				}
			}
		}
	}
}
