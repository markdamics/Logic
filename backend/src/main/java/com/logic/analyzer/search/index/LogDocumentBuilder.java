package com.logic.analyzer.search.index;

import com.logic.analyzer.logstream.LogEntry;
import com.logic.analyzer.search.extract.MessageFieldExtractor;
import com.logic.analyzer.source.LogSource;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetField;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Builds the Lucene {@link Document} schema for one {@link LogEntry}. Every
 * document gets: a stable "docId" (a diagnostic identifier - SearchIndexService
 * reconciles a file's documents by deleting and re-adding per pass, not by
 * upserting on this field), the source/level/file
 * fixed fields (both a stored exact-match copy and a sortable DocValues copy),
 * a numeric timestamp (for range filtering and sorting), the raw message, and
 * a composite "_all" field powering bare-keyword search - the indexed
 * equivalent of LogQueryService's old concatenate-and-.contains() scan.
 *
 * On top of that, {@link MessageFieldExtractor} detects a structured shape
 * (JSON/syslog/access-log/logfmt) in the message and each of its fields
 * becomes an individually filterable "field.&lt;name&gt;" keyword field, with a
 * numeric sibling "field.&lt;name&gt;#num" when the value looks numeric - a
 * DoublePoint (range filtering, e.g. status&gt;=500) plus a "field.&lt;name&gt;#numdv"
 * NumericDocValuesField sibling (a separate field name, not a doc-values
 * addition to "#num" itself - see the inline comment below) so
 * LuceneQueryExecutor can read matching docs' actual values back for metrics
 * aggregation (avg/min/max/percentile) - capped at MAX_DYNAMIC_FIELDS
 * per document since arbitrary JSON has unbounded key cardinality (fields
 * beyond the cap still appear in the full-text message/_all content, just
 * not as their own filterable field).
 *
 * Every keyword field (fixed and dynamic) also gets a SortedSetDocValuesFacetField
 * sibling so "stats count by &lt;field&gt;"-style aggregation works generically,
 * not just for a fixed field allowlist - {@link FacetsConfig#build(Document)}
 * encodes those into their final indexed form before the document is returned.
 */
@Component
public class LogDocumentBuilder {

    private static final int MAX_DYNAMIC_FIELDS = 32;
    private static final Pattern NUMERIC = Pattern.compile("-?\\d+(\\.\\d+)?");

    private final FacetsConfig facetsConfig;

    public LogDocumentBuilder(FacetsConfig facetsConfig) {
        this.facetsConfig = facetsConfig;
    }

    public Document build(LogSource source, LogEntry entry, String docId) throws IOException {
        Document doc = new Document();

        doc.add(new StringField("docId", docId, Field.Store.YES));
        doc.add(new StoredField("sourceId", source.getId()));

        String sourceName = entry.source();
        doc.add(new StringField("source", sourceName, Field.Store.YES));
        doc.add(new SortedDocValuesField("source", new BytesRef(sourceName)));
        doc.add(new SortedSetDocValuesFacetField("source", sourceName));

        String file = entry.file() == null ? "" : entry.file();
        doc.add(new StringField("file", file, Field.Store.YES));
        doc.add(new SortedDocValuesField("file", new BytesRef(file)));
        doc.add(new SortedSetDocValuesFacetField("file", file.isEmpty() ? "—" : file));

        doc.add(new StringField("level", entry.level().name(), Field.Store.YES));
        doc.add(new NumericDocValuesField("levelOrdinal", entry.level().ordinal()));
        doc.add(new SortedSetDocValuesFacetField("level", entry.level().name()));

        long millis = entry.timestamp().toEpochMilli();
        doc.add(new LongPoint("timestampMillis", millis));
        doc.add(new NumericDocValuesField("timestampMillis", millis));
        doc.add(new StoredField("timestampMillis", millis));

        MessageFieldExtractor.ExtractedFields extracted = MessageFieldExtractor.extract(entry.message());
        doc.add(new StringField("format", extracted.format(), Field.Store.YES));
        doc.add(new SortedSetDocValuesFacetField("format", extracted.format()));

        int dynamicFieldCount = 0;
        for (MessageFieldExtractor.Field field : extracted.fields()) {
            if (dynamicFieldCount >= MAX_DYNAMIC_FIELDS) {
                break;
            }
            String fieldName = "field." + field.name();
            doc.add(new StringField(fieldName, field.value(), Field.Store.YES));
            // SortedSetDocValuesFacetField rejects an empty label outright (real JSON/logfmt
            // content can have an empty-string value, e.g. {"user": ""}) - still exact-match
            // filterable via the StringField above, just not facet-able for "stats count by".
            if (!field.value().isEmpty()) {
                doc.add(new SortedSetDocValuesFacetField(fieldName, field.value()));
            }
            if (NUMERIC.matcher(field.value()).matches()) {
                double numericValue = Double.parseDouble(field.value());
                doc.add(new DoublePoint(fieldName + "#num", numericValue));
                // A distinct field name (not "#num" reused) is required here: a Lucene index's
                // per-field schema (including doc-values type) is fixed by whichever segment
                // wrote it first, so adding doc values under the existing "#num" name breaks on
                // any index with documents written before this field existed ("cannot change
                // field ... from doc values type=NONE to inconsistent doc values type=NUMERIC").
                // "#numdv" is a fresh field name, so it's absent (not NONE) on old documents -
                // LuceneQueryExecutor just treats those as unsampled for numeric-stats purposes,
                // no reindex/migration required. The double is packed via NumericUtils'
                // sortable-bits conversion (symmetric with sortableLongToDouble on read) since
                // NumericDocValuesField only stores longs.
                doc.add(new NumericDocValuesField(fieldName + "#numdv", NumericUtils.doubleToSortableLong(numericValue)));
            }
            dynamicFieldCount++;
        }

        doc.add(new TextField("message", entry.message(), Field.Store.YES));
        doc.add(new TextField("_all", compositeText(entry, extracted), Field.Store.NO));

        return facetsConfig.build(doc);
    }

    /** Everything a bare keyword search should be able to match against, mirroring the old LogQueryService.matches() concatenation. */
    private String compositeText(LogEntry entry, MessageFieldExtractor.ExtractedFields extracted) {
        StringBuilder sb = new StringBuilder(entry.message())
                .append(' ').append(entry.source())
                .append(' ').append(entry.level().name())
                .append(' ').append(entry.file() == null ? "" : entry.file());
        for (MessageFieldExtractor.Field field : extracted.fields()) {
            sb.append(' ').append(field.value());
        }
        return sb.toString();
    }
}
