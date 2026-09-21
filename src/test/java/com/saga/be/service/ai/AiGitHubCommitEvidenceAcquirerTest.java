package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.AiAnalysisProperties;
import com.saga.be.entity.enums.AiEvidenceType;
import com.saga.be.integration.github.GitHubAppJwtService;
import com.saga.be.integration.github.GitHubOAuthClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiGitHubCommitEvidenceAcquirerTest {
	@Test
	void exactShaProducesManifestAndBoundedDiffHunks() {
		GitHubOAuthClient github = mock(GitHubOAuthClient.class); GitHubAppJwtService jwt = mock(GitHubAppJwtService.class);
		AiAnalysisProperties props = new AiAnalysisProperties(); props.setMaxChangedFiles(1); props.setMaxPatchBytes(1024);
		when(jwt.createJwt()).thenReturn("jwt"); when(github.createInstallationToken("jwt", 7L)).thenReturn("token");
		when(github.getCommit("token", "org", "repo", "abc")).thenReturn(detail("abc", List.of(file("src/A.java", "@@ -1 +1 @@\n-old\n+new"), file("src/B.java", "@@ -1 +1 @@\n-old\n+new"))));
		List<AiEvidenceDraft> rows = acquirer(github, jwt, props).acquire(new AiGitHubCommitEvidenceAcquirer.Target(UUID.randomUUID(), 7L, "org", "repo", "abc"));
		assertThat(rows).extracting(AiEvidenceDraft::type).contains(AiEvidenceType.CHANGED_FILE_MANIFEST, AiEvidenceType.DIFF_HUNK);
		assertThat(rows.stream().filter(row -> row.type() == AiEvidenceType.CHANGED_FILE_MANIFEST).findFirst().orElseThrow().payloadJson()).contains("FILE_LIMIT");
		verify(github).getCommit("token", "org", "repo", "abc");
	}

	@Test
	void unavailableGitHubProducesTruthfulNoDiffStatus() {
		GitHubOAuthClient github = mock(GitHubOAuthClient.class); GitHubAppJwtService jwt = mock(GitHubAppJwtService.class);
		when(jwt.createJwt()).thenReturn("jwt"); when(github.createInstallationToken("jwt", 7L)).thenThrow(new RuntimeException("provider body must not persist"));
		List<AiEvidenceDraft> rows = acquirer(github, jwt, new AiAnalysisProperties()).acquire(new AiGitHubCommitEvidenceAcquirer.Target(UUID.randomUUID(), 7L, "org", "repo", "abc"));
		assertThat(rows).hasSize(1); assertThat(rows.getFirst().payloadJson()).contains("UNAVAILABLE").contains("codeDiffAvailable\":false").doesNotContain("provider body");
	}

	@Test
	void secretAndMissingPatchAreExcludedRatherThanSent() {
		GitHubOAuthClient github = mock(GitHubOAuthClient.class); GitHubAppJwtService jwt = mock(GitHubAppJwtService.class);
		when(jwt.createJwt()).thenReturn("jwt"); when(github.createInstallationToken("jwt", 7L)).thenReturn("token");
		when(github.getCommit("token", "org", "repo", "abc")).thenReturn(detail("abc", List.of(file(".env", "TOKEN=secret"), file("image.png", null))));
		List<AiEvidenceDraft> rows = acquirer(github, jwt, new AiAnalysisProperties()).acquire(new AiGitHubCommitEvidenceAcquirer.Target(UUID.randomUUID(), 7L, "org", "repo", "abc"));
		assertThat(rows).noneMatch(row -> row.type() == AiEvidenceType.DIFF_HUNK); assertThat(rows.getFirst().payloadJson()).contains("SECRET_SUSPECTED").contains("PATCH_NOT_RETURNED").doesNotContain("TOKEN=secret");
	}

	private static AiGitHubCommitEvidenceAcquirer acquirer(GitHubOAuthClient github, GitHubAppJwtService jwt, AiAnalysisProperties props) { return new AiGitHubCommitEvidenceAcquirer(github, jwt, props, new ObjectMapper()); }
	private static GitHubOAuthClient.CommitDetail detail(String sha, List<GitHubOAuthClient.CommitFileChange> files) { return new GitHubOAuthClient.CommitDetail(sha, null, null, null, null, null, null, List.of(), files, false); }
	private static GitHubOAuthClient.CommitFileChange file(String path, String patch) { return new GitHubOAuthClient.CommitFileChange(path, null, "modified", 1, 1, 2, patch); }
}
