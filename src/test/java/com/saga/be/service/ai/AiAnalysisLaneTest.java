package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Runs someone is waiting on never queue behind background work (automatic reviews, backfill, recovery). */
class AiAnalysisLaneTest {

	private final AiAnalysisExecutionService execution = mock(AiAnalysisExecutionService.class);
	private final List<Runnable> interactive = new ArrayList<>();
	private final List<Runnable> background = new ArrayList<>();

	private AiAnalysisExecutor dispatcher() {
		AiAnalysisExecutor dispatcher = new AiAnalysisExecutor(interactive::add, execution, mock(AiAnalysisStateService.class));
		dispatcher.setBackground(background::add);
		return dispatcher;
	}

	@Test
	void aRequestedRunTakesTheInteractiveLane() {
		dispatcher().enqueue(UUID.randomUUID());

		assertThat(interactive).hasSize(1);
		assertThat(background).isEmpty();
	}

	@Test
	void runsEnqueuedInsideInBackgroundTakeTheBackgroundLane_andTheFlagIsRestored() {
		AiAnalysisExecutor dispatcher = dispatcher();
		UUID automatic = UUID.randomUUID();

		String result = AiAnalysisExecutor.inBackground(() -> {
			dispatcher.enqueue(automatic);
			return "done";
		});
		dispatcher.enqueue(UUID.randomUUID());

		assertThat(result).isEqualTo("done");
		assertThat(background).hasSize(1);
		assertThat(interactive).hasSize(1);
		background.getFirst().run();
		verify(execution).execute(automatic);
	}

	@Test
	void theFlagIsRestoredEvenWhenTheWorkThrows() {
		AiAnalysisExecutor dispatcher = dispatcher();
		try {
			AiAnalysisExecutor.inBackground(() -> {
				throw new IllegalStateException("boom");
			});
		} catch (IllegalStateException expected) {
			// the lane must not leak into the next request on this thread
		}

		dispatcher.enqueue(UUID.randomUUID());

		assertThat(interactive).hasSize(1);
		assertThat(background).isEmpty();
	}

	@Test
	void recoveryUsesTheBackgroundLane_andARunAlreadyWaitingIsNotCopied() {
		AiAnalysisExecutor dispatcher = dispatcher();
		UUID waiting = UUID.randomUUID();
		dispatcher.enqueue(waiting);

		dispatcher.enqueueBackground(waiting);
		dispatcher.enqueueBackground(UUID.randomUUID());

		assertThat(interactive).hasSize(1);
		assertThat(background).hasSize(1);
	}

	@Test
	void withoutABackgroundLaneEverythingSharesTheOneExecutor() {
		AiAnalysisExecutor dispatcher = new AiAnalysisExecutor(interactive::add, execution, mock(AiAnalysisStateService.class));

		AiAnalysisExecutor.inBackground(() -> {
			dispatcher.enqueue(UUID.randomUUID());
			return null;
		});
		dispatcher.enqueueBackground(UUID.randomUUID());

		assertThat(interactive).hasSize(2);
	}
}
