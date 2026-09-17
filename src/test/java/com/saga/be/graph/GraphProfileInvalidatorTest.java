package com.saga.be.graph;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.auth.UserProfileUpdated;
import com.saga.be.repository.TeamMemberRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GraphProfileInvalidatorTest {

	@Mock
	private TeamMemberRepository members;
	@Mock
	private ProjectGraphProjector projector;

	private GraphProfileInvalidator invalidator;

	@BeforeEach
	void setUp() {
		invalidator = new GraphProfileInvalidator(members, projector);
	}

	@Test
	void marksEachActiveProjectDirty() {
		UUID userId = UUID.randomUUID();
		UUID projectA = UUID.randomUUID();
		UUID projectB = UUID.randomUUID();
		when(members.findActiveProjectIdsByUserId(userId)).thenReturn(List.of(projectA, projectB));
		invalidator.onProfileUpdated(new UserProfileUpdated(userId));
		verify(projector).markDirty(projectA, "PROFILE");
		verify(projector).markDirty(projectB, "PROFILE");
	}

	@Test
	void ignoresNullUser() {
		invalidator.onProfileUpdated(new UserProfileUpdated(null));
		verify(projector, never()).markDirty(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}
}
