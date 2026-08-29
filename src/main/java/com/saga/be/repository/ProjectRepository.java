package com.saga.be.repository;

import com.saga.be.entity.project.Project;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

	boolean existsByCourse_Id(UUID courseId);
}
