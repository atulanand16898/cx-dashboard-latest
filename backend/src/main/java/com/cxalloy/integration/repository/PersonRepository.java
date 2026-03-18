package com.cxalloy.integration.repository;
import com.cxalloy.integration.model.Person;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List; import java.util.Optional;
@Repository
public interface PersonRepository extends JpaRepository<Person, Long> {
    Optional<Person> findByExternalId(String externalId);
    List<Person> findAllByEmailIgnoreCase(String email);
    List<Person> findByProjectId(String projectId);
    boolean existsByExternalId(String externalId);
}
