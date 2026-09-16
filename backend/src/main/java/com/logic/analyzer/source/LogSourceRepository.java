package com.logic.analyzer.source;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LogSourceRepository extends JpaRepository<LogSource, Long> {

    /** findFirst, not a plain derived findByName - name has no unique constraint, so this can't risk IncorrectResultSizeDataAccessException. */
    Optional<LogSource> findFirstByName(String name);
}
