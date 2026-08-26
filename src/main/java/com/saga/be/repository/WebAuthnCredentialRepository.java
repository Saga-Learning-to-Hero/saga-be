package com.saga.be.repository;

import com.saga.be.entity.security.WebAuthnCredential;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebAuthnCredentialRepository extends JpaRepository<WebAuthnCredential, UUID> {

	Optional<WebAuthnCredential> findByCredentialId(String credentialId);

	List<WebAuthnCredential> findByUserAccount_Id(UUID userId);
}
