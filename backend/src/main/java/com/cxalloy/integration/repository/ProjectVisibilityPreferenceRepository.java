package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.ProjectVisibilityPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectVisibilityPreferenceRepository extends JpaRepository<ProjectVisibilityPreference, Long> {

    @Query("""
            select preference
            from ProjectVisibilityPreference preference
            where upper(preference.username) = upper(:username)
              and upper(coalesce(preference.provider, 'cxalloy')) = upper(:provider)
            order by preference.projectId asc
            """)
    List<ProjectVisibilityPreference> findByUsernameForProviderOrderByProjectIdAsc(@Param("username") String username,
                                                                                   @Param("provider") String provider);

    boolean existsByUsernameIgnoreCase(String username);

    @Modifying
    @Query("""
            delete
            from ProjectVisibilityPreference preference
            where upper(preference.username) = upper(:username)
              and upper(coalesce(preference.provider, 'cxalloy')) = upper(:provider)
            """)
    void deleteByUsernameAndProvider(@Param("username") String username,
                                     @Param("provider") String provider);
}
