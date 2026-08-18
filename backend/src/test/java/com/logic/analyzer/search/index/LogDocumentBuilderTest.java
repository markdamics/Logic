package com.logic.analyzer.search.index;

import com.logic.analyzer.logstream.LogEntry;
import com.logic.analyzer.logstream.LogLevel;
import com.logic.analyzer.source.LogSource;
import org.apache.lucene.document.Document;
import org.apache.lucene.facet.FacetsConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LogDocumentBuilderTest {

    private final FacetsConfig facetsConfig = new FacetsConfig();
    private final LogDocumentBuilder builder = new LogDocumentBuilder(facetsConfig);

    private LogSource testSource() {
        LogSource source = mock(LogSource.class);
        when(source.getId()).thenReturn(1L);
        return source;
    }

    @Test
    void indexesAPlainUnstructuredMessage() throws Exception {
        LogEntry entry = new LogEntry(1, Instant.now(), LogLevel.INFO, "svc", "app.log", "just some text");

        Document doc = builder.build(testSource(), entry, "doc-1");

        assertThat(doc.get("message")).isEqualTo("just some text");
        assertThat(doc.get("format")).isEqualTo("unstructured");
    }

    @Test
    void doesNotThrowWhenAnExtractedJsonFieldIsAnEmptyString() throws Exception {
        LogEntry entry = new LogEntry(1, Instant.now(), LogLevel.INFO, "svc", "app.log",
                "{\"user\": \"\", \"service\": \"payments\"}");

        Document doc = builder.build(testSource(), entry, "doc-1");

        assertThat(doc.get("field.user")).isEqualTo("");
        assertThat(doc.get("field.service")).isEqualTo("payments");
    }

    @Test
    void doesNotThrowForAnEmptyFileLabel() throws Exception {
        LogEntry entry = new LogEntry(1, Instant.now(), LogLevel.INFO, "svc", null, "no file for this entry");

        Document doc = builder.build(testSource(), entry, "doc-1");

        assertThat(doc.get("file")).isEqualTo("");
    }

    @Test
    void capsDynamicFieldsAtThirtyTwoPerDocument() throws Exception {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < 40; i++) {
            if (i > 0) json.append(',');
            json.append("\"field").append(i).append("\":\"value").append(i).append('"');
        }
        json.append('}');
        LogEntry entry = new LogEntry(1, Instant.now(), LogLevel.INFO, "svc", "app.log", json.toString());

        Document doc = builder.build(testSource(), entry, "doc-1");

        long dynamicFieldCount = doc.getFields().stream()
                .filter(f -> f.name().startsWith("field.") && !f.name().endsWith("#num"))
                .count();
        assertThat(dynamicFieldCount).isEqualTo(32);
    }
}
