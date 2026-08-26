package com.saga.be.repository;

import com.saga.be.entity.warning.BusinessWarning;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessWarningRepository extends JpaRepository<BusinessWarning, UUID> {

	Optional<BusinessWarning> findByEventKey(String eventKey);
}
