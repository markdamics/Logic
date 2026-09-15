package com.logic.analyzer.template;

import com.logic.analyzer.logstream.LogEntry;
import com.logic.analyzer.logstream.LogLevel;
import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TemplateMiningServiceTest {

    @Mock
    private LogTemplateRepository repository;

    private TemplateMiningService service;
    private LogSource source;
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        service = new TemplateMiningService(repository);
        source = new LogSource("payments-api", SourceType.LOCAL_FILE, "/var/log/app.log", null, null, null, null);
        // repository.findBySourceAndFile(...) is left unstubbed deliberately - Mockito's
        // default answer for an unstubbed List-returning method is an empty list, which is
        // exactly "no prior templates for this (source, file)", the state every test starts from.
    }

    private LogEntry entry(String message) {
        return new LogEntry(0, now, LogLevel.INFO, "payments-api", "app.log", message);
    }

    /** Mirrors LogIngestionService.errorEntry() - a synthetic entry, not real ingested content. */
    private LogEntry syntheticIngestionErrorEntry(String message) {
        return new LogEntry(0, now, LogLevel.ERROR, "payments-api", null, message);
    }

    @Test
    void syntheticIngestionErrorEntriesNeverBecomeAPattern() {
        service.mine(source, "", List.of(
                syntheticIngestionErrorEntry("Failed to read log source: file not found: /var/log/app.log"),
                syntheticIngestionErrorEntry("Failed to read log source: file not found: /var/log/app.log")));

        assertThat(clusters()).isEmpty();
    }

    @Test
    void aSyntheticIngestionErrorEntryMixedInWithRealLinesIsIgnoredButRealLinesStillMine() {
        service.mine(source, "app.log", List.of(
                entry("User bob123 logged in from 10.0.0.5"),
                syntheticIngestionErrorEntry("Failed to read log source: file not found: /var/log/app.log")));

        List<LogTemplate> templates = clusters();
        assertThat(templates).hasSize(1);
        assertThat(templates.get(0).getTemplateText()).contains("logged", "in", "from");
    }

    @Test
    void threeDistinctShapesAtVaryingFrequenciesClusterIntoExactlyThreeTemplates() {
        List<LogEntry> pass = List.of(
                entry("User bob123 logged in from 10.0.0.5"),
                entry("User bob123 logged in from 10.0.0.5"),
                entry("User alice99 logged in from 10.0.0.9"),
                entry("Payment failed for order 48291 amount 129.99"),
                entry("Payment failed for order 90210 amount 4.50"),
                entry("Connection timeout after 30 seconds"));

        service.mine(source, "app.log", pass);

        List<LogTemplate> templates = clusters();
        assertThat(templates).hasSize(3);
        assertThat(templates.stream().mapToLong(LogTemplate::getOccurrenceCount).sum()).isEqualTo(6);

        LogTemplate loginTemplate = findByOccurrence(templates, 3);
        assertThat(loginTemplate.getTemplateText()).isEqualTo("User <ID> logged in from <IP>");

        LogTemplate paymentTemplate = findByOccurrence(templates, 2);
        assertThat(paymentTemplate.getTemplateText()).isEqualTo("Payment failed for order <NUM> amount <NUM>");

        LogTemplate timeoutTemplate = findByOccurrence(templates, 1);
        assertThat(timeoutTemplate.getTemplateText()).isEqualTo("Connection timeout after <NUM> seconds");
    }

    @Test
    void aFourthShapeMidStreamMintsAFourthTemplateInsteadOfDroppingLines() {
        service.mine(source, "app.log", List.of(
                entry("User bob123 logged in from 10.0.0.5"),
                entry("Payment failed for order 48291 amount 129.99"),
                entry("Connection timeout after 30 seconds")));
        assertThat(clusters()).hasSize(3);

        // A deploy changes the log format: a brand new shape appears mid-stream, on top of
        // (not replacing) the previous three lines still present in this pass's tail window.
        service.mine(source, "app.log", List.of(
                entry("User bob123 logged in from 10.0.0.5"),
                entry("Payment failed for order 48291 amount 129.99"),
                entry("Connection timeout after 30 seconds"),
                entry("Cache eviction triggered for region us-east-1")));

        List<LogTemplate> templates = clusters();
        assertThat(templates).hasSize(4);
        assertThat(templates).anySatisfy(t -> assertThat(t.getTemplateText()).contains("Cache", "eviction"));
    }

    @Test
    void reprocessingTheSameUnchangedTailWindowDoesNotInflateOccurrenceCount() {
        List<LogEntry> pass = List.of(
                entry("User bob123 logged in from 10.0.0.5"),
                entry("User bob123 logged in from 10.0.0.5"));

        // Simulates SearchIndexService re-adding the file's entire current tail window on
        // three separate scheduled passes even though nothing new was appended.
        service.mine(source, "app.log", pass);
        service.mine(source, "app.log", pass);
        service.mine(source, "app.log", pass);

        List<LogTemplate> templates = clusters();
        assertThat(templates).hasSize(1);
        assertThat(templates.get(0).getOccurrenceCount()).isEqualTo(2);
    }

    @Test
    void genuinelyRepeatedIdenticalLinesAcrossPassesAreCountedNotCollapsed() {
        // Pass 1: two identical health-check lines already in the window.
        service.mine(source, "app.log", List.of(
                entry("Health check OK"),
                entry("Health check OK")));
        assertThat(clusters().get(0).getOccurrenceCount()).isEqualTo(2);

        // Pass 2: the file grew - one more identical line appended (now three total in window).
        service.mine(source, "app.log", List.of(
                entry("Health check OK"),
                entry("Health check OK"),
                entry("Health check OK")));

        List<LogTemplate> templates = clusters();
        assertThat(templates).hasSize(1);
        assertThat(templates.get(0).getOccurrenceCount()).isEqualTo(3);
    }

    @Test
    void commaDelimitedCsvRowsClusterIntoOneTemplateInsteadOfOnePerRow() {
        // Before comma was a delimiter, an entire unspaced row was one giant token - no two
        // rows could ever match regardless of content, guaranteeing one template per row.
        // id/email/date vary (and mask) per row; name/country/plan repeat, as they would for
        // several signups processed through the same code path.
        service.mine(source, "app.log", List.of(
                entry("1001,Liu,Brown,liu.brown1@example.com,BR,enterprise,2026-01-15"),
                entry("1002,Liu,Brown,liu.brown2@example.com,BR,enterprise,2026-01-16"),
                entry("1003,Liu,Brown,liu.brown3@example.com,BR,enterprise,2026-01-17")));

        List<LogTemplate> templates = clusters();
        assertThat(templates).hasSize(1);
        assertThat(templates.get(0).getOccurrenceCount()).isEqualTo(3);
        assertThat(templates.get(0).getTemplateText()).isEqualTo("<NUM> Liu Brown <EMAIL> BR enterprise <DATE>");
    }

    private List<LogTemplate> clusters() {
        // The mining service keeps its own per-source in-memory cluster cache (loaded from
        // the repository lazily); route through it the same way TemplateService would.
        return repositorySavedTemplates();
    }

    private List<LogTemplate> repositorySavedTemplates() {
        org.mockito.ArgumentCaptor<LogTemplate> captor = org.mockito.ArgumentCaptor.forClass(LogTemplate.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues().stream().distinct().toList();
    }

    private LogTemplate findByOccurrence(List<LogTemplate> templates, long occurrenceCount) {
        return templates.stream().filter(t -> t.getOccurrenceCount() == occurrenceCount).findFirst()
                .orElseThrow(() -> new AssertionError("No template with occurrenceCount=" + occurrenceCount + " in " + templates));
    }
}
