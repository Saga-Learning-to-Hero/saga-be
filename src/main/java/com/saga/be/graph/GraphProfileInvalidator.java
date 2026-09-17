package com.saga.be.graph;

import com.saga.be.auth.UserProfileUpdated;
import com.saga.be.repository.TeamMemberRepository;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class GraphProfileInvalidator {

	private final TeamMemberRepository members;
	private final ProjectGraphProjector projector;

	public GraphProfileInvalidator(TeamMemberRepository members, ProjectGraphProjector projector) {
		this.members = members;
		this.projector = projector;
	}

	@EventListener
	public void onProfileUpdated(UserProfileUpdated event) {
		if (event == null || event.userId() == null) {
			return;
		}
		for (UUID projectId : members.findActiveProjectIdsByUserId(event.userId())) {
			projector.markDirty(projectId, "PROFILE");
		}
	}
}
