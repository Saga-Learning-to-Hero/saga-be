package com.saga.be.repository;

import com.saga.be.entity.project.ProjectType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectTypeRepository extends JpaRepository<ProjectType, UUID> {

	List<ProjectType> findAllByOrderByCodeAsc();
}
