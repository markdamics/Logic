package com.logic.analyzer.search.nlquery;

import com.logic.analyzer.search.query.LogQlSubsetQueryParser;
import com.logic.analyzer.search.query.LuceneSyntaxQueryParser;
import com.logic.analyzer.search.query.QueryLanguage;
import com.logic.analyzer.search.query.QueryParser;
import com.logic.analyzer.search.query.SplSubsetQueryParser;
import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.LogSourceRepository;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Exercises the full Phase 1 template pipeline: intent extraction, per-language
 * query string generation, and validation through the real QueryParser for
 * each language - proving every generated candidate actually parses.
 */
class NlQueryTemplateTranslatorTest {

    private NlQueryTemplateTranslator translator;

    @BeforeEach
    void setUp() {
        LogSource paymentsApi = mock(LogSource.class);
        lenient().when(paymentsApi.getName()).thenReturn("payments-api");
        LogSourceRepository sourceRepository = mock(LogSourceRepository.class);
        lenient().when(sourceRepository.findAll()).thenReturn(List.of(paymentsApi));

        List<QueryParser> parsers = List.of(
                new LuceneSyntaxQueryParser(new StandardAnalyzer()),
                new SplSubsetQueryParser(),
                new LogQlSubsetQueryParser());
        translator = new NlQueryTemplateTranslator(parsers, sourceRepository);
    }

    @Test
    void translatesToLuceneSyntax() {
        NlQueryTranslateResponse response = translator.translate(
                "show me errors from payments-api in the last hour", QueryLanguage.LUCENE);

        assertThat(response.matched()).isTrue();
        assertThat(response.query()).isEqualTo("level:ERROR AND source:\"payments-api\"");
        assertThat(response.rangeMinutes()).isEqualTo(60);
    }

    @Test
    void translatesToSplSyntax() {
        NlQueryTranslateResponse response = translator.translate(
                "show me errors from payments-api in the last hour", QueryLanguage.SPL);

        assertThat(response.matched()).isTrue();
        assertThat(response.query()).isEqualTo("level=ERROR AND source=\"payments-api\"");
        assertThat(response.rangeMinutes()).isEqualTo(60);
    }

    @Test
    void translatesToLogQlSyntax() {
        NlQueryTranslateResponse response = translator.translate(
                "show me errors from payments-api in the last hour", QueryLanguage.LOGQL);

        assertThat(response.matched()).isTrue();
        assertThat(response.query()).isEqualTo("{source=\"payments-api\", level=\"ERROR\"}");
        assertThat(response.rangeMinutes()).isEqualTo(60);
    }

    @Test
    void freeTextOnlyPromptProducesAQuotedPhraseClause() {
        NlQueryTranslateResponse response = translator.translate(
                "failed login attempts in the last 24h", QueryLanguage.LOGQL);

        assertThat(response.matched()).isTrue();
        assertThat(response.query()).isEqualTo("{} |= \"failed login attempts\"");
        assertThat(response.rangeMinutes()).isEqualTo(24 * 60);
    }

    @Test
    void unrecognizedPromptIsReportedAsUnmatchedRatherThanAnError() {
        NlQueryTranslateResponse response = translator.translate("   ", QueryLanguage.LUCENE);

        assertThat(response.matched()).isFalse();
        assertThat(response.query()).isNull();
        assertThat(response.message()).isNotBlank();
    }

    @Test
    void simpleLanguageIsRejected() {
        assertThatThrownBy(() -> translator.translate("errors", QueryLanguage.SIMPLE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
