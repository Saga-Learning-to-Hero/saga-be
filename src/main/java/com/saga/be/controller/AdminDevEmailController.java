package com.saga.be.controller;

import com.saga.be.auth.InstitutionalEmailPolicy;
import com.saga.be.dto.mail.DevEmailTestRequest;
import com.saga.be.dto.mail.DevEmailTestResponse;
import com.saga.be.dto.mail.EmailEnqueueRequest;
import com.saga.be.dto.mail.EmailOutboxRecord;
import com.saga.be.mail.EmailSender;
import com.saga.be.mail.template.EmailTemplateModel;
import com.saga.be.mail.template.EmailTemplateService;
import com.saga.be.service.mail.EmailOutboxService;
import com.saga.be.service.mail.EmailOutboxWorker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local", "dev"})
@RequestMapping("/api/admin/dev")
@Tag(name = "Admin mail smoke", description = "Local/dev only. Enqueue + process one test email. ADMIN only.")
@SecurityRequirement(name = "SAGA_SESSION")
public class AdminDevEmailController {

	private final EmailOutboxService outbox;
	private final EmailOutboxWorker worker;
	private final EmailSender sender;
	private final EmailTemplateService templates;
	private final InstitutionalEmailPolicy institutionalEmails;

	public AdminDevEmailController(
			EmailOutboxService outbox,
			EmailOutboxWorker worker,
			EmailSender sender,
			EmailTemplateService templates,
			InstitutionalEmailPolicy institutionalEmails) {
		this.outbox = outbox;
		this.worker = worker;
		this.sender = sender;
		this.templates = templates;
		this.institutionalEmails = institutionalEmails;
	}

	@PostMapping("/email-test")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Operation(summary = "Enqueue a test email and run the outbox worker once")
	public DevEmailTestResponse sendTest(@Valid @RequestBody DevEmailTestRequest request) {
		String templateKey = StringUtils.hasText(request.templateKey())
				? request.templateKey()
				: EmailTemplateService.COURSE_ENROLLED;
		Map<String, Object> payload = templates.payload(templateKey, sample(templateKey, request.to()));
		EmailOutboxRecord queued =
				outbox.enqueue(new EmailEnqueueRequest(request.to(), null, "DEV_SMOKE", templateKey, payload, null));
		worker.processBatch();
		EmailOutboxRecord current = outbox.get(queued.id());
		return new DevEmailTestResponse(
				current.id(),
				current.deliveryStatus(),
				current.attemptCount(),
				current.lastFailureCode(),
				current.sentAt(),
				sender.isEnabled());
	}

	private EmailTemplateModel sample(String templateKey, String to) {
		boolean invitation = EmailTemplateService.COURSE_INVITATION.equals(EmailTemplateService.normalize(templateKey));
		return EmailTemplateModel.course(
				"Developer",
				to,
				"Software Development Project",
				"SWP391",
				"SE1705",
				"FA26",
				"Fall 2026",
				invitation && institutionalEmails.isInstitutionalEmail(to));
	}
}
