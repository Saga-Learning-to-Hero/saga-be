package com.saga.be.controller;

import com.saga.be.dto.roster.CourseRosterResponse;
import com.saga.be.dto.roster.RosterConfirmRequest;
import com.saga.be.dto.roster.RosterConfirmResponse;
import com.saga.be.dto.roster.RosterPreviewResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.roster.CourseRosterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile("!test")
@RequestMapping("/api/admin/courses/{courseId}/roster")
@Tag(name = "Admin course roster", description = "XLSX roster template, preview, confirm, and read. ADMIN only.")
@SecurityRequirement(name = "SAGA_SESSION")
public class AdminCourseRosterController {

	private static final MediaType XLSX =
			MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

	private final CourseRosterService roster;
	private final UserAccountRepository users;

	public AdminCourseRosterController(CourseRosterService roster, UserAccountRepository users) {
		this.roster = roster;
		this.users = users;
	}

	@GetMapping("/template")
	@Operation(summary = "Download the course roster XLSX template")
	public ResponseEntity<byte[]> template(@PathVariable UUID courseId) {
		byte[] body = roster.template(courseId);
		return ResponseEntity.ok()
				.contentType(XLSX)
				.header(
						HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename("Danh_Sach_SV.xlsx").build().toString())
				.body(body);
	}

	@GetMapping
	@Operation(summary = "List enrollments and pending invitations for a course")
	public CourseRosterResponse get(@PathVariable UUID courseId) {
		return roster.getRoster(courseId);
	}

	@PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Validate an XLSX roster without mutating membership")
	public RosterPreviewResponse preview(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@RequestPart("file") MultipartFile file) {
		byte[] bytes;
		try {
			bytes = file == null ? new byte[0] : file.getBytes();
		} catch (IOException ex) {
			throw new AcademicException(
					AcademicErrorCode.ROSTER_FILE_INVALID, HttpStatus.BAD_REQUEST, "Roster file could not be read.");
		}
		return roster.preview(courseId, bytes, actor(principal));
	}

	@PostMapping("/import/confirm")
	@ResponseStatus(HttpStatus.OK)
	@Operation(summary = "Atomically apply a roster preview token")
	public RosterConfirmResponse confirm(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@Valid @RequestBody RosterConfirmRequest request,
			HttpServletRequest http) {
		return roster.confirm(courseId, request.previewToken(), actor(principal), audit(http));
	}

	private UserAccount actor(SagaUserPrincipal principal) {
		return users.findById(principal.getUserId()).orElseThrow();
	}

	private static AuditRequest audit(HttpServletRequest http) {
		return new AuditRequest(http.getHeader("X-Request-Id"), http.getRemoteAddr(), http.getHeader("User-Agent"));
	}
}
