package com.saga.be.service.jira;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraDynamicWebhookClient;
import com.saga.be.integration.jira.JiraDynamicWebhookClient.RegisteredWebhook;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.service.projection.ProjectionMappings;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registers / refreshes / deletes Jira OAuth dynamic webhooks for Team integrations.
 * Provider HTTP stays outside JDBC transactions.
 */
@Service
@Profile("!test")
public class JiraDynamicWebhookService {

	private static final Logger log = LoggerFactory.getLogger(JiraDynamicWebhookService.class);

	static final List<String> REQUIRED_EVENTS = List.of(
			"jira:issue_created",
			"jira:issue_updated",
			"jira:issue_deleted",
			"sprint_created",
			"sprint_updated",
			"sprint_started",
			"sprint_closed",
			"sprint_deleted");

	private final JiraIntegrationRepository integrations;
	private final JiraDynamicWebhookClient client;
	private final JiraTeamTokenService tokens;
	private final IntegrationProperties properties;
	private final TransactionTemplate writes;

	public JiraDynamicWebhookService(
			JiraIntegrationRepository integrations,
			JiraDynamicWebhookClient client,
			JiraTeamTokenService tokens,
			IntegrationProperties properties,
			org.springframework.transaction.PlatformTransactionManager transactionManager) {
		this.integrations = integrations;
		this.client = client;
		this.tokens = tokens;
		this.properties = properties;
		this.writes = new TransactionTemplate(transactionManager);
	}

	/**
	 * Ensures exactly one dynamic webhook for the project's selected Jira source.
	 * On failure keeps integration ACTIVE but records {@code JIRA_WEBHOOK_REGISTER_FAILED}.
	 */
	public void ensureRegistered(UUID projectId, String preferredAccessToken) {
		JiraIntegration integration = integrations.findByProject_Id(projectId).orElse(null);
		if (integration == null || integration.getConnectionStatus() != IntegrationStatus.ACTIVE) {
			return;
		}
		String callback = requireCallbackUrl();
		String jql = jqlForProject(integration.getProjectKey());
		try {
			String access = preferredAccessToken != null && !preferredAccessToken.isBlank()
					? preferredAccessToken
					: tokens.accessToken(integration);
			RegisteredWebhook reusable = findReusable(access, integration.getCloudId(), callback, jql);
			long webhookId;
			String expiration;
			if (reusable != null) {
				webhookId = reusable.id();
				expiration = client.refreshWebhooks(access, integration.getCloudId(), List.of(webhookId));
			} else {
				Long previous = parseWebhookId(integration.getWebhookId());
				if (previous != null) {
					safeDelete(access, integration.getCloudId(), previous);
				}
				webhookId = client.registerWebhook(access, integration.getCloudId(), callback, jql, REQUIRED_EVENTS);
				RegisteredWebhook created = findById(access, integration.getCloudId(), webhookId);
				expiration = created == null ? null : created.expirationDate();
				if (expiration == null || expiration.isBlank()) {
					expiration = client.refreshWebhooks(access, integration.getCloudId(), List.of(webhookId));
				}
			}
			persistSuccess(integration.getId(), String.valueOf(webhookId), expiration);
		} catch (RuntimeException ex) {
			log.warn(
					"jira dynamic webhook register failed projectId={} type={}",
					projectId,
					ex.getClass().getSimpleName());
			persistRegisterFailure(integration.getId());
		}
	}

	public void refreshDue(JiraIntegration integration) {
		if (integration == null || integration.getId() == null) {
			return;
		}
		Long webhookId = parseWebhookId(integration.getWebhookId());
		if (webhookId == null) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_WEBHOOK_REFRESH_FAILED,
					HttpStatus.BAD_REQUEST,
					"Jira webhook id is missing; cannot refresh.");
		}
		String access = tokens.accessToken(integration);
		String expiration = client.refreshWebhooks(access, integration.getCloudId(), List.of(webhookId));
		persistSuccess(integration.getId(), String.valueOf(webhookId), expiration);
	}

	public void unregisterIfPresent(JiraIntegration integration, String preferredAccessToken) {
		if (integration == null) {
			return;
		}
		Long webhookId = parseWebhookId(integration.getWebhookId());
		String cloudId = integration.getCloudId();
		if (webhookId != null && cloudId != null && !cloudId.isBlank()) {
			try {
				String access = preferredAccessToken != null && !preferredAccessToken.isBlank()
						? preferredAccessToken
						: tokens.accessToken(integration);
				client.deleteWebhooks(access, cloudId, List.of(webhookId));
			} catch (RuntimeException ex) {
				log.warn(
						"jira dynamic webhook delete failed integrationId={} type={}",
						integration.getId(),
						ex.getClass().getSimpleName());
			}
		}
		clearWebhookFields(integration.getId());
	}

	/** Best-effort delete of a prior-source webhook after replacement (old cloudId). */
	public void unregisterRemote(String cloudId, String webhookIdRaw, String accessToken) {
		Long webhookId = parseWebhookId(webhookIdRaw);
		if (cloudId == null || cloudId.isBlank() || webhookId == null || accessToken == null || accessToken.isBlank()) {
			return;
		}
		try {
			client.deleteWebhooks(accessToken, cloudId, List.of(webhookId));
		} catch (RuntimeException ex) {
			log.warn("jira prior-source webhook delete skipped type={}", ex.getClass().getSimpleName());
		}
	}

	private RegisteredWebhook findReusable(String access, String cloudId, String callback, String jql) {
		List<RegisteredWebhook> existing = client.listWebhooks(access, cloudId);
		for (RegisteredWebhook row : existing) {
			if (row.id() == null) {
				continue;
			}
			if (!urlsMatch(callback, row.url())) {
				continue;
			}
			if (!jqlEquals(jql, row.jqlFilter())) {
				continue;
			}
			if (!coversRequiredEvents(row.events())) {
				continue;
			}
			return row;
		}
		return null;
	}

	private RegisteredWebhook findById(String access, String cloudId, long webhookId) {
		return client.listWebhooks(access, cloudId).stream()
				.filter(row -> Objects.equals(row.id(), webhookId))
				.findFirst()
				.orElse(null);
	}

	private void safeDelete(String access, String cloudId, long webhookId) {
		try {
			client.deleteWebhooks(access, cloudId, List.of(webhookId));
		} catch (RuntimeException ex) {
			log.warn("jira dynamic webhook prior delete skipped type={}", ex.getClass().getSimpleName());
		}
	}

	private void persistSuccess(UUID integrationId, String webhookId, String expirationRaw) {
		LocalDateTime expiration = ProjectionMappings.parseInstant(expirationRaw);
		if (expiration == null) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_WEBHOOK_REFRESH_FAILED,
					HttpStatus.BAD_GATEWAY,
					"Jira webhook expiration could not be parsed.");
		}
		writes.executeWithoutResult(status -> {
			JiraIntegration row = integrations.findById(integrationId).orElse(null);
			if (row == null) {
				return;
			}
			row.setWebhookId(webhookId);
			row.setWebhookExpiresAt(expiration);
			if (IntegrationErrorCode.JIRA_WEBHOOK_REGISTER_FAILED.name().equals(row.getLastErrorCode())
					|| IntegrationErrorCode.JIRA_WEBHOOK_REFRESH_FAILED.name().equals(row.getLastErrorCode())) {
				row.setLastErrorCode(null);
			}
			row.setConsecutiveFailures(0);
			integrations.save(row);
		});
	}

	private void persistRegisterFailure(UUID integrationId) {
		writes.executeWithoutResult(status -> {
			JiraIntegration row = integrations.findById(integrationId).orElse(null);
			if (row == null) {
				return;
			}
			// Keep ACTIVE so sync/recovery still works; surface webhook gap via lastErrorCode.
			row.setLastErrorCode(IntegrationErrorCode.JIRA_WEBHOOK_REGISTER_FAILED.name());
			integrations.save(row);
		});
	}

	private void clearWebhookFields(UUID integrationId) {
		if (integrationId == null) {
			return;
		}
		writes.executeWithoutResult(status -> {
			JiraIntegration row = integrations.findById(integrationId).orElse(null);
			if (row == null) {
				return;
			}
			row.setWebhookId(null);
			row.setWebhookExpiresAt(null);
			integrations.save(row);
		});
	}

	private String requireCallbackUrl() {
		String url = properties.getJira().getWebhookUrl();
		if (url == null || url.isBlank()) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_WEBHOOK_REGISTER_FAILED,
					HttpStatus.BAD_REQUEST,
					"saga.integration.jira.webhook-url is not configured.");
		}
		return url.trim();
	}

	static String jqlForProject(String projectKey) {
		if (projectKey == null || projectKey.isBlank()) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_WEBHOOK_REGISTER_FAILED,
					HttpStatus.BAD_REQUEST,
					"Jira project key is required for webhook registration.");
		}
		String sanitized = projectKey.trim().replace("\"", "");
		return "project = \"" + sanitized + "\"";
	}

	static Long parseWebhookId(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Long.parseLong(raw.trim());
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	static boolean urlsMatch(String expected, String actual) {
		if (expected == null || actual == null) {
			return false;
		}
		String left = stripTrailingSlash(expected.trim());
		String right = stripTrailingSlash(actual.trim());
		return left.equalsIgnoreCase(right);
	}

	static boolean jqlEquals(String expected, String actual) {
		if (expected == null || actual == null) {
			return false;
		}
		return normalizeJql(expected).equals(normalizeJql(actual));
	}

	static boolean coversRequiredEvents(List<String> events) {
		if (events == null || events.isEmpty()) {
			return false;
		}
		for (String required : REQUIRED_EVENTS) {
			boolean found = false;
			for (String event : events) {
				if (required.equalsIgnoreCase(event)) {
					found = true;
					break;
				}
			}
			if (!found) {
				return false;
			}
		}
		return true;
	}

	private static String normalizeJql(String jql) {
		return jql.replace(" ", "").toLowerCase(Locale.ROOT);
	}

	private static String stripTrailingSlash(String url) {
		if (url.endsWith("/")) {
			return url.substring(0, url.length() - 1);
		}
		return url;
	}
}
