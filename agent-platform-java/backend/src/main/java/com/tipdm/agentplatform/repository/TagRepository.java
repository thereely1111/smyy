package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TagRepository extends JpaRepository<Tag, UUID> {

    Optional<Tag> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<Tag> findByEnabledTrueOrderBySortAscNameAsc();

    List<Tag> findAllByOrderBySortAscNameAsc();

    List<Tag> findByNameInIgnoreCase(List<String> names);
}
