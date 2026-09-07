package com.saga.be.service.projection;

import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.enums.IdentityMappingStatus;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.integration.IdentityMap;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.StudentProfileRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class GitCommitProjectionService {

	public record CommitDraft(
			String sha, String message, LocalDateTime committedAt, String authorExternalId, String authorLogin, String headRef) {}

	private static final List<IdentityMappingStatus> ACTIVE_STATUSES =
			List.of(IdentityMappingStatus.ACTIVE, IdentityMappingStatus.VERIFIED, IdentityMappingStatus.PENDING);

	private final GitCommitRepository commits;
	private final IdentityMapRepository identities;
	private final StudentProfileRepository students;
	private final JiraIntegrationRepository jiraIntegrations;
	private final CommitTaskAutoLinkService autoLink;

	public GitCommitProjectionService(
			GitCommitRepository commits,
			IdentityMapRepository identities,
			StudentProfileRepository students,
			JiraIntegrationRepository jiraIntegrations,
			CommitTaskAutoLinkService autoLink) {
		this.commits = commits;
		this.identities = identities;
		this.students = students;
		this.jiraIntegrations = jiraIntegrations;
		this.autoLink = autoLink;
	}

	@Transactional
	public int upsertBatch(GitRepo repo, List<CommitDraft> drafts) {
		if (repo == null || repo.getId() == null || drafts == null || drafts.isEmpty()) {
			return 0;
		}
		List<CommitDraft> valid = drafts.stream()
				.filter(item -> item != null && item.sha() != null && !item.sha().isBlank())
				.toList();
		if (valid.isEmpty()) {
			return 0;
		}
		Set<String> shas = valid.stream().map(CommitDraft::sha).collect(Collectors.toCollection(HashSet::new));
		Map<String, GitCommit> existing = commits.findByRepo_IdAndShaHashIn(repo.getId(), shas).stream()
				.collect(Collectors.toMap(GitCommit::getShaHash, Function.identity(), (a, b) -> a));
		Map<String, StudentProfile> authors = resolveAuthors(valid);
		List<GitCommit> saved = new ArrayList<>(valid.size());
		for (CommitDraft draft : valid) {
			GitCommit commit = existing.getOrDefault(draft.sha(), new GitCommit());
			if (commit.getId() == null) {
				commit.setRepo(repo);
				commit.setShaHash(draft.sha());
			}
			commit.setGithubCommitId(draft.sha());
			commit.setMessage(draft.message());
			commit.setCommittedAt(draft.committedAt());
			String external = draft.authorExternalId() != null
					? draft.authorExternalId()
					: draft.authorLogin();
			commit.setAuthorExternalId(external);
			String key = authorKey(draft);
			commit.setAuthorStudent(key == null ? null : authors.get(key));
			commit.setHeadRef(draft.headRef());
			saved.add(commit);
		}
		List<GitCommit> persisted;
		try {
			persisted = commits.saveAll(saved);
		} catch (DataIntegrityViolationException ex) {
			Map<String, GitCommit> reloaded = commits.findByRepo_IdAndShaHashIn(repo.getId(), shas).stream()
					.collect(Collectors.toMap(GitCommit::getShaHash, Function.identity(), (a, b) -> a));
			List<GitCommit> merged = new ArrayList<>();
			for (GitCommit candidate : saved) {
				GitCommit row = reloaded.getOrDefault(candidate.getShaHash(), candidate);
				if (row.getId() != null && row != candidate) {
					row.setGithubCommitId(candidate.getGithubCommitId());
					row.setMessage(candidate.getMessage());
					row.setCommittedAt(candidate.getCommittedAt());
					row.setAuthorExternalId(candidate.getAuthorExternalId());
					row.setAuthorStudent(candidate.getAuthorStudent());
					row.setHeadRef(candidate.getHeadRef());
					merged.add(row);
				} else {
					merged.add(candidate);
				}
			}
			persisted = merged.isEmpty() ? List.of() : commits.saveAll(merged);
		}
		String projectKey = jiraIntegrations
				.findByProject_Id(repo.getProject().getId())
				.map(row -> row.getProjectKey())
				.orElse(null);
		autoLink.linkCommits(repo.getProject().getId(), projectKey, persisted);
		return persisted.size();
	}

	private Map<String, StudentProfile> resolveAuthors(List<CommitDraft> drafts) {
		Set<String> ids = drafts.stream()
				.map(CommitDraft::authorExternalId)
				.filter(Objects::nonNull)
				.filter(id -> !id.isBlank())
				.collect(Collectors.toCollection(HashSet::new));
		Set<String> logins = drafts.stream()
				.map(CommitDraft::authorLogin)
				.filter(Objects::nonNull)
				.filter(login -> !login.isBlank())
				.map(login -> login.toLowerCase(Locale.ROOT))
				.collect(Collectors.toCollection(HashSet::new));
		Map<String, StudentProfile> byKey = new HashMap<>();
		if (!ids.isEmpty()) {
			List<IdentityMap> byId = identities.findFetchedByProviderAndExternalAccountIdInAndMappingStatusIn(
					IntegrationProvider.GITHUB, ids, ACTIVE_STATUSES);
			attachProfiles(byId, byKey, true);
		}
		if (!logins.isEmpty()) {
			List<IdentityMap> byLogin = identities.findFetchedByProviderAndExternalUsernameLowerInAndMappingStatusIn(
					IntegrationProvider.GITHUB, logins, ACTIVE_STATUSES);
			attachProfiles(byLogin, byKey, false);
		}
		return byKey;
	}

	private void attachProfiles(List<IdentityMap> maps, Map<String, StudentProfile> byKey, boolean byAccountId) {
		Set<UUID> userIds = maps.stream().map(map -> map.getUserAccount().getId()).collect(Collectors.toSet());
		if (userIds.isEmpty()) {
			return;
		}
		Map<UUID, StudentProfile> profiles = students.findFetchedByUserAccount_IdIn(userIds).stream()
				.collect(Collectors.toMap(p -> p.getUserAccount().getId(), Function.identity(), (a, b) -> a));
		for (IdentityMap map : maps) {
			StudentProfile profile = profiles.get(map.getUserAccount().getId());
			if (profile == null) {
				continue;
			}
			if (byAccountId && map.getExternalAccountId() != null) {
				byKey.put(map.getExternalAccountId(), profile);
			}
			if (!byAccountId && map.getExternalUsername() != null) {
				byKey.put(map.getExternalUsername().toLowerCase(Locale.ROOT), profile);
			}
		}
	}

	private static String authorKey(CommitDraft draft) {
		if (draft.authorExternalId() != null && !draft.authorExternalId().isBlank()) {
			return draft.authorExternalId();
		}
		return draft.authorLogin() == null ? null : draft.authorLogin().toLowerCase(Locale.ROOT);
	}
}
