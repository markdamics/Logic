package com.logic.analyzer.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {

    List<AlertEvent> findTop50ByAlertRuleIdOrderByTriggeredAtDesc(Long alertRuleId);

    Optional<AlertEvent> findFirstByAlertRuleIdAndResolvedAtIsNullOrderByTriggeredAtDesc(Long alertRuleId);
}
