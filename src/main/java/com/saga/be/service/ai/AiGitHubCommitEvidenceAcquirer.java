package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.AiAnalysisProperties;
import com.saga.be.entity.enums.AiEvidenceType;
import com.saga.be.integration.github.GitHubAppJwtService;
import com.saga.be.integration.github.GitHubOAuthClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Exact-SHA, bounded GitHub evidence acquisition. It never persists data or holds a DB transaction. */
@Component
@Profile("!test")
public class AiGitHubCommitEvidenceAcquirer {
	private static final Pattern HUNK = Pattern.compile("(?m)(?=^@@ )");
	private final GitHubOAuthClient github; private final GitHubAppJwtService jwt; private final AiAnalysisProperties properties; private final ObjectMapper mapper;
	public AiGitHubCommitEvidenceAcquirer(GitHubOAuthClient github, GitHubAppJwtService jwt, AiAnalysisProperties properties, ObjectMapper mapper) { this.github = github; this.jwt = jwt; this.properties = properties; this.mapper = mapper; }

	public List<AiEvidenceDraft> acquire(Target target) {
		try {
			if (target.installationId() == null || target.owner() == null || target.repo() == null || target.sha() == null) return unavailable("PROVIDER_AUTH_UNAVAILABLE");
			String token = github.createInstallationToken(jwt.createJwt(), target.installationId());
			GitHubOAuthClient.CommitDetail detail = github.getCommit(token, target.owner(), target.repo(), target.sha());
			if (detail == null || detail.sha() == null || !detail.sha().equalsIgnoreCase(target.sha())) return unavailable("REPOSITORY_UNAVAILABLE");
			return bounded(target, detail);
		} catch (RuntimeException ex) { return unavailable("REPOSITORY_UNAVAILABLE"); }
	}

	private List<AiEvidenceDraft> bounded(Target target, GitHubOAuthClient.CommitDetail detail) {
		List<AiEvidenceDraft> result = new ArrayList<>(); List<Map<String, Object>> files = new ArrayList<>();
		int includedBytes = 0, eligible = 0, analyzed = 0, omitted = 0, totalPatchBytes = 0;
		for (GitHubOAuthClient.CommitFileChange file : detail.files() == null ? List.<GitHubOAuthClient.CommitFileChange>of() : detail.files()) {
			String path = file.filename(); String patch = file.patch(); String reason = exclusion(path, patch);
			int bytes = patch == null ? 0 : patch.getBytes(StandardCharsets.UTF_8).length; totalPatchBytes += bytes;
			if (reason == null) { eligible++; if (eligible > properties.getMaxChangedFiles()) reason = "FILE_LIMIT"; else if (includedBytes + bytes > properties.getMaxPatchBytes()) reason = "BYTE_LIMIT"; }
			Map<String, Object> item = new LinkedHashMap<>(); item.put("path", path); item.put("previousPath", file.previousFilename()); item.put("status", file.status()); item.put("additions", file.additions()); item.put("deletions", file.deletions()); item.put("changes", file.changes()); item.put("patchAvailable", patch != null); item.put("exclusionReason", reason); files.add(item);
			if (reason != null) { omitted++; continue; }
			includedBytes += bytes; analyzed++; int hunk = 0;
			for (String text : HUNK.split(patch)) { if (text == null || text.isBlank()) continue; hunk++; String hunkId = "H" + hunk;
				Map<String, Object> hunkPayload = new LinkedHashMap<>(); hunkPayload.put("path", path); hunkPayload.put("hunkId", hunkId); hunkPayload.put("patch", text);
				Map<String, Object> meta = new LinkedHashMap<>(); meta.put("repositoryId", target.repositoryId()); meta.put("commitSha", target.sha()); meta.put("path", path); meta.put("hunkId", hunkId);
				result.add(new AiEvidenceDraft(AiEvidenceType.DIFF_HUNK, "repo:" + target.repositoryId() + ":sha:" + target.sha() + ":path:" + path + ":hunk:" + hunkId, json(hunkPayload), json(meta)));
			}
		}
		Map<String, Object> manifest = new LinkedHashMap<>(); manifest.put("providerEvidenceStatus", "AVAILABLE"); manifest.put("filesTotal", files.size()); manifest.put("filesAnalyzed", analyzed); manifest.put("filesOmitted", omitted); manifest.put("totalAvailablePatchBytes", totalPatchBytes); manifest.put("patchBytesIncluded", includedBytes); manifest.put("coverage", omitted == 0 && !detail.filesTruncated() ? "COMPLETE" : "PARTIAL"); manifest.put("providerFilesTruncated", detail.filesTruncated()); manifest.put("files", files);
		result.add(0, new AiEvidenceDraft(AiEvidenceType.CHANGED_FILE_MANIFEST, "github-commit-manifest:" + target.sha(), json(manifest), null));
		result.add(1, new AiEvidenceDraft(AiEvidenceType.PROVIDER_EVIDENCE_STATUS, "github-commit-status:" + target.sha(), json(Map.of("providerEvidenceStatus", "AVAILABLE", "codeDiffAvailable", !result.isEmpty())), null));
		return List.copyOf(result);
	}

	private List<AiEvidenceDraft> unavailable(String reason) {
		return List.of(new AiEvidenceDraft(AiEvidenceType.PROVIDER_EVIDENCE_STATUS, "github-commit-status", json(Map.of("providerEvidenceStatus", "UNAVAILABLE", "codeDiffAvailable", false, "reason", reason)), null));
	}
	private static String exclusion(String path, String patch) {
		String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
		if (p.matches("(^|/)(\\.env|.*\\.pem|.*\\.key|.*credentials.*|.*secret.*)(/|$|\\..*)")) return "SECRET_SUSPECTED";
		if (p.endsWith(".lock") || p.endsWith("package-lock.json") || p.endsWith("yarn.lock") || p.contains("/generated/") || p.contains("/vendor/")) return "GENERATED_OR_LOCKFILE";
		if (patch == null) return "PATCH_NOT_RETURNED";
		return null;
	}
	private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException("GitHub AI evidence serialization failed", ex); } }
	public record Target(UUID repositoryId, Long installationId, String owner, String repo, String sha) {}
}
