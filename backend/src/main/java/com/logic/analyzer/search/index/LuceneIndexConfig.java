package com.logic.analyzer.search.index;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Wires up the embedded Lucene index that backs the Search & Query feature -
 * an on-disk, in-process search index (no external service), living
 * alongside the H2 data file and encryption key under ./data/.
 */
@Configuration
public class LuceneIndexConfig {

    /**
     * Keyword analysis by default (single exact term, no tokenization/lowercasing) -
     * the right behavior for the keyword fields (source/file/level/format and the
     * dynamic field.&lt;name&gt; fields, whose names aren't known statically so they
     * can't be enumerated here individually). message/_all are the only fields
     * that need real full-text tokenization, so they get StandardAnalyzer instead.
     * This only affects how QueryParser-driven query text is analyzed - it has no
     * effect on indexing itself, since StringField already bypasses analysis.
     */
    @Bean
    public Analyzer searchAnalyzer() {
        Map<String, Analyzer> perField = Map.of(
                "message", new StandardAnalyzer(),
                "_all", new StandardAnalyzer()
        );
        return new PerFieldAnalyzerWrapper(new KeywordAnalyzer(), perField);
    }

    @Bean(destroyMethod = "close")
    public Directory searchIndexDirectory(@Value("${app.search.index-dir:./data/search-index}") String indexDir) throws IOException {
        Path path = Path.of(indexDir);
        Files.createDirectories(path);
        return FSDirectory.open(path);
    }

    @Bean(destroyMethod = "close")
    public IndexWriter indexWriter(Directory searchIndexDirectory, Analyzer searchAnalyzer) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(searchAnalyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        return new IndexWriter(searchIndexDirectory, config);
    }

    @Bean(destroyMethod = "close")
    public SearcherManager searcherManager(IndexWriter indexWriter) throws IOException {
        return new SearcherManager(indexWriter, false, false, null);
    }

    /**
     * Shared between LogDocumentBuilder (write side, encodes each document's
     * SortedSetDocValuesFacetFields) and LuceneQueryExecutor (read side,
     * builds the SortedSetDocValuesReaderState) - default dim config
     * (flat, single-valued) is correct for every dimension we use: each
     * entry has exactly one source/file/level/format, and the JSON
     * flattener's [i]-indexed field names mean a given field.&lt;name&gt;
     * dimension never repeats within one document either.
     */
    @Bean
    public FacetsConfig facetsConfig() {
        return new FacetsConfig();
    }
}
