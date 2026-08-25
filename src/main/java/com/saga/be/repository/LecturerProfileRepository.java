package com.saga.be.repository;

import com.saga.be.entity.account.LecturerProfile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LecturerProfileRepository extends JpaRepository<LecturerProfile, UUID> {
}
