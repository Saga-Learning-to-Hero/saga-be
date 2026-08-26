package com.saga.be.repository;

import com.saga.be.entity.account.UserAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

	Optional<UserAccount> findByEmail(String email);

	Optional<UserAccount> findByUsername(String username);

	Optional<UserAccount> findByGoogleSubject(String googleSubject);

	boolean existsByUsername(String username);

	boolean existsByEmail(String email);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from UserAccount u where u.id = :id")
	Optional<UserAccount> findByIdForUpdate(@Param("id") UUID id);
}
