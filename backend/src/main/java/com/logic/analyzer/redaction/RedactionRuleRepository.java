package com.logic.analyzer.redaction;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RedactionRuleRepository extends JpaRepository<RedactionRule, Long> {
}
