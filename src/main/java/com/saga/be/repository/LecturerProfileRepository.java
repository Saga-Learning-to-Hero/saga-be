package com.saga.be.repository;

import com.saga.be.entity.account.LecturerProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LecturerProfileRepository extends JpaRepository<LecturerProfile, UUID> {

	Optional<LecturerProfile> findByUserAccount_Id(UUID userAccountId);

	boolean existsByUserAccount_Id(UUID userAccountId);
}
