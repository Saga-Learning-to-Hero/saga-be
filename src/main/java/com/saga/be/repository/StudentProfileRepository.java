package com.saga.be.repository;

import com.saga.be.entity.account.StudentProfile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentProfileRepository extends JpaRepository<StudentProfile, UUID> {

	boolean existsByStudentCode(String studentCode);
}
