package com.saga.be.service.contribution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.saga.be.entity.enums.ContributionCriterion;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.service.contribution.SprintFirstContributionMixer.Member;
import com.saga.be.service.contribution.SprintFirstContributionMixer.MemberResult;
import com.saga.be.service.contribution.SprintFirstContributionMixer.OverrideFact;
import com.saga.be.service.contribution.SprintFirstContributionMixer.PeerFact;
import com.saga.be.service.contribution.SprintFirstContributionMixer.Result;
import com.saga.be.service.contribution.SprintFirstContributionMixer.SprintSlice;
import com.saga.be.service.contribution.SprintFirstContributionMixer.TaskFact;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SprintFirstContributionMixerTest {

	private static final UUID AN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
	private static final UUID BINH = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2");
	private static final UUID CHI = UUID.fromString("cccccccc-cccc-cccc-cccc-ccccccccccc3");
	private static final UUID DUNG = UUID.fromString("dddddddd-dddd-dddd-dddd-ddddddddddd4");
	private static final UUID S1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID S2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID S3 = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID S4 = UUID.fromString("44444444-4444-4444-4444-444444444444");
	private static final ContributionSliceWeights WEIGHTS =
			new ContributionSliceWeights(bd("0.40"), bd("0.10"), bd("0.15"), bd("0.35"));

	@Test
	void specFourSprintExampleMatchesLockedFormula() {
		List<TaskFact> tasks = new ArrayList<>();
		tasks.addAll(sprint1());
		tasks.addAll(sprint2());
		tasks.addAll(sprint3());
		tasks.addAll(sprint4());
		Result result = SprintFirstContributionMixer.mix(members(), tasks, peers(), WEIGHTS, List.of());

		MemberResult an = member(result, AN);
		MemberResult binh = member(result, BINH);
		MemberResult chi = member(result, CHI);
		MemberResult dung = member(result, DUNG);

		assertEquals(bd("8.00"), scale2(an.sliceScore()));
		assertEquals(bd("7.95"), scale2(binh.sliceScore()));
		assertEquals(bd("5.75"), scale2(chi.sliceScore()));
		assertEquals(bd("6.80"), scale2(dung.sliceScore()));

		assertEquals(bd("0.40"), scale2(an.peerReviewScore()));
		assertEquals(bd("0.30"), scale2(binh.peerReviewScore()));
		assertEquals(bd("0.20"), scale2(chi.peerReviewScore()));
		assertEquals(bd("0.10"), scale2(dung.peerReviewScore()));

		assertPercent(43.16, an.finalContributionPercentage());
		assertPercent(32.16, binh.finalContributionPercentage());
		assertPercent(15.51, chi.finalContributionPercentage());
		assertPercent(9.17, dung.finalContributionPercentage());

		assertPercent(52.53, sprint(an, S1).contributionPercentage());
		assertPercent(19.70, sprint(binh, S1).contributionPercentage());
		assertPercent(18.69, sprint(chi, S1).contributionPercentage());
		assertPercent(9.09, sprint(dung, S1).contributionPercentage());

		assertPercent(38.68, sprint(an, S2).contributionPercentage());
		assertPercent(45.80, sprint(binh, S2).contributionPercentage());
		assertPercent(8.14, sprint(chi, S2).contributionPercentage());
		assertPercent(7.38, sprint(dung, S2).contributionPercentage());
	}

	@Test
	void unlabeledAndMissingDocumentEvidenceAreZero() {
		List<TaskFact> tasks = List.of(
				task(AN, S1, ContributionCriterion.CODE, 3),
				task(AN, S1, null, 2),
				task(BINH, S1, null, 5));
		Result result = SprintFirstContributionMixer.mix(
				List.of(new Member(AN), new Member(BINH)), tasks, List.of(), WEIGHTS, List.of());
		assertEquals(bd("1.20"), scale2(member(result, AN).sliceScore()));
		assertEquals(bd("0.00"), scale2(member(result, BINH).sliceScore()));
		assertPercent(100, member(result, AN).finalContributionPercentage());
		assertPercent(0, member(result, BINH).finalContributionPercentage());
	}

	@Test
	void backlogTaskWithoutSprintIsIgnored() {
		List<TaskFact> tasks = List.of(new TaskFact(AN, null, null, TaskStatus.DONE, 8, ContributionCriterion.CODE));
		Result result = SprintFirstContributionMixer.mix(List.of(new Member(AN)), tasks, List.of(), WEIGHTS, List.of());
		assertEquals(bd("0.00"), scale2(member(result, AN).sliceScore()));
	}

	@Test
	void missingStoryPointCountsAsOne() {
		List<TaskFact> tasks = List.of(new TaskFact(AN, S1, "S1", TaskStatus.DONE, null, ContributionCriterion.CODE));
		Result result = SprintFirstContributionMixer.mix(List.of(new Member(AN)), tasks, List.of(), WEIGHTS, List.of());
		assertEquals(bd("0.40"), scale2(member(result, AN).sliceScore()));
	}

	@Test
	void overrideKeepsLockedValuesAndRenormalizesRemainder() {
		List<TaskFact> tasks = List.of(
				task(AN, S1, ContributionCriterion.CODE, 5),
				task(BINH, S1, ContributionCriterion.CODE, 5));
		Result result = SprintFirstContributionMixer.mix(
				List.of(new Member(AN), new Member(BINH), new Member(CHI)),
				tasks,
				List.of(),
				WEIGHTS,
				List.of(new OverrideFact(AN, bd("40"))));
		assertPercent(40, member(result, AN).finalContributionPercentage());
		assertPercent(60, member(result, BINH).finalContributionPercentage());
		assertPercent(0, member(result, CHI).finalContributionPercentage());
	}

	@Test
	void sliceBeforePeerIsIndependentOfStars() {
		List<TaskFact> tasks = List.of(
				task(AN, S1, ContributionCriterion.CODE, 3),
				task(BINH, S1, ContributionCriterion.CODE, 5));
		List<PeerFact> peers = List.of(new PeerFact(AN, S1, 4), new PeerFact(BINH, S1, 1));
		ContributionSliceWeights equal = ContributionSliceWeights.equalQuarters();
		Result result = SprintFirstContributionMixer.mix(
				List.of(new Member(AN), new Member(BINH)), tasks, peers, equal, List.of());
		assertPercent(37.5, member(result, AN).sliceContributionPercentage());
		assertPercent(62.5, member(result, BINH).sliceContributionPercentage());
		assertPercent(70.59, member(result, AN).finalContributionPercentage());
		assertPercent(29.41, member(result, BINH).finalContributionPercentage());
	}

	private static List<Member> members() {
		return List.of(new Member(AN), new Member(BINH), new Member(CHI), new Member(DUNG));
	}

	private static List<PeerFact> peers() {
		List<PeerFact> peers = new ArrayList<>();
		for (UUID sprint : List.of(S1, S2, S3, S4)) {
			peers.add(new PeerFact(AN, sprint, 4));
			peers.add(new PeerFact(BINH, sprint, 3));
			peers.add(new PeerFact(CHI, sprint, 2));
			peers.add(new PeerFact(DUNG, sprint, 1));
		}
		return peers;
	}

	private static List<TaskFact> sprint1() {
		return List.of(
				task(AN, S1, ContributionCriterion.CODE, 3),
				task(AN, S1, ContributionCriterion.CODE, 2),
				task(AN, S1, ContributionCriterion.TEST, 3),
				task(AN, S1, ContributionCriterion.DOCUMENT, 2),
				task(BINH, S1, ContributionCriterion.DOCUMENT, 2),
				task(BINH, S1, ContributionCriterion.DOCUMENT, 2),
				task(BINH, S1, ContributionCriterion.RESEARCH, 2),
				task(CHI, S1, ContributionCriterion.CODE, 2),
				task(CHI, S1, ContributionCriterion.RESEARCH, 3),
				task(DUNG, S1, ContributionCriterion.CODE, 3),
				task(DUNG, S1, ContributionCriterion.CODE, 1),
				task(DUNG, S1, ContributionCriterion.TEST, 2));
	}

	private static List<TaskFact> sprint2() {
		return List.of(
				task(AN, S2, ContributionCriterion.CODE, 3),
				task(AN, S2, ContributionCriterion.RESEARCH, 2),
				task(BINH, S2, ContributionCriterion.CODE, 4),
				task(BINH, S2, ContributionCriterion.CODE, 2),
				task(BINH, S2, ContributionCriterion.TEST, 3),
				task(BINH, S2, ContributionCriterion.DOCUMENT, 2),
				task(CHI, S2, ContributionCriterion.TEST, 2),
				task(CHI, S2, ContributionCriterion.DOCUMENT, 2),
				task(CHI, S2, ContributionCriterion.DOCUMENT, 2),
				task(DUNG, S2, ContributionCriterion.CODE, 1),
				task(DUNG, S2, ContributionCriterion.RESEARCH, 3));
	}

	private static List<TaskFact> sprint3() {
		return List.of(
				task(AN, S3, ContributionCriterion.CODE, 2),
				task(AN, S3, ContributionCriterion.TEST, 2),
				task(AN, S3, ContributionCriterion.TEST, 2),
				task(AN, S3, ContributionCriterion.DOCUMENT, 3),
				task(BINH, S3, ContributionCriterion.CODE, 4),
				task(BINH, S3, null, 5),
				task(CHI, S3, ContributionCriterion.CODE, 3),
				task(CHI, S3, ContributionCriterion.CODE, 2),
				task(CHI, S3, ContributionCriterion.TEST, 1),
				task(CHI, S3, ContributionCriterion.RESEARCH, 2),
				task(DUNG, S3, ContributionCriterion.TEST, 2),
				task(DUNG, S3, ContributionCriterion.DOCUMENT, 4));
	}

	private static List<TaskFact> sprint4() {
		return List.of(
				task(AN, S4, ContributionCriterion.CODE, 2),
				task(AN, S4, ContributionCriterion.RESEARCH, 3),
				task(AN, S4, null, 2),
				task(BINH, S4, ContributionCriterion.DOCUMENT, 2),
				task(BINH, S4, ContributionCriterion.RESEARCH, 5),
				task(CHI, S4, ContributionCriterion.TEST, 3),
				task(CHI, S4, null, 4),
				task(DUNG, S4, ContributionCriterion.CODE, 4),
				task(DUNG, S4, ContributionCriterion.CODE, 2),
				task(DUNG, S4, ContributionCriterion.TEST, 2),
				task(DUNG, S4, ContributionCriterion.DOCUMENT, 1));
	}

	private static TaskFact task(UUID student, UUID sprint, ContributionCriterion criterion, int sp) {
		return new TaskFact(student, sprint, sprint.toString(), TaskStatus.DONE, sp, criterion);
	}

	private static MemberResult member(Result result, UUID id) {
		return result.members().stream().filter(row -> row.studentProfileId().equals(id)).findFirst().orElseThrow();
	}

	private static SprintSlice sprint(MemberResult member, UUID sprintId) {
		return member.sprintBreakdowns().stream()
				.filter(row -> row.sprintId().equals(sprintId))
				.findFirst()
				.orElseThrow();
	}

	private static void assertPercent(double expected, BigDecimal actual) {
		assertEquals(expected, actual.setScale(2, RoundingMode.HALF_UP).doubleValue(), 0.01);
	}

	private static BigDecimal scale2(BigDecimal value) {
		return value.setScale(2, RoundingMode.HALF_UP);
	}

	private static BigDecimal bd(String value) {
		return new BigDecimal(value);
	}
}
