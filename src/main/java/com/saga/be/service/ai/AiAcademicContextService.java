package com.saga.be.service.ai;
import com.saga.be.entity.academic.*; import com.saga.be.entity.project.Project; import com.saga.be.exception.IntegrationException; import com.saga.be.integration.IntegrationErrorCode; import com.saga.be.repository.*; import java.util.*; import org.springframework.context.annotation.Profile; import org.springframework.http.HttpStatus; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
/** Resolves only the Course-pinned syllabus version; it never substitutes a latest version. */
@Service @Profile("!test") public class AiAcademicContextService {
 private final ProjectRepository projects; private final SyllabusPhaseRepository phases; private final SyllabusExpectedDeliverableRepository deliverables;
 public AiAcademicContextService(ProjectRepository projects,SyllabusPhaseRepository phases,SyllabusExpectedDeliverableRepository deliverables){this.projects=projects;this.phases=phases;this.deliverables=deliverables;}
 @Transactional(readOnly=true) public Context resolve(UUID projectId){ Project p=projects.findWithPinnedSyllabus(projectId).orElseThrow(()->missing()); SubjectSyllabusVersion v=p.getCourse().getSyllabusVersion(); if(v==null) throw missing(); return new Context(p,v,phases.findBySyllabusVersion_IdOrderByOrderIndexAsc(v.getId()),deliverables.findBySyllabusVersion_IdOrderByOrderIndexAsc(v.getId())); }
 private static IntegrationException missing(){return new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_TARGET_NOT_FOUND,HttpStatus.UNPROCESSABLE_ENTITY,"Project has no pinned syllabus version.");}
 public record Context(Project project,SubjectSyllabusVersion syllabusVersion,List<SyllabusPhase> phases,List<SyllabusExpectedDeliverable> deliverables){}
}
