package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.ProjectAccessAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectAccessAssignmentRepository extends JpaRepository<ProjectAccessAssignment, Long> {

    @Query("""
            select assignment
            from ProjectAccessAssignment assignment
            where upper(coalesce(assignment.provider, 'cxalloy')) = upper(:provider)
            order by assignment.projectId asc, assignment.personName asc
            """)
    List<ProjectAccessAssignment> findAllForProviderOrderByProjectIdAscPersonNameAsc(@Param("provider") String provider);

    @Query("""
            select assignment
            from ProjectAccessAssignment assignment
            where assignment.projectId = :projectId
              and upper(coalesce(assignment.provider, 'cxalloy')) = upper(:provider)
            order by assignment.personName asc
            """)
    List<ProjectAccessAssignment> findByProjectIdForProviderOrderByPersonNameAsc(@Param("projectId") String projectId,
                                                                                 @Param("provider") String provider);

    @Query("""
            select assignment
            from ProjectAccessAssignment assignment
            where upper(assignment.personEmail) = upper(:personEmail)
              and upper(coalesce(assignment.provider, 'cxalloy')) = upper(:provider)
            order by assignment.projectId asc
            """)
    List<ProjectAccessAssignment> findByPersonEmailForProviderOrderByProjectIdAsc(@Param("personEmail") String personEmail,
                                                                                   @Param("provider") String provider);

    boolean existsByPersonEmailIgnoreCase(String personEmail);

    @Query("""
            select case when count(assignment) > 0 then true else false end
            from ProjectAccessAssignment assignment
            where assignment.projectId = :projectId
              and upper(assignment.personEmail) = upper(:personEmail)
              and upper(coalesce(assignment.provider, 'cxalloy')) = upper(:provider)
            """)
    boolean existsByProjectIdAndPersonEmailForProvider(@Param("projectId") String projectId,
                                                       @Param("personEmail") String personEmail,
                                                       @Param("provider") String provider);

    @Query("""
            select assignment
            from ProjectAccessAssignment assignment
            where assignment.projectId = :projectId
              and upper(assignment.personEmail) = upper(:personEmail)
              and upper(coalesce(assignment.provider, 'cxalloy')) = upper(:provider)
            """)
    Optional<ProjectAccessAssignment> findByProjectIdAndPersonEmailForProvider(@Param("projectId") String projectId,
                                                                               @Param("personEmail") String personEmail,
                                                                               @Param("provider") String provider);
}
