package com.saga.be.repository;

import com.saga.be.entity.project.TeamMember;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamMemberRepository extends JpaRepository<TeamMember, UUID> {

	List<TeamMember> findByTeam_Id(UUID teamId);
}
