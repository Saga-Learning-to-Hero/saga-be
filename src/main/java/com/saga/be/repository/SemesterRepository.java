package com.saga.be.repository;

import com.saga.be.entity.academic.Semester;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SemesterRepository extends JpaRepository<Semester, UUID> {

	Optional<Semester> findByCode(String code);

	boolean existsByCode(String code);

	boolean existsByCodeAndIdNot(String code, UUID id);

	List<Semester> findByDeletedAtIsNullOrderByStartDateDesc();
}
