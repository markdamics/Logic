package com.logic.analyzer.alert;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {
}
