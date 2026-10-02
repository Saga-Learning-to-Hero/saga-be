package com.saga.be.service.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.assistant.AssistantDtos.AskResponse;
import com.saga.be.dto.assistant.AssistantDtos.Citation;
import com.saga.be.dto.assistant.AssistantDtos.MessageResponse;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.assistant.AssistantConversation;
import com.saga.be.entity.assistant.AssistantMessage;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.AssistantConversationRepository;
import com.saga.be.repository.AssistantMessageRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.ai.AiAssistantClient;
import com.saga.be.service.assistant.AssistantFacts.Fact;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssistantServiceTest {

	/** 03/10/2026 10:00 in Vietnam; the server clock runs in UTC. */
	private static final Instant NOW = Instant.parse("2026-10-03T03:00:00Z");
	private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

	@Mock private ProjectDataAuthorization authorization;
	@Mock private AssistantConversationRepository conversations;
	@Mock private AssistantMessageRepository messages;
	@Mock private ProjectRepository projects;
	@Mock private UserAccountRepository users;
	@Mock private AssistantFactsBuilder factsBuilder;
	@Mock private AiAssistantClient ai;

	private final UUID userId = UUID.randomUUID();
	private final UUID projectId = UUID.randomUUID();
	private final UUID courseId = UUID.randomUUID();
	private final UUID sprintId = UUID.randomUUID();
	private final UUID emptySprintId = UUID.randomUUID();
	private final UUID overdueTaskId = UUID.randomUUID();
	private final UUID namedTaskId = UUID.randomUUID();
	private final UUID memberId = UUID.randomUUID();
	private final UUID commitId = UUID.randomUUID();
	private AssistantConversation conversation;
	private List<AssistantMessage> stored;
	private AssistantFacts facts;

	@BeforeEach
	void setUp() {
		Course course = new Course();
		course.setId(courseId);
		Project project = new Project();
		project.setId(projectId);
		project.setName("Smart Library");
		project.setCourse(course);
		UserAccount user = new UserAccount();
		user.setId(userId);
		conversation = new AssistantConversation();
		conversation.setId(UUID.randomUUID());
		conversation.setProject(project);
		conversation.setUserAccount(user);
		conversation.setCreatedAt(NOW_UTC.minusHours(1));
		stored = new ArrayList<>();
		when(conversations.findOwned(conversation.getId(), projectId, userId)).thenReturn(Optional.of(conversation));
		when(conversations.findById(conversation.getId())).thenReturn(Optional.of(conversation));
		when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(messages.save(any())).thenAnswer(inv -> {
			AssistantMessage message = inv.getArgument(0);
			if (message.getId() == null) {
				message.setId(UUID.randomUUID());
				message.setCreatedAt(NOW_UTC);
				stored.add(message);
			}
			return message;
		});
		when(messages.findByConversation(conversation.getId())).thenAnswer(inv -> List.copyOf(stored));
		when(projects.findById(projectId)).thenReturn(Optional.of(project));
		facts = facts();
		when(factsBuilder.build(eq(userId), eq(projectId), anyString())).thenReturn(facts);
	}

	private AssistantService service(boolean enabled, int dailyLimit) {
		return new AssistantService(authorization, conversations, messages, projects, users, factsBuilder, ai,
				new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(NOW, ZoneOffset.UTC),
				ZoneId.of("Asia/Ho_Chi_Minh"), enabled, dailyLimit, 6);
	}

	private AssistantService service() {
		return service(true, 50);
	}

	// ------------------------------------------------------------------ AI answers

	@Test
	void anAiAnswerKeepsOnlyCitationsOfSuppliedFactsAndIsUnverifiedWhenOneWasDropped() {
		UUID invented = UUID.randomUUID();
		when(ai.ask(anyString(), eq(courseId), any(), any())).thenReturn(answer(
				"SAGA-1 đang trễ hạn.",
				List.of(new AiAssistantClient.Citation("TASK", overdueTaskId),
						new AiAssistantClient.Citation("TASK", overdueTaskId),
						new AiAssistantClient.Citation("TASK", invented),
						// a real id under the wrong kind is not a match either
						new AiAssistantClient.Citation("MEMBER", overdueTaskId)),
				false, false));

		AskResponse response = service().ask(userId, projectId, conversation.getId(), "  Task nào đang trễ?  ");

		MessageResponse answer = response.answer();
		assertThat(response.question().content()).isEqualTo("Task nào đang trễ?");
		assertThat(response.question().role()).isEqualTo("USER");
		assertThat(answer.role()).isEqualTo("ASSISTANT");
		assertThat(answer.answerSource()).isEqualTo("AI");
		assertThat(answer.citations()).containsExactly(new Citation("TASK", overdueTaskId, "SAGA-1 · Login API", overdueTaskId, null));
		assertThat(answer.removedCitationCount()).isEqualTo(2);
		assertThat(answer.verified()).isFalse();
		assertThat(answer.followUpQuestions()).containsExactly("Ai đang làm SAGA-1?");
		// shown in Vietnam time with its offset although the server clock is UTC
		assertThat(answer.createdAt()).isEqualTo(OffsetDateTime.parse("2026-10-03T10:00:00+07:00"));
		assertThat(conversation.getTitle()).isEqualTo("Task nào đang trễ?");
		AssistantMessage saved = stored.get(1);
		assertThat(saved.getCredentialSource()).isEqualTo("COURSE");
		assertThat(saved.getModelId()).isEqualTo("gpt-test");
	}

	@Test
	void anAnswerCitingOnlySuppliedFactsIsVerified() {
		when(ai.ask(anyString(), any(), any(), any())).thenReturn(answer("Minh có 2 task trễ.",
				List.of(new AiAssistantClient.Citation("MEMBER", memberId), new AiAssistantClient.Citation("COMMIT", commitId)), false, false));

		MessageResponse answer = service().ask(userId, projectId, conversation.getId(), "Minh làm gì?").answer();

		assertThat(answer.verified()).isTrue();
		assertThat(answer.removedCitationCount()).isZero();
		assertThat(answer.citations()).extracting(Citation::kind).containsExactly("MEMBER", "COMMIT");
		assertThat(answer.citations().get(1).sha()).isEqualTo("abcdef1234567");
	}

	@Test
	void aClaimWithoutAnyCitationIsUnverifiedButADeclineOrNoDataIsNot() {
		when(ai.ask(anyString(), any(), any(), any()))
				.thenReturn(answer("Nhóm ổn.", List.of(), false, false))
				.thenReturn(answer("Câu hỏi nằm ngoài phạm vi dự án.", List.of(), false, true))
				.thenReturn(answer("Không có dữ liệu về việc này.", List.of(), true, false));

		assertThat(service().ask(userId, projectId, conversation.getId(), "Nhóm sao rồi?").answer().verified()).isFalse();
		MessageResponse declined = service().ask(userId, projectId, conversation.getId(), "Viết code giúp tui").answer();
		assertThat(declined.verified()).isTrue();
		assertThat(declined.outOfScope()).isTrue();
		MessageResponse noData = service().ask(userId, projectId, conversation.getId(), "Ai thích cà phê?").answer();
		assertThat(noData.verified()).isTrue();
		assertThat(noData.insufficientData()).isTrue();
	}

	@Test
	void theAiGetsEveryFactTheViewerAndTheRecentTurns() {
		when(ai.ask(anyString(), any(), any(), any())).thenReturn(answer("Đầu tiên.", List.of(), true, false));
		service().ask(userId, projectId, conversation.getId(), "Câu một");
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<AiAssistantClient.Evidence>> evidence = ArgumentCaptor.forClass(List.class);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);

		service().ask(userId, projectId, conversation.getId(), "Câu hai");

		verify(ai, org.mockito.Mockito.times(2)).ask(anyString(), eq(courseId), evidence.capture(), context.capture());
		assertThat(evidence.getValue()).hasSize(facts.items().size());
		assertThat(evidence.getValue()).extracting(AiAssistantClient.Evidence::type).contains("PROJECT", "SPRINT", "TASK", "MEMBER", "COMMIT");
		assertThat(evidence.getValue().getFirst().sourceRef()).isEqualTo("project:" + projectId);
		Map<String, Object> last = context.getValue();
		assertThat(last.get("question")).isEqualTo("Câu hai");
		assertThat(last.get("today")).isEqualTo("2026-10-03");
		@SuppressWarnings("unchecked")
		Map<String, Object> viewer = (Map<String, Object>) last.get("viewer");
		assertThat(viewer).containsEntry("role", "STUDENT").containsEntry("teamRole", "MEMBER");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> history = (List<Map<String, Object>>) last.get("history");
		assertThat(history).extracting(turn -> turn.get("role")).containsExactly("user", "assistant");
		assertThat(history).extracting(turn -> turn.get("content")).containsExactly("Câu một", "Đầu tiên.");
	}

	// ------------------------------------------------------------------ fallback

	@Test
	void whenTheAiIsUnavailableTheBackendSummarisesTheDataWithCitations() {
		when(ai.ask(anyString(), any(), any(), any())).thenThrow(new AiAssistantClient.Unavailable("AI_CREDENTIAL_UNAVAILABLE"));

		MessageResponse answer = service().ask(userId, projectId, conversation.getId(), "SAGA-7 tới đâu rồi? Minh sao?").answer();

		assertThat(answer.answerSource()).isEqualTo("FALLBACK");
		assertThat(answer.fallbackReason()).isEqualTo("AI_CREDENTIAL_UNAVAILABLE");
		assertThat(answer.verified()).isTrue();
		assertThat(answer.content())
				.contains("lớp chưa có key AI và chưa được phép dùng key hệ thống")
				.contains("Dự án có 3 task, 1 task trễ hạn")
				.contains("Sprint 4: 1/2 task đã hoàn thành")
				.contains("SAGA-7 · Search: đang làm, người làm Trần Minh, hạn 06/10/2026")
				.contains("Trần Minh: 2 task chưa xong, 1 task trễ hạn, 5 commit")
				.contains("\n- Task trễ hạn:\n  - SAGA-1 · Login API: bị chặn, người làm chưa giao, hạn 01/10/2026.")
				// an active sprint without tasks is left out of the summary
				.doesNotContain("Sprint trống");
		assertThat(answer.citations()).extracting(Citation::kind).containsExactly("PROJECT", "SPRINT", "TASK", "MEMBER", "TASK");
	}

	@Test
	void fallbackReasonsAreReadable() {
		assertThat(AssistantService.reasonText("AI_RUNTIME_NOT_CONFIGURED")).isEqualTo("hệ thống chưa bật AI");
		assertThat(AssistantService.reasonText("AI_PROVIDER_QUOTA_EXHAUSTED")).contains("hết hạn mức");
		assertThat(AssistantService.reasonText("AI_PROVIDER_TIMEOUT")).isEqualTo("AI phản hồi quá lâu");
		assertThat(AssistantService.reasonText("AI_RUNTIME_OUTDATED")).contains("chưa được cập nhật");
		assertThat(AssistantService.reasonText("AI_PROVIDER_MODEL_NOT_FOUND")).contains("không còn được hỗ trợ");
		assertThat(AssistantService.reasonText("AI_PROVIDER_UNAVAILABLE")).contains("tạm ngừng");
		assertThat(AssistantService.reasonText("SOMETHING_NEW")).isEqualTo("AI đang gặp lỗi");
		assertThat(AssistantService.reasonText(null)).isEqualTo("AI đang gặp lỗi");
	}

	// ------------------------------------------------------------------ guards

	@Test
	void anEmptyOrTooLongQuestionIsRejectedBeforeAnythingElse() {
		expectCode(() -> service().ask(userId, projectId, conversation.getId(), "   "), IntegrationErrorCode.ASSISTANT_INPUT_INVALID);
		expectCode(() -> service().ask(userId, projectId, conversation.getId(), null), IntegrationErrorCode.ASSISTANT_INPUT_INVALID);
		expectCode(() -> service().ask(userId, projectId, conversation.getId(), "x".repeat(1001)), IntegrationErrorCode.ASSISTANT_INPUT_INVALID);
		verify(messages, never()).save(any());
		verify(ai, never()).ask(anyString(), any(), any(), any());
	}

	@Test
	void theDailyLimitStopsTheQuestionBeforeItIsStoredOrSentToTheAi() {
		when(messages.countQuestionsSince(userId, NOW_UTC.minusHours(24))).thenReturn(3L);

		expectCode(() -> service(true, 3).ask(userId, projectId, conversation.getId(), "Còn hỏi được không?"),
				IntegrationErrorCode.ASSISTANT_RATE_LIMITED);

		verify(messages, never()).save(any());
		verify(factsBuilder, never()).build(any(), any(), any());
		verify(ai, never()).ask(anyString(), any(), any(), any());
	}

	@Test
	void someoneElsesConversationIsNotFound() {
		UUID other = UUID.randomUUID();
		when(conversations.findOwned(other, projectId, userId)).thenReturn(Optional.empty());

		expectCode(() -> service().ask(userId, projectId, other, "Xin chào"), IntegrationErrorCode.ASSISTANT_CONVERSATION_NOT_FOUND);
		expectCode(() -> service().messages(userId, projectId, other), IntegrationErrorCode.ASSISTANT_CONVERSATION_NOT_FOUND);
		verify(ai, never()).ask(anyString(), any(), any(), any());
	}

	@Test
	void aReaderCheckFailureStopsEverything() {
		doThrow(new IntegrationException(IntegrationErrorCode.INTEGRATION_FORBIDDEN, org.springframework.http.HttpStatus.FORBIDDEN, "no"))
				.when(authorization).requireReader(userId, projectId);

		expectCode(() -> service().ask(userId, projectId, conversation.getId(), "Xin chào"), IntegrationErrorCode.INTEGRATION_FORBIDDEN);
		expectCode(() -> service().startConversation(userId, projectId), IntegrationErrorCode.INTEGRATION_FORBIDDEN);
		expectCode(() -> service().status(userId, projectId), IntegrationErrorCode.INTEGRATION_FORBIDDEN);
		verify(messages, never()).save(any());
		verify(conversations, never()).save(any());
	}

	@Test
	void aSwitchedOffAssistantRefusesNewWork() {
		expectCode(() -> service(false, 50).ask(userId, projectId, conversation.getId(), "Xin chào"), IntegrationErrorCode.ASSISTANT_DISABLED);
		expectCode(() -> service(false, 50).startConversation(userId, projectId), IntegrationErrorCode.ASSISTANT_DISABLED);
	}

	@Test
	void feedbackOnlyOnTheCallersOwnAnswer() {
		AssistantMessage answer = new AssistantMessage();
		answer.setId(UUID.randomUUID());
		answer.setConversation(conversation);
		answer.setRole(AssistantMessage.Role.ASSISTANT);
		answer.setContent("Trả lời");
		answer.setCreatedAt(NOW_UTC);
		when(messages.findOwnedAnswer(answer.getId(), projectId, userId)).thenReturn(Optional.of(answer));
		UUID foreign = UUID.randomUUID();
		when(messages.findOwnedAnswer(foreign, projectId, userId)).thenReturn(Optional.empty());

		MessageResponse rated = service().feedback(userId, projectId, answer.getId(), false, "  Sai số task  ");

		assertThat(rated.feedback().helpful()).isFalse();
		assertThat(rated.feedback().comment()).isEqualTo("Sai số task");
		assertThat(answer.getFeedbackAt()).isEqualTo(NOW_UTC);
		expectCode(() -> service().feedback(userId, projectId, foreign, true, null), IntegrationErrorCode.ASSISTANT_MESSAGE_NOT_FOUND);
	}

	@Test
	void statusReportsTheKeySourceAndTheRemainingQuestions() {
		when(ai.runtimeConfigured()).thenReturn(true);
		when(ai.keySource(courseId)).thenReturn("PLATFORM");
		when(messages.countQuestionsSince(userId, NOW_UTC.minusHours(24))).thenReturn(12L);

		var status = service(true, 50).status(userId, projectId);

		assertThat(status.enabled()).isTrue();
		assertThat(status.aiConfigured()).isTrue();
		assertThat(status.keySource()).isEqualTo("PLATFORM");
		assertThat(status.usedToday()).isEqualTo(12);
		assertThat(status.remainingToday()).isEqualTo(38);
	}

	// ------------------------------------------------------------------ fixtures

	private static AiAssistantClient.Answer answer(String text, List<AiAssistantClient.Citation> citations, boolean noData, boolean outOfScope) {
		return new AiAssistantClient.Answer(text, citations, noData, outOfScope, List.of("Ai đang làm SAGA-1?"), "COURSE", "saga-ai", "gpt-test", 1200L);
	}

	private AssistantFacts facts() {
		List<Fact> items = new ArrayList<>();
		items.add(new Fact("PROJECT", projectId, "Smart Library", null, null,
				map("taskCount", 3, "overdueTaskCount", 1L, "dueSoonTaskCount", 1L)));
		items.add(new Fact("SPRINT", sprintId, "Sprint 4", null, null,
				map("taskCount", 2, "taskStatusCounts", map("DONE", 1L, "IN_PROGRESS", 1L))));
		items.add(new Fact("SPRINT", emptySprintId, "Sprint trống", null, null,
				map("taskCount", 0, "taskStatusCounts", map())));
		items.add(new Fact("TASK", overdueTaskId, "SAGA-1 · Login API", overdueTaskId, null,
				map("status", "BLOCKED", "assignee", null, "dueDate", "2026-10-01")));
		items.add(new Fact("TASK", namedTaskId, "SAGA-7 · Search", namedTaskId, null,
				map("status", "IN_PROGRESS", "assignee", "Trần Minh", "dueDate", "2026-10-06")));
		items.add(new Fact("MEMBER", memberId, "Trần Minh", null, null,
				map("assignedTaskStatusCounts", map("TODO", 1L, "IN_PROGRESS", 1L, "DONE", 3L), "overdueTaskCount", 1L, "authoredCommitCount", 5L)));
		items.add(new Fact("COMMIT", commitId, "abcdef1 · feat: search", null, "abcdef1234567", map("sha", "abcdef1")));
		return new AssistantFacts(items,
				new AssistantFacts.Viewer(userId, "STUDENT", "MEMBER", "Nguyễn A", UUID.randomUUID()),
				LocalDate.of(2026, 10, 3),
				new AssistantFacts.Hints(projectId, List.of(sprintId, emptySprintId), List.of(overdueTaskId), List.of(namedTaskId), List.of(memberId)));
	}

	private static Map<String, Object> map(Object... pairs) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put((String) pairs[i], pairs[i + 1]);
		}
		return map;
	}

	private static void expectCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, IntegrationErrorCode code) {
		assertThatThrownBy(call).isInstanceOf(IntegrationException.class)
				.satisfies(ex -> assertThat(((IntegrationException) ex).getCode()).isEqualTo(code));
	}
}
