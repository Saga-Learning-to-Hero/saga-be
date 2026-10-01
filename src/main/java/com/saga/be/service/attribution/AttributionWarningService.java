package com.saga.be.service.attribution;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.dto.mail.EmailEnqueueRequest;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.enums.WarningCategory;
import com.saga.be.entity.enums.WarningSeverity;
import com.saga.be.entity.integration.IdentityMap;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.warning.BusinessWarning;
import com.saga.be.repository.BusinessWarningRepository;
import com.saga.be.service.mail.EmailOutboxService;
import com.saga.be.service.notification.NotificationService;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class AttributionWarningService {

	private final BusinessWarningRepository warnings;
	private final NotificationService notifications;
	private final EmailOutboxService emails;

	public AttributionWarningService(
			BusinessWarningRepository warnings, NotificationService notifications, EmailOutboxService emails) {
		this.warnings = warnings;
		this.notifications = notifications;
		this.emails = emails;
	}

	@Transactional
	public void onDuplicateClaim(UserAccount actor, IntegrationProvider provider, String subject) {
		raise(
				"EXTERNAL_IDENTITY_ALREADY_CLAIMED",
				"identity-claimed:" + provider + ":" + subject,
				WarningSeverity.HIGH,
				null,
				null,
				"Tài khoản nhà cung cấp (GitHub/Jira) này đã được liên kết với một người dùng SAGA khác.",
				actor,
				null);
	}

	@Transactional
	public void onIdentityChanged(UserAccount actor, IdentityMap identity, LocalDateTime now) {
		raise(
				"IDENTITY_CHANGED_NEAR_DEADLINE",
				"identity-changed:" + identity.getId() + ":" + now.toLocalDate(),
				WarningSeverity.MEDIUM,
				null,
				null,
				"Một tài khoản nhà cung cấp (GitHub/Jira) vừa được liên kết hoặc huỷ liên kết. Hãy kiểm tra các task sắp đến hạn.",
				actor,
				null);
	}

	@Transactional
	public void raise(
			String type,
			String eventKey,
			WarningSeverity severity,
			Project project,
			Team team,
			String summary,
			UserAccount student,
			Task task) {
		if (warnings.findByEventKey(eventKey).isPresent()) {
			return;
		}
		BusinessWarning warning = new BusinessWarning();
		warning.setWarningType(type);
		warning.setCategory(WarningCategory.ATTRIBUTION);
		warning.setEventKey(eventKey);
		warning.setSeverity(severity);
		warning.setProject(project);
		warning.setTeam(team);
		if (project != null) {
			warning.setCourse(project.getCourse());
		}
		warning.setEvidenceSummary(summary);
		warnings.save(warning);
		AttributionWarningRouter.Delivery delivery = AttributionWarningRouter.forSeverity(severity);
		if (delivery.inAppNotification() && project != null && project.getCourse() != null && project.getCourse().getInstructor() != null) {
			UserAccount lecturer = project.getCourse().getInstructor().getUserAccount();
			if (lecturer != null && lecturer.getId() != null) {
				notifications.createNotification(
						lecturer.getId(),
						NotificationType.WARNING,
						"Có dấu hiệu bất thường trong ghi nhận đóng góp",
						summary,
						null,
						eventKey);
			}
			if (delivery.emailLecturer()) {
				emails.enqueue(new EmailEnqueueRequest(
						lecturer.getEmail(),
						lecturer.getId(),
						"ATTRIBUTION_ANOMALY",
						"attribution-anomaly",
						Map.of(
								"subject",
								"SAGA — Có dấu hiệu bất thường trong ghi nhận đóng góp",
								"textBody",
								summary,
								"summary",
								summary,
								"studentName",
								student == null || student.getFullName() == null ? "" : student.getFullName()),
						null));
			}
		}
	}

	public void securityFailure(String eventKey, String summary) {
		raise("WEBHOOK_OR_INTEGRATION_SECURITY_FAILURE", eventKey, WarningSeverity.CRITICAL, null, null, summary, null, null);
	}
}
