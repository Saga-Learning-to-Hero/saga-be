package com.saga.be.repository;

import com.saga.be.entity.academic.AcademicClass;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AcademicClassRepository extends JpaRepository<AcademicClass, UUID> {

	boolean existsBySemester_IdAndClassCode(UUID semesterId, String classCode);

	boolean existsBySemester_IdAndClassCodeAndIdNot(UUID semesterId, String classCode, UUID id);

	boolean existsBySemester_Id(UUID semesterId);

	List<AcademicClass> findByDeletedAtIsNullOrderByClassCodeAsc();

	List<AcademicClass> findBySemester_IdAndDeletedAtIsNullOrderByClassCodeAsc(UUID semesterId);
}
