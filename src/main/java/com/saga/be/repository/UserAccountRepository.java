package com.saga.be.repository;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

	Optional<UserAccount> findByEmail(String email);

	List<UserAccount> findByEmailIn(Collection<String> emails);

	Optional<UserAccount> findByUsername(String username);

	Optional<UserAccount> findByGoogleSubject(String googleSubject);

	boolean existsByUsername(String username);

	boolean existsByEmail(String email);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from UserAccount u where u.id = :id")
	Optional<UserAccount> findByIdForUpdate(@Param("id") UUID id);

	@Query(
			"""
			select u.id from UserAccount u
			where u.accountStatus = :status
			  and u.accountRole in :roles
			order by u.id asc
			""")
	List<UUID> findIdsByAccountStatusAndAccountRoleInOrderByIdAsc(
			@Param("status") AccountStatus status, @Param("roles") Collection<AccountRole> roles);

	@Query(
			value =
					"""
					select new com.saga.be.repository.AdminUserQueryRow(
							u.id, u.email, u.username, u.fullName, u.avatarUrl,
							u.accountRole, u.accountStatus, sp.studentCode, lp.id, u.createdAt)
					from UserAccount u
					left join StudentProfile sp on sp.userAccount = u
					left join LecturerProfile lp on lp.userAccount = u
					where u.accountRole <> com.saga.be.entity.enums.AccountRole.ADMIN
					  and (:role is null or u.accountRole = :role)
					  and (:status is null or u.accountStatus = :status)
					  and (:qPattern is null
					    or lower(u.email) like :qPattern escape '\\'
					    or lower(coalesce(u.username, '')) like :qPattern escape '\\'
					    or lower(coalesce(u.fullName, '')) like :qPattern escape '\\')
					order by u.createdAt desc, u.id desc
					""",
			countQuery =
					"""
					select count(u.id)
					from UserAccount u
					where u.accountRole <> com.saga.be.entity.enums.AccountRole.ADMIN
					  and (:role is null or u.accountRole = :role)
					  and (:status is null or u.accountStatus = :status)
					  and (:qPattern is null
					    or lower(u.email) like :qPattern escape '\\'
					    or lower(coalesce(u.username, '')) like :qPattern escape '\\'
					    or lower(coalesce(u.fullName, '')) like :qPattern escape '\\')
					""")
	Page<AdminUserQueryRow> searchAdminUsers(
			@Param("role") AccountRole role,
			@Param("status") AccountStatus status,
			@Param("qPattern") String qPattern,
			Pageable pageable);

	@Query(
			"""
			select new com.saga.be.repository.AdminUserQueryRow(
					u.id, u.email, u.username, u.fullName, u.avatarUrl,
					u.accountRole, u.accountStatus, sp.studentCode, lp.id, u.createdAt)
			from UserAccount u
			left join StudentProfile sp on sp.userAccount = u
			left join LecturerProfile lp on lp.userAccount = u
			where u.id = :id
			  and u.accountRole <> com.saga.be.entity.enums.AccountRole.ADMIN
			""")
	Optional<AdminUserQueryRow> findAdminUserById(@Param("id") UUID id);
}
