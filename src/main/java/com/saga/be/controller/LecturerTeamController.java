package com.saga.be.controller;

import com.saga.be.dto.team.LecturerCourseTeamsResponse;
import com.saga.be.dto.team.TeamConfirmRequest;
import com.saga.be.dto.team.TeamConfirmResponse;
import com.saga.be.dto.team.TeamPreviewResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.team.LecturerTeamService;
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
@RequestMapping("/api/lecturer/courses/{courseId}/teams")
@Tag(name = "Lecturer teams", description = "Desired-state team assignment for an assigned course.")
@SecurityRequirement(name = "SAGA_SESSION")
public class LecturerTeamController {

	private static final MediaType XLSX =
			MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

	private final LecturerTeamService teams;
	private final UserAccountRepository users;

	public LecturerTeamController(LecturerTeamService teams, UserAccountRepository users) {
		this.teams = teams;
		this.users = users;
	}

	@GetMapping
	@Operation(summary = "List teams and members for an assigned course")
	public LecturerCourseTeamsResponse list(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId) {
		return teams.listTeams(actor(principal), courseId);
	}

	@GetMapping("/template")
	@Operation(summary = "Download the team assignment XLSX template")
	public ResponseEntity<byte[]> template(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId) {
		byte[] body = teams.template(actor(principal), courseId);
		return ResponseEntity.ok()
				.contentType(XLSX)
				.header(
						HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename("Team_Assignment.xlsx").build().toString())
				.body(body);
	}

	@PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Validate a team assignment workbook without writing teams")
	public TeamPreviewResponse preview(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@RequestPart("file") MultipartFile file) {
		byte[] bytes;
		try {
			bytes = file == null ? new byte[0] : file.getBytes();
		} catch (IOException ex) {
			throw new AcademicException(
					AcademicErrorCode.TEAM_FILE_INVALID, HttpStatus.BAD_REQUEST, "Team file could not be read.");
		}
		return teams.preview(actor(principal), courseId, bytes);
	}

	@PostMapping("/import/confirm")
	@ResponseStatus(HttpStatus.OK)
	@Operation(summary = "Atomically apply a team assignment preview token")
	public TeamConfirmResponse confirm(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@Valid @RequestBody TeamConfirmRequest request,
			HttpServletRequest http) {
		return teams.confirm(actor(principal), courseId, request.previewToken(), audit(http));
	}

	private UserAccount actor(SagaUserPrincipal principal) {
		return users.findById(principal.getUserId()).orElseThrow();
	}

	private static AuditRequest audit(HttpServletRequest http) {
		return new AuditRequest(http.getHeader("X-Request-Id"), http.getRemoteAddr(), http.getHeader("User-Agent"));
	}
}
