package com.saga.be.repository;

import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LecturerProfileRepository extends JpaRepository<LecturerProfile, UUID> {

	Optional<LecturerProfile> findByUserAccount_Id(UUID userAccountId);

	boolean existsByUserAccount_Id(UUID userAccountId);

	@Query(
			"""
			select p from LecturerProfile p
			join fetch p.userAccount u
			where (:assignable = false
			    or (u.accountRole = :lecturerRole and u.accountStatus = :activeStatus))
			  and (:assignable = true
			    or (u.accountRole <> :lecturerRole or u.accountStatus <> :activeStatus))
			  and (:q is null
			    or lower(u.email) like lower(concat('%', :q, '%')) escape '\\'
			    or lower(coalesce(u.fullName, '')) like lower(concat('%', :q, '%')) escape '\\')
			order by coalesce(u.fullName, u.email) asc, u.email asc, p.id asc
			""")
	List<LecturerProfile> searchDirectory(
			@Param("assignable") boolean assignable,
			@Param("lecturerRole") AccountRole lecturerRole,
			@Param("activeStatus") AccountStatus activeStatus,
			@Param("q") String q);

	/**
	 * Admin lecturer management page. Callers must pass an unsorted {@link Pageable}; sort is in JPQL.
	 * userAccount is a to-one fetch, so Pageable stays database-backed.
	 */
	@Query(
			value =
					"""
					select p from LecturerProfile p
					join fetch p.userAccount u
					where (:assignable = false
					    or (u.accountRole = :lecturerRole and u.accountStatus = :activeStatus))
					  and (:assignable = true
					    or (u.accountRole <> :lecturerRole or u.accountStatus <> :activeStatus))
					  and (:q is null
					    or lower(u.email) like lower(concat('%', :q, '%')) escape '\\'
					    or lower(coalesce(u.fullName, '')) like lower(concat('%', :q, '%')) escape '\\')
					order by coalesce(u.fullName, u.email) asc, u.email asc, p.id asc
					""",
			countQuery =
					"""
					select count(p.id)
					from LecturerProfile p
					join p.userAccount u
					where (:assignable = false
					    or (u.accountRole = :lecturerRole and u.accountStatus = :activeStatus))
					  and (:assignable = true
					    or (u.accountRole <> :lecturerRole or u.accountStatus <> :activeStatus))
					  and (:q is null
					    or lower(u.email) like lower(concat('%', :q, '%')) escape '\\'
					    or lower(coalesce(u.fullName, '')) like lower(concat('%', :q, '%')) escape '\\')
					""")
	Page<LecturerProfile> searchDirectoryPage(
			@Param("assignable") boolean assignable,
			@Param("lecturerRole") AccountRole lecturerRole,
			@Param("activeStatus") AccountStatus activeStatus,
			@Param("q") String q,
			Pageable pageable);
}
