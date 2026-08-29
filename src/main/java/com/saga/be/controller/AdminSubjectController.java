package com.saga.be.controller;

import com.saga.be.dto.academic.CreateSubjectRequest;
import com.saga.be.dto.academic.CreateSyllabusRequest;
import com.saga.be.dto.academic.PatchSubjectRequest;
import com.saga.be.dto.academic.PatchSyllabusRequest;
import com.saga.be.dto.academic.SubjectResponse;
import com.saga.be.dto.academic.SyllabusDetailResponse;
import com.saga.be.dto.academic.SyllabusStructureRequest;
import com.saga.be.dto.academic.SyllabusSummaryResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.academic.AcademicCatalogService;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/admin/subjects")
@Tag(name = "Admin subjects", description = "Academic catalog: Subject and versioned syllabus. ADMIN only. Not a course offering.")
@SecurityRequirement(name = "SAGA_SESSION")
public class AdminSubjectController {

	private final AcademicCatalogService catalog;
	private final UserAccountRepository users;

	public AdminSubjectController(AcademicCatalogService catalog, UserAccountRepository users) {
		this.catalog = catalog;
		this.users = users;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a subject catalog entry")
	public SubjectResponse create(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@Valid @RequestBody CreateSubjectRequest request,
			HttpServletRequest http) {
		return catalog.createSubject(request, actor(principal), audit(http));
	}

	@GetMapping
	@Operation(summary = "List subjects")
	public List<SubjectResponse> list(
			@RequestParam(required = false) String code,
			@RequestParam(required = false) SubjectStatus status,
			@RequestParam(required = false) String q) {
		return catalog.listSubjects(code, status, q);
	}

	@GetMapping("/{subjectId}")
	@Operation(summary = "Get subject detail including syllabus versions")
	public SubjectResponse get(@PathVariable UUID subjectId) {
		return catalog.getSubject(subjectId);
	}

	@PatchMapping("/{subjectId}")
	@Operation(summary = "Update subject catalog fields or ACTIVE/INACTIVE status")
	public SubjectResponse patch(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID subjectId,
			@Valid @RequestBody PatchSubjectRequest request,
			HttpServletRequest http) {
		return catalog.updateSubject(subjectId, request, actor(principal), audit(http));
	}

	@PostMapping("/{subjectId}/syllabi")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a DRAFT syllabus version")
	public SyllabusSummaryResponse createSyllabus(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID subjectId,
			@Valid @RequestBody CreateSyllabusRequest request,
			HttpServletRequest http) {
		return catalog.createSyllabus(subjectId, request, actor(principal), audit(http));
	}

	@GetMapping("/{subjectId}/syllabi")
	@Operation(summary = "List syllabus versions for a subject")
	public List<SyllabusSummaryResponse> listSyllabi(@PathVariable UUID subjectId) {
		return catalog.listSyllabi(subjectId);
	}

	@GetMapping("/{subjectId}/syllabi/{syllabusVersionId}")
	@Operation(summary = "Get syllabus version with ordered academic structure")
	public SyllabusDetailResponse getSyllabus(
			@PathVariable UUID subjectId, @PathVariable UUID syllabusVersionId) {
		return catalog.getSyllabus(subjectId, syllabusVersionId);
	}

	@PatchMapping("/{subjectId}/syllabi/{syllabusVersionId}")
	@Operation(summary = "Update DRAFT syllabus metadata")
	public SyllabusSummaryResponse patchSyllabus(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID subjectId,
			@PathVariable UUID syllabusVersionId,
			@Valid @RequestBody PatchSyllabusRequest request,
			HttpServletRequest http) {
		return catalog.updateSyllabus(subjectId, syllabusVersionId, request, actor(principal), audit(http));
	}

	@PutMapping("/{subjectId}/syllabi/{syllabusVersionId}/structure")
	@Operation(summary = "Replace DRAFT academic structure atomically")
	public SyllabusDetailResponse replaceStructure(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID subjectId,
			@PathVariable UUID syllabusVersionId,
			@Valid @RequestBody SyllabusStructureRequest request,
			HttpServletRequest http) {
		return catalog.replaceStructure(subjectId, syllabusVersionId, request, actor(principal), audit(http));
	}

	@PostMapping("/{subjectId}/syllabi/{syllabusVersionId}/publish")
	@Operation(summary = "Publish a DRAFT syllabus; the snapshot becomes immutable")
	public SyllabusDetailResponse publish(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID subjectId,
			@PathVariable UUID syllabusVersionId,
			HttpServletRequest http) {
		return catalog.publish(subjectId, syllabusVersionId, actor(principal), audit(http));
	}

	@PostMapping("/{subjectId}/syllabi/{syllabusVersionId}/archive")
	@Operation(summary = "Archive a PUBLISHED syllabus; it remains readable")
	public SyllabusDetailResponse archive(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID subjectId,
			@PathVariable UUID syllabusVersionId,
			HttpServletRequest http) {
		return catalog.archive(subjectId, syllabusVersionId, actor(principal), audit(http));
	}

	private UserAccount actor(SagaUserPrincipal principal) {
		return users.findById(principal.getUserId()).orElseThrow();
	}

	private static AuditRequest audit(HttpServletRequest http) {
		return new AuditRequest(http.getHeader("X-Request-Id"), http.getRemoteAddr(), http.getHeader("User-Agent"));
	}
}
