package com.saga.be.repository;

import com.saga.be.entity.integration.IdentityMappingHistory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityMappingHistoryRepository extends JpaRepository<IdentityMappingHistory, UUID> {}
