package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.AiModelProvider;
import com.saga.be.ai.AiProviderResponse;
import com.saga.be.entity.ai.AiAnalysisEvidence;
import com.saga.be.entity.ai.AiAnalysisProviderDecision;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.*;
import com.saga.be.repository.AiAnalysisEvidenceRepository;
import com.saga.be.repository.AiAnalysisProviderDecisionRepository;
import com.saga.be.repository.AiAnalysisRunRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiAnalysisConcurrencyStateTest {
	@Mock private AiAnalysisRunRepository runs;
	@Mock private AiAnalysisEvidenceRepository evidence;
	@Mock private AiAnalysisProviderDecisionRepository decisions;
	private AiAnalysisStateService state;

	@BeforeEach
	void setUp() { state = new AiAnalysisStateService(runs, evidence, decisions); }

	@Test
	void twoSimultaneousClaimsHaveExactlyOneWinner() throws Exception {
		UUID runId = UUID.randomUUID();
		AtomicBoolean available = new AtomicBoolean(true);
		when(runs.claimQueued(eq(runId), any())).thenAnswer(invocation -> available.compareAndSet(true, false) ? 1 : 0);
		when(decisions.startPending(runId)).thenReturn(1);
		ExecutorService callers = Executors.newFixedThreadPool(2);
		try {
			List<Future<Boolean>> results = callers.invokeAll(List.of(() -> state.claim(runId), () -> state.claim(runId)));
			assertThat(results.stream().filter(future -> get(future)).count()).isEqualTo(1);
			verify(decisions, times(1)).startPending(runId);
		} finally { callers.shutdownNow(); }
	}

	@Test
	void completedRunCannotBeClaimedAgain() {
		UUID completed = UUID.randomUUID();
		when(runs.claimQueued(eq(completed), any())).thenReturn(0);
		assertThat(state.claim(completed)).isFalse();
		verifyNoInteractions(decisions);
	}

	@Test
	void failedRunCannotBeClaimedAgain() {
		UUID failed = UUID.randomUUID();
		when(runs.claimQueued(eq(failed), any())).thenReturn(0);
		assertThat(state.claim(failed)).isFalse();
		verifyNoInteractions(decisions);
	}

	@Test
	void completionOnlyFinalizesAStillRunningRun() {
		UUID runId = UUID.randomUUID();
		when(runs.completeRunning(eq(runId), any())).thenReturn(0);
		assertThat(state.complete(runId, "{}", true, 1L, 1L, 1L, null, null)).isFalse();
		verify(decisions, never()).completeRunning(any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void staleRecoveryDoesNotOverwriteCompletionThatWonTheRace() {
		UUID runId = UUID.randomUUID(); LocalDateTime cutoff = LocalDateTime.now().minusMinutes(1);
		when(runs.findStaleRunningIds(cutoff)).thenReturn(List.of(runId));
		when(runs.recoverStaleRunning(eq(runId), eq(cutoff), eq("AI_RUNNING_STALE_RECOVERED"), any())).thenReturn(0);
		assertThat(state.failStaleRunning(cutoff)).isZero();
		verify(decisions, never()).failActive(any(), any(), anyBoolean(), any());
	}

	@Test
	void staleRecoveryThatWinsAlsoFailsItsRunningDecision() {
		UUID runId = UUID.randomUUID(); LocalDateTime cutoff = LocalDateTime.now().minusMinutes(1);
		when(runs.findStaleRunningIds(cutoff)).thenReturn(List.of(runId));
		when(runs.recoverStaleRunning(eq(runId), eq(cutoff), eq("AI_RUNNING_STALE_RECOVERED"), any())).thenReturn(1);
		when(decisions.failActive(eq(runId), eq("AI_RUNNING_STALE_RECOVERED"), eq(false), any())).thenReturn(1);
		assertThat(state.failStaleRunning(cutoff)).isEqualTo(1);
		verify(decisions).failActive(eq(runId), eq("AI_RUNNING_STALE_RECOVERED"), eq(false), any());
	}

	@Test
	void duplicateDeliveryInvokesTheProviderOnlyOnce() throws Exception {
		UUID runId = UUID.randomUUID();
		AiAnalysisStateService executionState = mock(AiAnalysisStateService.class);
		AtomicBoolean available = new AtomicBoolean(true); AtomicInteger providerCalls = new AtomicInteger();
		when(executionState.claim(runId)).thenAnswer(invocation -> available.compareAndSet(true, false));
		when(executionState.loadExecution(runId)).thenReturn(executionInput(runId));
		when(executionState.complete(eq(runId), anyString(), eq(true), any(), any(), any(), any(), any())).thenReturn(true);
		AiAnalysisExecutionService execution = execution(executionState, countingProvider(providerCalls));
		ExecutorService callers = Executors.newFixedThreadPool(2);
		try {
			callers.invokeAll(List.of(() -> { execution.execute(runId); return null; }, () -> { execution.execute(runId); return null; }));
			assertThat(providerCalls).hasValue(1);
			verify(executionState, times(1)).complete(eq(runId), anyString(), eq(true), any(), any(), any(), any(), any());
		} finally { callers.shutdownNow(); }
	}

	@Test
	void lateProviderResultIsDiscardedWhenRecoveryAlreadyFailedTheRun() {
		UUID runId = UUID.randomUUID();
		AiAnalysisStateService executionState = mock(AiAnalysisStateService.class);
		when(executionState.claim(runId)).thenReturn(true);
		when(executionState.loadExecution(runId)).thenReturn(executionInput(runId));
		when(executionState.complete(eq(runId), anyString(), eq(true), any(), any(), any(), any(), any())).thenReturn(false);
		AiAnalysisExecutionService execution = execution(executionState, countingProvider(new AtomicInteger()));
		execution.execute(runId);
		verify(executionState, never()).fail(eq(runId), anyString(), anyBoolean());
	}

	@Test
	void rejectedQueueFailsThePersistedRunAndDoesNotInvokeTheWorker() {
		AiAnalysisExecutionService execution = mock(AiAnalysisExecutionService.class);
		AiAnalysisStateService executionState = mock(AiAnalysisStateService.class);
		AiAnalysisExecutor dispatcher = new AiAnalysisExecutor(command -> { throw new RejectedExecutionException(); }, execution, executionState);
		UUID runId = UUID.randomUUID();
		dispatcher.enqueue(runId);
		verify(executionState).failQueued(runId, "AI_QUEUE_CAPACITY_EXCEEDED", false);
		verifyNoInteractions(execution);
	}

	@Test
	void queueRejectionOnlyTransitionsAStillQueuedRun() {
		UUID runId = UUID.randomUUID();
		when(runs.failQueued(eq(runId), eq("AI_QUEUE_CAPACITY_EXCEEDED"), any())).thenReturn(1);
		when(decisions.failActive(eq(runId), eq("AI_QUEUE_CAPACITY_EXCEEDED"), eq(false), any())).thenReturn(1);
		assertThat(state.failQueued(runId, "AI_QUEUE_CAPACITY_EXCEEDED", false)).isTrue();
		verify(runs, never()).failActive(any(), any(), any());
	}

	@Test
	void originalAndRecoveryEnqueuesStillInvokeTheProviderOnlyOnce() throws Exception {
		UUID runId = UUID.randomUUID();
		AiAnalysisStateService executionState = mock(AiAnalysisStateService.class);
		AtomicBoolean available = new AtomicBoolean(true); AtomicInteger providerCalls = new AtomicInteger();
		when(executionState.claim(runId)).thenAnswer(invocation -> available.compareAndSet(true, false));
		when(executionState.loadExecution(runId)).thenReturn(executionInput(runId));
		when(executionState.complete(eq(runId), anyString(), eq(true), any(), any(), any(), any(), any())).thenReturn(true);
		AiAnalysisExecutionService execution = execution(executionState, countingProvider(providerCalls));
		AiAnalysisExecutor dispatcher = new AiAnalysisExecutor(Runnable::run, execution, executionState);
		ExecutorService callers = Executors.newFixedThreadPool(2);
		try {
			callers.invokeAll(List.of(() -> { dispatcher.enqueue(runId); return null; }, () -> { dispatcher.enqueue(runId); return null; }));
			assertThat(providerCalls).hasValue(1);
		} finally { callers.shutdownNow(); }
	}

	private static AiAnalysisExecutionService execution(AiAnalysisStateService state, AiModelProvider provider) {
		return new AiAnalysisExecutionService(state, List.of(provider), new AiStructuredResultValidator(), new ObjectMapper());
	}

	private static AiModelProvider countingProvider(AtomicInteger calls) {
		FakeAiModelProvider fake = new FakeAiModelProvider();
		return new AiModelProvider() {
			public AiProviderRole role() { return fake.role(); }
			public String providerKey() { return fake.providerKey(); }
			public String providerConfigHash() { return fake.providerConfigHash(); }
			public String modelId() { return fake.modelId(); }
			public AiProviderResponse analyze(com.saga.be.ai.AiAnalysisRequest request) { calls.incrementAndGet(); return fake.analyze(request); }
		};
	}

	private static AiAnalysisStateService.ExecutionInput executionInput(UUID runId) {
		AiAnalysisRun run = new AiAnalysisRun(); run.setId(runId); run.setStatus(AiAnalysisStatus.RUNNING);
		AiAnalysisProviderDecision decision = new AiAnalysisProviderDecision(); decision.setProviderRole(AiProviderRole.PRIMARY); decision.setProviderKey("fake"); decision.setProviderConfigHash(FakeAiModelProvider.CONFIG_HASH);
		AiAnalysisEvidence item = new AiAnalysisEvidence(); item.setId(UUID.randomUUID()); item.setEvidenceType(AiEvidenceType.COMMIT_MESSAGE); item.setSourceRef("commit"); item.setPayloadJson("{}");
		return new AiAnalysisStateService.ExecutionInput(run, List.of(item), decision);
	}

	private static boolean get(Future<Boolean> future) {
		try { return future.get(5, TimeUnit.SECONDS); }
		catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
		catch (ExecutionException | TimeoutException ex) { throw new AssertionError(ex); }
	}
}
