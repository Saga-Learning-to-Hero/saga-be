package com.saga.be.repository;

import com.saga.be.entity.account.UserAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

	Optional<UserAccount> findByEmail(String email);

	Optional<UserAccount> findByUsername(String username);

	Optional<UserAccount> findByGoogleSubject(String googleSubject);

	boolean existsByUsername(String username);

	boolean existsByEmail(String email);
}
