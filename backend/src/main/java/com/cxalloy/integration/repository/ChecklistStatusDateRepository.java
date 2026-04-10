package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.ChecklistStatusDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ChecklistStatusDateRepository extends JpaRepository<ChecklistStatusDate, Long> {

    @Query("""
            select row
            from ChecklistStatusDate row
            where row.projectId = :projectId
              and row.checklistExternalId = :checklistExternalId
              and upper(coalesce(row.provider, 'cxalloy')) = upper(:provider)
            """)
    Optional<ChecklistStatusDate> findByProjectIdAndChecklistExternalIdAndProvider(@Param("projectId") String projectId,
                                                                                   @Param("checklistExternalId") String checklistExternalId,
                                                                                   @Param("provider") String provider);

    @Query("""
            select row
            from ChecklistStatusDate row
            where row.projectId = :projectId
              and upper(coalesce(row.provider, 'cxalloy')) = upper(:provider)
            """)
    java.util.List<ChecklistStatusDate> findByProjectIdAndProvider(@Param("projectId") String projectId,
                                                                   @Param("provider") String provider);
}
