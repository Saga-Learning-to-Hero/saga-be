package com.saga.be.repository;

import com.saga.be.entity.github.GitCommitBranch;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GitCommitBranchRepository extends JpaRepository<GitCommitBranch, UUID> {

	@Query(
			"""
			select b.commit.id, b.branchName
			from GitCommitBranch b
			where b.commit.id in :commitIds
			order by b.branchName asc
			""")
	List<Object[]> findCommitIdAndBranchNameByCommitIdIn(@Param("commitIds") Collection<UUID> commitIds);

	List<GitCommitBranch> findByCommit_IdInOrderByBranchNameAsc(Collection<UUID> commitIds);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from GitCommitBranch b where b.commit.repo.id = :repoId")
	int deleteByCommitRepoId(@Param("repoId") UUID repoId);
}
