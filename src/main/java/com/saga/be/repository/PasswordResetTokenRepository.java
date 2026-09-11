package com.saga.be.repository;

import com.saga.be.entity.account.PasswordResetToken;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

	Optional<PasswordResetToken> findByTokenHash(String tokenHash);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from PasswordResetToken t where t.tokenHash = :tokenHash")
	Optional<PasswordResetToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

	/** Invalidates every still-unused token for a user before a fresh one is issued. */
	@Modifying
	@Transactional
	@Query(
			"update PasswordResetToken t set t.usedAt = :now "
					+ "where t.user.id = :userId and t.usedAt is null")
	int invalidateUnusedForUser(@Param("userId") UUID userId, @Param("now") LocalDateTime now);
}
