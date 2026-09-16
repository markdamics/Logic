package com.logic.analyzer.alert;

import com.logic.analyzer.logstream.LogQueryParams;
import com.logic.analyzer.logstream.LogQueryService;
import com.logic.analyzer.logstream.dto.LogAggregationResult;
import com.logic.analyzer.search.SearchQueryService;
import com.logic.analyzer.search.query.AggregationStage;
import com.logic.analyzer.search.query.LuceneQueryExecutor;
import com.logic.analyzer.search.query.QueryLanguage;
import com.logic.analyzer.template.LogTemplate;
import com.logic.analyzer.template.LogTemplateRepository;
import org.apache.lucene.search.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodically evaluates every {@link AlertRule} against the live search
 * index, reusing the same {@link LuceneQueryExecutor}/{@link AggregationStage.CountOverTimeStage}
 * bucketing Search & Query already built for count_over_time/rate - one
 * aggregation engine, not a second one for alerting.
 *
 * THRESHOLD rules compare the current window's count/rate against a fixed
 * number. ANOMALY rules compare it against a statistical baseline - the
 * mean plus k standard deviations of the preceding windows - which is a
 * simple, real technique but not a trained ML model (see README Known
 * simplifications).
 */
@Service
public class AlertEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluationService.class);

    private final AlertRuleRepository ruleRepository;
    private final AlertEventRepository eventRepository;
    private final LogQueryService logQueryService;
    private final SearchQueryService searchQueryService;
    private final LuceneQueryExecutor executor;
    private final WebhookNotifier webhookNotifier;
    private final LogTemplateRepository templateRepository;

    /** Was this rule triggered as of the last evaluation? In-memory only - losing it on restart costs at most one duplicate AlertEvent, not a correctness issue. */
    private final Map<Long, Boolean> triggeredState = new ConcurrentHashMap<>();

    public AlertEvaluationService(AlertRuleRepository ruleRepository, AlertEventRepository eventRepository,
                                   LogQueryService logQueryService, SearchQueryService searchQueryService,
                                   LuceneQueryExecutor executor, WebhookNotifier webhookNotifier,
                                   LogTemplateRepository templateRepository) {
        this.ruleRepository = ruleRepository;
        this.eventRepository = eventRepository;
        this.logQueryService = logQueryService;
        this.searchQueryService = searchQueryService;
        this.executor = executor;
        this.webhookNotifier = webhookNotifier;
        this.templateRepository = templateRepository;
    }

    @Scheduled(fixedDelayString = "${app.alerts.evaluation-interval-ms:30000}")
    public void evaluateAll() {
        for (AlertRule rule : ruleRepository.findAll()) {
            try {
                evaluate(rule);
            } catch (Exception e) {
                log.warn("Failed to evaluate alert rule {} ('{}'): {}", rule.getId(), rule.getName(), e.getMessage());
            }
        }
    }

    private void evaluate(AlertRule rule) {
        if (rule.getRuleType() == AlertRuleType.NEW_PATTERN) {
            evaluateNewPattern(rule);
            return;
        }

        int totalWindows = rule.getRuleType() == AlertRuleType.ANOMALY ? rule.getAnomalyBaselineWindows() + 1 : 1;
        long rangeMinutes = (long) rule.getWindowMinutes() * totalWindows;

        Query query = compileScope(rule, rangeMinutes);
        LogAggregationResult aggregation = executor.executeAggregation(query,
                new AggregationStage.CountOverTimeStage(Duration.ofMinutes(rule.getWindowMinutes())), rangeMinutes)
                .aggregation();
        List<LogAggregationResult.Bucket> buckets = aggregation.buckets();

        rule.setLastEvaluatedAt(Instant.now());
        if (buckets.isEmpty()) {
            ruleRepository.save(rule);
            return;
        }

        LogAggregationResult.Bucket current = buckets.get(buckets.size() - 1);
        double currentValue = metricValue(rule, current);
        boolean triggered = rule.getRuleType() == AlertRuleType.THRESHOLD
                ? compare(currentValue, rule.getComparisonOp(), rule.getThreshold())
                : isAnomaly(rule, buckets, currentValue);

        boolean wasTriggered = triggeredState.getOrDefault(rule.getId(), false);
        if (triggered && !wasTriggered) {
            fire(rule, currentValue);
        } else if (!triggered && wasTriggered) {
            resolve(rule);
        }
        triggeredState.put(rule.getId(), triggered);

        ruleRepository.save(rule);
    }

    /**
     * NEW_PATTERN has no count/rate to bucket - it fires once per {@link LogTemplate} whose
     * {@link LogTemplate#getFirstSeenAt()} falls in this rule's lookback window, deduped against
     * {@link AlertEventRepository#existsByAlertRuleIdAndTemplateId} so a template already alerted
     * on doesn't fire again on the next (overlapping) evaluation pass. There's no "resolve" side
     * to this - a newly appeared template isn't an ongoing condition that can clear.
     */
    private void evaluateNewPattern(AlertRule rule) {
        rule.setLastEvaluatedAt(Instant.now());

        // Never earlier than the rule's own creation - a template that predates the rule must
        // never retroactively fire, no matter how wide the window is.
        Instant cutoff = laterOf(Instant.now().minus(Duration.ofMinutes(rule.getWindowMinutes())), rule.getCreatedAt());
        boolean fileScoped = rule.getFile() != null && !rule.getFile().isBlank();
        List<LogTemplate> candidates = fileScoped
                ? templateRepository.findBySourceAndFileAndFirstSeenAtAfter(rule.getSource(), rule.getFile(), cutoff)
                : templateRepository.findBySourceAndFirstSeenAtAfter(rule.getSource(), cutoff);

        for (LogTemplate template : candidates) {
            if (!eventRepository.existsByAlertRuleIdAndTemplateId(rule.getId(), template.getId())) {
                fireNewPattern(rule, template);
            }
        }

        ruleRepository.save(rule);
    }

    private static Instant laterOf(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private void fireNewPattern(AlertRule rule, LogTemplate template) {
        Instant now = Instant.now();
        rule.setLastTriggeredAt(now);
        AlertEvent event = eventRepository.save(new AlertEvent(
                rule.getId(), now, template.getOccurrenceCount(), template.getId(), template.getTemplateText(), template.getSampleRawLine()));
        log.info("Alert rule {} ('{}') triggered: new pattern '{}'", rule.getId(), rule.getName(), template.getTemplateText());
        if (!rule.isMuted()) {
            webhookNotifier.notifyNewPatternAsync(rule, now, template, status -> {
                event.setWebhookStatus(status);
                eventRepository.save(event);
            });
        }
    }

    /** True when the current bucket exceeds mean + k*stddev of the preceding baseline buckets. */
    private boolean isAnomaly(AlertRule rule, List<LogAggregationResult.Bucket> buckets, double currentValue) {
        List<LogAggregationResult.Bucket> baseline = buckets.subList(0, buckets.size() - 1);
        if (baseline.isEmpty()) {
            return false;
        }
        double[] values = baseline.stream().mapToDouble(b -> metricValue(rule, b)).toArray();
        double mean = Arrays.stream(values).average().orElse(0);
        double variance = Arrays.stream(values).map(v -> (v - mean) * (v - mean)).average().orElse(0);
        double stddev = Math.sqrt(variance);
        return currentValue > mean + rule.getAnomalyStdDevMultiplier() * stddev;
    }

    private double metricValue(AlertRule rule, LogAggregationResult.Bucket bucket) {
        return rule.getMetric() == AlertMetric.RATE && bucket.rate() != null ? bucket.rate() : bucket.count();
    }

    private boolean compare(double value, ComparisonOperator op, double threshold) {
        return op == ComparisonOperator.GTE ? value >= threshold : value > threshold;
    }

    private Query compileScope(AlertRule rule, long rangeMinutes) {
        if (rule.getQueryLanguage() == QueryLanguage.SIMPLE) {
            return logQueryService.compile(new LogQueryParams(
                    rule.getSearch(), rule.getLevels(), rule.getSource(), rule.getFile(), null, rangeMinutes,
                    "time", "desc", 0, 0));
        }
        return searchQueryService.compile(rule.getQuery(), rule.getQueryLanguage(), rule.getSource(), rule.getFile(), rangeMinutes);
    }

    private void fire(AlertRule rule, double currentValue) {
        Instant now = Instant.now();
        rule.setLastTriggeredAt(now);
        AlertEvent event = eventRepository.save(new AlertEvent(rule.getId(), now, currentValue, rule.getThreshold()));
        log.info("Alert rule {} ('{}') triggered: {} = {}", rule.getId(), rule.getName(), rule.getMetric(), currentValue);
        if (!rule.isMuted()) {
            webhookNotifier.notifyAsync(rule, now, currentValue, status -> {
                event.setWebhookStatus(status);
                eventRepository.save(event);
            });
        }
    }

    private void resolve(AlertRule rule) {
        eventRepository.findFirstByAlertRuleIdAndResolvedAtIsNullOrderByTriggeredAtDesc(rule.getId())
                .ifPresent(event -> {
                    event.setResolvedAt(Instant.now());
                    eventRepository.save(event);
                });
    }
}
