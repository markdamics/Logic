package com.logic.analyzer.alert;

import com.logic.analyzer.logstream.LogEntry;
import com.logic.analyzer.logstream.LogIngestionService;
import com.logic.analyzer.logstream.LogLevel;
import com.logic.analyzer.logstream.LogQueryService;
import com.logic.analyzer.search.SearchQueryService;
import com.logic.analyzer.search.index.LogDocumentBuilder;
import com.logic.analyzer.search.index.SearchIndexService;
import com.logic.analyzer.search.query.LuceneQueryExecutor;
import com.logic.analyzer.search.query.QueryCompiler;
import com.logic.analyzer.search.query.QueryLanguage;
import com.logic.analyzer.source.LogSource;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises AlertEvaluationService against a real (in-memory) Lucene index -
 * the same setup SearchIndexServiceTest/SearchQueryServiceTest use - since
 * the whole point of this service is the count_over_time bucketing math, not
 * something a mocked executor could prove.
 */
@ExtendWith(MockitoExtension.class)
class AlertEvaluationServiceTest {

    @Mock
    private AlertRuleRepository ruleRepository;
    @Mock
    private AlertEventRepository eventRepository;
    @Mock
    private LogIngestionService ingestionService;
    @Mock
    private SearchIndexService searchIndexService;
    @Mock
    private WebhookNotifier webhookNotifier;

    private final Analyzer analyzer = new PerFieldAnalyzerWrapper(new KeywordAnalyzer(),
            Map.of("message", new StandardAnalyzer(), "_all", new StandardAnalyzer()));
    private final FacetsConfig facetsConfig = new FacetsConfig();
    private final LogDocumentBuilder documentBuilder = new LogDocumentBuilder(facetsConfig);
    private final LogSource testSource = mock(LogSource.class);

    private Directory directory;
    private IndexWriter writer;
    private SearcherManager searcherManager;
    private AlertEvaluationService service;

    @BeforeEach
    void setUp() throws Exception {
        when(testSource.getId()).thenReturn(1L);
        directory = new ByteBuffersDirectory();
        writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
        searcherManager = new SearcherManager(writer, false, false, null);

        LuceneQueryExecutor executor = new LuceneQueryExecutor(searcherManager, facetsConfig);
        QueryCompiler queryCompiler = new QueryCompiler(analyzer);
        LogQueryService logQueryService = new LogQueryService(ingestionService, searchIndexService, executor, queryCompiler);
        SearchQueryService searchQueryService = new SearchQueryService(List.of(), queryCompiler, executor);

        when(ruleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // lenient: not every test's rule actually triggers, so this stub goes unused in those.
        lenient().when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new AlertEvaluationService(ruleRepository, eventRepository, logQueryService, searchQueryService,
                executor, webhookNotifier);
    }

    @AfterEach
    void tearDown() throws Exception {
        searcherManager.close();
        writer.close();
        directory.close();
    }

    private void seed(String source, LogLevel level, String message, Instant timestamp) throws Exception {
        LogEntry entry = new LogEntry(1, timestamp, level, source, "app.log", message);
        String docId = "doc-" + source + "-" + timestamp.toEpochMilli() + "-" + Math.random();
        Document doc = documentBuilder.build(testSource, entry, docId);
        writer.updateDocument(new Term("docId", docId), doc);
        writer.commit();
        searcherManager.maybeRefresh();
    }

    /** AlertRule's id is normally JPA-assigned on save; give the test fixture a real one so triggeredState's ConcurrentHashMap (no null keys) works. */
    private static void setId(AlertRule rule, long id) throws Exception {
        Field field = AlertRule.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(rule, id);
    }

    @Test
    void thresholdRuleFiresOnceWhenCountCrossesTheLine() throws Exception {
        AlertRule rule = new AlertRule("high errors", QueryLanguage.SIMPLE, null, null, Set.of(LogLevel.ERROR),
                "svc", null, AlertRuleType.THRESHOLD, 5, AlertMetric.COUNT,
                ComparisonOperator.GT, 2.0, null, null, null, null);
        setId(rule, 1L);
        when(ruleRepository.findAll()).thenReturn(List.of(rule));

        Instant now = Instant.now();
        seed("svc", LogLevel.ERROR, "boom 1", now.minusSeconds(10));
        seed("svc", LogLevel.ERROR, "boom 2", now.minusSeconds(20));
        seed("svc", LogLevel.ERROR, "boom 3", now.minusSeconds(30));

        service.evaluateAll();

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getMetricValue()).isEqualTo(3.0);
        assertThat(captor.getValue().getResolvedAt()).isNull();
        assertThat(rule.getLastTriggeredAt()).isNotNull();
    }

    @Test
    void thresholdRuleDoesNotFireWhenBelowTheLine() throws Exception {
        AlertRule rule = new AlertRule("high errors", QueryLanguage.SIMPLE, null, null, Set.of(LogLevel.ERROR),
                "svc", null, AlertRuleType.THRESHOLD, 5, AlertMetric.COUNT,
                ComparisonOperator.GT, 2.0, null, null, null, null);
        setId(rule, 1L);
        when(ruleRepository.findAll()).thenReturn(List.of(rule));

        seed("svc", LogLevel.ERROR, "boom", Instant.now().minusSeconds(10));

        service.evaluateAll();

        verify(eventRepository, never()).save(any());
        assertThat(rule.getLastTriggeredAt()).isNull();
    }

    @Test
    void aClearedConditionResolvesTheOpenEvent() throws Exception {
        AlertRule rule = new AlertRule("high errors", QueryLanguage.SIMPLE, null, null, Set.of(LogLevel.ERROR),
                "svc", null, AlertRuleType.THRESHOLD, 5, AlertMetric.COUNT,
                ComparisonOperator.GT, 2.0, null, null, null, null);
        setId(rule, 1L);
        when(ruleRepository.findAll()).thenReturn(List.of(rule));

        Instant now = Instant.now();
        seed("svc", LogLevel.ERROR, "boom 1", now.minusSeconds(10));
        seed("svc", LogLevel.ERROR, "boom 2", now.minusSeconds(20));
        seed("svc", LogLevel.ERROR, "boom 3", now.minusSeconds(30));
        service.evaluateAll(); // triggers

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(eventRepository).save(captor.capture());
        AlertEvent openEvent = captor.getValue();
        when(eventRepository.findFirstByAlertRuleIdAndResolvedAtIsNullOrderByTriggeredAtDesc(1L))
                .thenReturn(Optional.of(openEvent));

        // Re-scope the rule to a source with no matching entries - condition clears.
        setSource(rule, "no-such-service");
        service.evaluateAll();

        assertThat(openEvent.getResolvedAt()).isNotNull();
    }

    @Test
    void mutedRuleStillEvaluatesButNeverNotifies() throws Exception {
        AlertRule rule = new AlertRule("high errors", QueryLanguage.SIMPLE, null, null, Set.of(LogLevel.ERROR),
                "svc", null, AlertRuleType.THRESHOLD, 5, AlertMetric.COUNT,
                ComparisonOperator.GT, 2.0, null, null, "http://example.invalid/hook", null);
        setId(rule, 1L);
        rule.setMuted(true);
        when(ruleRepository.findAll()).thenReturn(List.of(rule));

        Instant now = Instant.now();
        seed("svc", LogLevel.ERROR, "boom 1", now.minusSeconds(10));
        seed("svc", LogLevel.ERROR, "boom 2", now.minusSeconds(20));
        seed("svc", LogLevel.ERROR, "boom 3", now.minusSeconds(30));

        service.evaluateAll();

        assertThat(rule.getLastTriggeredAt()).isNotNull();
        verify(eventRepository).save(any());
        verify(webhookNotifier, never()).notifyAsync(any(), any(), org.mockito.ArgumentMatchers.anyDouble(), any());
    }

    @Test
    void anomalyRuleFlagsASpikeAgainstAConstantBaseline() throws Exception {
        AlertRule rule = new AlertRule("spike", QueryLanguage.SIMPLE, null, null, Set.of(),
                "spiky", null, AlertRuleType.ANOMALY, 10, AlertMetric.COUNT,
                null, null, 4, 3.0, null, null);
        setId(rule, 1L);
        when(ruleRepository.findAll()).thenReturn(List.of(rule));

        Instant now = Instant.now();
        // 4 baseline windows (10min each), each with exactly 2 entries -> stddev 0.
        int[] minutesAgo = {45, 35, 25, 15};
        for (int m : minutesAgo) {
            seed("spiky", LogLevel.INFO, "steady 1", now.minus(Duration.ofMinutes(m)));
            seed("spiky", LogLevel.INFO, "steady 2", now.minus(Duration.ofMinutes(m)));
        }
        // Current window: a spike.
        for (int i = 0; i < 20; i++) {
            seed("spiky", LogLevel.INFO, "spike " + i, now.minusSeconds(5));
        }

        service.evaluateAll();

        verify(eventRepository).save(any());
        assertThat(rule.getLastTriggeredAt()).isNotNull();
    }

    @Test
    void anomalyRuleDoesNotFlagNormalVarianceWithinTheBaseline() throws Exception {
        AlertRule rule = new AlertRule("stable", QueryLanguage.SIMPLE, null, null, Set.of(),
                "stable-svc", null, AlertRuleType.ANOMALY, 10, AlertMetric.COUNT,
                null, null, 4, 3.0, null, null);
        setId(rule, 1L);
        when(ruleRepository.findAll()).thenReturn(List.of(rule));

        Instant now = Instant.now();
        // Baseline windows alternate 2/3 entries (mean 2.5, stddev 0.5) - current window (3) is well within 3 stddev.
        int[] baselineMinutesAgo = {45, 35, 25, 15};
        int[] baselineCounts = {2, 3, 2, 3};
        for (int i = 0; i < baselineMinutesAgo.length; i++) {
            for (int c = 0; c < baselineCounts[i]; c++) {
                seed("stable-svc", LogLevel.INFO, "line " + i + "-" + c, now.minus(Duration.ofMinutes(baselineMinutesAgo[i])));
            }
        }
        seed("stable-svc", LogLevel.INFO, "current 1", now.minusSeconds(5));
        seed("stable-svc", LogLevel.INFO, "current 2", now.minusSeconds(10));
        seed("stable-svc", LogLevel.INFO, "current 3", now.minusSeconds(15));

        service.evaluateAll();

        verify(eventRepository, never()).save(any());
        assertThat(rule.getLastTriggeredAt()).isNull();
    }

    private static void setSource(AlertRule rule, String source) throws Exception {
        Field field = AlertRule.class.getDeclaredField("source");
        field.setAccessible(true);
        field.set(rule, source);
    }
}
