package com.saga.be.service.contribution;

import com.saga.be.entity.enums.ContributionCriterion;
import com.saga.be.entity.enums.TaskStatus;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class SprintFirstContributionMixer {

	private static final MathContext MATH = ContributionSliceWeights.MATH;
	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private SprintFirstContributionMixer() {}

	public record Member(UUID studentProfileId) {}

	public record TaskFact(
			UUID assigneeStudentId,
			UUID sprintId,
			String sprintName,
			TaskStatus status,
			Integer storyPoint,
			ContributionCriterion criterion) {}

	public record PeerFact(UUID revieweeStudentId, UUID sprintId, int stars) {}

	public record OverrideFact(UUID studentProfileId, BigDecimal percentage) {}

	public record SprintSlice(
			UUID sprintId,
			String sprintName,
			BigDecimal sliceScore,
			BigDecimal sliceContributionPercentage,
			BigDecimal contributionPercentage,
			BigDecimal adjustedTaskScore) {}

	public record MemberResult(
			UUID studentProfileId,
			BigDecimal sliceScore,
			BigDecimal sliceContributionPercentage,
			BigDecimal finalContributionPercentage,
			BigDecimal peerReviewScore,
			BigDecimal codeContributionPercentage,
			BigDecimal testContributionPercentage,
			BigDecimal documentContributionPercentage,
			BigDecimal researchContributionPercentage,
			BigDecimal taskContributionPercentage,
			List<SprintSlice> sprintBreakdowns) {}

	public record Result(List<MemberResult> members) {}

	public static Result mix(
			List<Member> members,
			List<TaskFact> tasks,
			List<PeerFact> peers,
			ContributionSliceWeights weights,
			List<OverrideFact> overrides) {
		List<Member> roster = members == null ? List.of() : members;
		if (roster.isEmpty()) {
			return new Result(List.of());
		}
		ContributionSliceWeights sliceWeights = weights == null ? ContributionSliceWeights.equalQuarters() : weights;
		Set<UUID> memberIds = new LinkedHashSet<>();
		for (Member member : roster) {
			memberIds.add(member.studentProfileId());
		}

		Map<UUID, Map<UUID, Accumulator>> byStudentSprint = new LinkedHashMap<>();
		Map<UUID, String> sprintNames = new LinkedHashMap<>();
		Set<UUID> recognizedSprints = new LinkedHashSet<>();
		for (UUID studentId : memberIds) {
			byStudentSprint.put(studentId, new LinkedHashMap<>());
		}
		if (tasks != null) {
			for (TaskFact task : tasks) {
				if (task == null
						|| task.status() != TaskStatus.DONE
						|| task.sprintId() == null
						|| task.assigneeStudentId() == null
						|| !memberIds.contains(task.assigneeStudentId())) {
					continue;
				}
				sprintNames.putIfAbsent(task.sprintId(), task.sprintName());
				Accumulator acc = byStudentSprint
						.get(task.assigneeStudentId())
						.computeIfAbsent(task.sprintId(), ignored -> new Accumulator());
				BigDecimal weight = taskWeight(task.storyPoint());
				acc.taskScore = acc.taskScore.add(weight, MATH);
				if (task.criterion() != null) {
					recognizedSprints.add(task.sprintId());
					switch (task.criterion()) {
						case CODE -> acc.codeSp = acc.codeSp.add(weight, MATH);
						case TEST -> acc.testSp = acc.testSp.add(weight, MATH);
						case DOCUMENT -> acc.documentSp = acc.documentSp.add(weight, MATH);
						case RESEARCH -> acc.researchSp = acc.researchSp.add(weight, MATH);
					}
				}
			}
		}

		Map<UUID, Map<UUID, Integer>> starsBySprintStudent = new LinkedHashMap<>();
		Map<UUID, Integer> starsByStudent = new LinkedHashMap<>();
		int teamStars = 0;
		if (peers != null) {
			for (PeerFact peer : peers) {
				if (peer == null || peer.revieweeStudentId() == null || !memberIds.contains(peer.revieweeStudentId())) {
					continue;
				}
				int stars = Math.max(peer.stars(), 0);
				starsByStudent.merge(peer.revieweeStudentId(), stars, Integer::sum);
				teamStars += stars;
				if (peer.sprintId() != null) {
					starsBySprintStudent
							.computeIfAbsent(peer.sprintId(), ignored -> new LinkedHashMap<>())
							.merge(peer.revieweeStudentId(), stars, Integer::sum);
				}
			}
		}

		Map<UUID, BigDecimal> totalSlice = new LinkedHashMap<>();
		Map<UUID, BigDecimal> totalTaskScore = new LinkedHashMap<>();
		Map<UUID, BigDecimal> adjustedSprintScore = new LinkedHashMap<>();
		Map<UUID, BigDecimal> codeWeighted = new LinkedHashMap<>();
		Map<UUID, BigDecimal> testWeighted = new LinkedHashMap<>();
		Map<UUID, BigDecimal> documentWeighted = new LinkedHashMap<>();
		Map<UUID, BigDecimal> researchWeighted = new LinkedHashMap<>();
		Map<UUID, List<SprintSlice>> breakdowns = new LinkedHashMap<>();
		for (UUID studentId : memberIds) {
			totalSlice.put(studentId, BigDecimal.ZERO);
			totalTaskScore.put(studentId, BigDecimal.ZERO);
			adjustedSprintScore.put(studentId, BigDecimal.ZERO);
			codeWeighted.put(studentId, BigDecimal.ZERO);
			testWeighted.put(studentId, BigDecimal.ZERO);
			documentWeighted.put(studentId, BigDecimal.ZERO);
			researchWeighted.put(studentId, BigDecimal.ZERO);
			breakdowns.put(studentId, new ArrayList<>());
		}

		List<UUID> sprintOrder = new ArrayList<>(recognizedSprints);
		sprintOrder.sort(Comparator.comparing((UUID id) -> Objects.toString(sprintNames.get(id), ""))
				.thenComparing(UUID::toString));

		for (UUID sprintId : sprintOrder) {
			Map<UUID, BigDecimal> sliceByStudent = new LinkedHashMap<>();
			Map<UUID, BigDecimal> taskByStudent = new LinkedHashMap<>();
			BigDecimal sprintSliceTotal = BigDecimal.ZERO;
			for (UUID studentId : memberIds) {
				Accumulator acc = byStudentSprint.get(studentId).getOrDefault(sprintId, new Accumulator());
				BigDecimal slice = acc.codeSp
						.multiply(sliceWeights.code(), MATH)
						.add(acc.testSp.multiply(sliceWeights.test(), MATH), MATH)
						.add(acc.documentSp.multiply(sliceWeights.document(), MATH), MATH)
						.add(acc.researchSp.multiply(sliceWeights.research(), MATH), MATH);
				sliceByStudent.put(studentId, slice);
				taskByStudent.put(studentId, acc.taskScore);
				sprintSliceTotal = sprintSliceTotal.add(slice, MATH);
				totalSlice.put(studentId, totalSlice.get(studentId).add(slice, MATH));
				totalTaskScore.put(studentId, totalTaskScore.get(studentId).add(acc.taskScore, MATH));
				codeWeighted.put(studentId, codeWeighted.get(studentId).add(acc.codeSp.multiply(sliceWeights.code(), MATH), MATH));
				testWeighted.put(studentId, testWeighted.get(studentId).add(acc.testSp.multiply(sliceWeights.test(), MATH), MATH));
				documentWeighted.put(
						studentId, documentWeighted.get(studentId).add(acc.documentSp.multiply(sliceWeights.document(), MATH), MATH));
				researchWeighted.put(
						studentId, researchWeighted.get(studentId).add(acc.researchSp.multiply(sliceWeights.research(), MATH), MATH));
			}

			int sprintStarTotal = 0;
			Map<UUID, Integer> sprintStars = starsBySprintStudent.getOrDefault(sprintId, Map.of());
			for (Integer stars : sprintStars.values()) {
				sprintStarTotal += stars;
			}
			Map<UUID, BigDecimal> adjustByStudent = new LinkedHashMap<>();
			BigDecimal sprintAdjustTotal = BigDecimal.ZERO;
			for (UUID studentId : memberIds) {
				BigDecimal peer = peerShare(sprintStars.getOrDefault(studentId, 0), sprintStarTotal);
				BigDecimal adjust = sliceByStudent.get(studentId).multiply(peer, MATH);
				adjustByStudent.put(studentId, adjust);
				sprintAdjustTotal = sprintAdjustTotal.add(adjust, MATH);
				adjustedSprintScore.put(
						studentId,
						adjustedSprintScore.get(studentId).add(taskByStudent.get(studentId).multiply(peer, MATH), MATH));
			}
			for (UUID studentId : memberIds) {
				breakdowns
						.get(studentId)
						.add(new SprintSlice(
								sprintId,
								sprintNames.get(sprintId),
								sliceByStudent.get(studentId),
								ratioPercent(sliceByStudent.get(studentId), sprintSliceTotal),
								ratioPercent(adjustByStudent.get(studentId), sprintAdjustTotal),
								taskByStudent.get(studentId).multiply(peerShare(sprintStars.getOrDefault(studentId, 0), sprintStarTotal), MATH)));
			}
		}

		BigDecimal teamSliceTotal = BigDecimal.ZERO;
		BigDecimal teamTaskTotal = BigDecimal.ZERO;
		BigDecimal teamCode = BigDecimal.ZERO;
		BigDecimal teamTest = BigDecimal.ZERO;
		BigDecimal teamDocument = BigDecimal.ZERO;
		BigDecimal teamResearch = BigDecimal.ZERO;
		for (UUID studentId : memberIds) {
			teamSliceTotal = teamSliceTotal.add(totalSlice.get(studentId), MATH);
			teamTaskTotal = teamTaskTotal.add(adjustedSprintScore.get(studentId), MATH);
			teamCode = teamCode.add(codeWeighted.get(studentId), MATH);
			teamTest = teamTest.add(testWeighted.get(studentId), MATH);
			teamDocument = teamDocument.add(documentWeighted.get(studentId), MATH);
			teamResearch = teamResearch.add(researchWeighted.get(studentId), MATH);
		}

		Map<UUID, BigDecimal> peerByStudent = new LinkedHashMap<>();
		Map<UUID, BigDecimal> adjustFinal = new LinkedHashMap<>();
		BigDecimal teamAdjust = BigDecimal.ZERO;
		for (UUID studentId : memberIds) {
			BigDecimal peer = peerShare(starsByStudent.getOrDefault(studentId, 0), teamStars);
			peerByStudent.put(studentId, peer);
			BigDecimal adjust = totalSlice.get(studentId).multiply(peer, MATH);
			adjustFinal.put(studentId, adjust);
			teamAdjust = teamAdjust.add(adjust, MATH);
		}

		Map<UUID, BigDecimal> baseFinal = new LinkedHashMap<>();
		for (UUID studentId : memberIds) {
			baseFinal.put(studentId, ratioPercent(adjustFinal.get(studentId), teamAdjust));
		}
		Map<UUID, BigDecimal> overridden = applyOverrides(memberIds, baseFinal, overrides);

		List<MemberResult> results = new ArrayList<>();
		for (Member member : roster) {
			UUID studentId = member.studentProfileId();
			results.add(new MemberResult(
					studentId,
					totalSlice.get(studentId),
					ratioPercent(totalSlice.get(studentId), teamSliceTotal),
					overridden.get(studentId),
					peerByStudent.get(studentId),
					ratioPercent(codeWeighted.get(studentId), teamCode),
					ratioPercent(testWeighted.get(studentId), teamTest),
					ratioPercent(documentWeighted.get(studentId), teamDocument),
					ratioPercent(researchWeighted.get(studentId), teamResearch),
					ratioPercent(adjustedSprintScore.get(studentId), teamTaskTotal),
					List.copyOf(breakdowns.get(studentId))));
		}
		return new Result(List.copyOf(results));
	}

	static Map<UUID, BigDecimal> applyOverrides(
			Set<UUID> memberIds, Map<UUID, BigDecimal> baseFinal, List<OverrideFact> overrides) {
		Map<UUID, BigDecimal> latest = new LinkedHashMap<>();
		if (overrides != null) {
			for (OverrideFact override : overrides) {
				if (override == null
						|| override.studentProfileId() == null
						|| !memberIds.contains(override.studentProfileId())
						|| override.percentage() == null) {
					continue;
				}
				latest.put(override.studentProfileId(), override.percentage());
			}
		}
		if (latest.isEmpty()) {
			return baseFinal;
		}
		if (latest.keySet().containsAll(memberIds)) {
			BigDecimal total = BigDecimal.ZERO;
			for (BigDecimal value : latest.values()) {
				total = total.add(zero(value), MATH);
			}
			Map<UUID, BigDecimal> result = new LinkedHashMap<>();
			if (total.compareTo(BigDecimal.ZERO) <= 0) {
				BigDecimal equal = HUNDRED.divide(BigDecimal.valueOf(memberIds.size()), MATH);
				for (UUID studentId : memberIds) {
					result.put(studentId, equal);
				}
				return result;
			}
			for (UUID studentId : memberIds) {
				result.put(studentId, zero(latest.get(studentId)).divide(total, MATH).multiply(HUNDRED, MATH));
			}
			return result;
		}
		BigDecimal totalOverride = BigDecimal.ZERO;
		for (BigDecimal value : latest.values()) {
			totalOverride = totalOverride.add(zero(value), MATH);
		}
		Map<UUID, BigDecimal> result = new LinkedHashMap<>();
		BigDecimal remainingBudget;
		if (totalOverride.compareTo(HUNDRED) > 0) {
			for (UUID studentId : memberIds) {
				if (latest.containsKey(studentId)) {
					result.put(studentId, zero(latest.get(studentId)).divide(totalOverride, MATH).multiply(HUNDRED, MATH));
				}
			}
			remainingBudget = BigDecimal.ZERO;
		} else {
			for (UUID studentId : memberIds) {
				if (latest.containsKey(studentId)) {
					result.put(studentId, zero(latest.get(studentId)));
				}
			}
			remainingBudget = HUNDRED.subtract(totalOverride, MATH);
		}
		BigDecimal totalBase = BigDecimal.ZERO;
		for (UUID studentId : memberIds) {
			if (!latest.containsKey(studentId)) {
				totalBase = totalBase.add(zero(baseFinal.get(studentId)), MATH);
			}
		}
		for (UUID studentId : memberIds) {
			if (latest.containsKey(studentId)) {
				continue;
			}
			if (totalBase.compareTo(BigDecimal.ZERO) == 0) {
				result.put(studentId, BigDecimal.ZERO);
			} else {
				result.put(
						studentId,
						zero(baseFinal.get(studentId)).divide(totalBase, MATH).multiply(remainingBudget, MATH));
			}
		}
		return result;
	}

	private static BigDecimal peerShare(int stars, int total) {
		if (total <= 0) {
			return BigDecimal.ONE;
		}
		return BigDecimal.valueOf(stars).divide(BigDecimal.valueOf(total), MATH);
	}

	private static BigDecimal ratioPercent(BigDecimal part, BigDecimal total) {
		if (total == null || total.compareTo(BigDecimal.ZERO) == 0) {
			return BigDecimal.ZERO;
		}
		return zero(part).divide(total, MATH).multiply(HUNDRED, MATH);
	}

	private static BigDecimal taskWeight(Integer storyPoint) {
		return storyPoint == null ? BigDecimal.ONE : BigDecimal.valueOf(storyPoint.longValue());
	}

	private static BigDecimal zero(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	private static final class Accumulator {
		private BigDecimal codeSp = BigDecimal.ZERO;
		private BigDecimal testSp = BigDecimal.ZERO;
		private BigDecimal documentSp = BigDecimal.ZERO;
		private BigDecimal researchSp = BigDecimal.ZERO;
		private BigDecimal taskScore = BigDecimal.ZERO;
	}
}
