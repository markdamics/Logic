package com.logic.analyzer.search.nlquery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plain-English entry point in front of the query bar (LOGIC-107 Phase 1):
 * translates a prompt into a candidate query string for the requested
 * QueryLanguage, validated but never auto-executed - the frontend fills the
 * (editable) query bar with the result for the admin to review and run.
 */
@RestController
@RequestMapping("/api/logs/query")
public class NlQueryController {

    private static final Logger log = LoggerFactory.getLogger(NlQueryController.class);

    private final NlQueryTemplateTranslator translator;

    public NlQueryController(NlQueryTemplateTranslator translator) {
        this.translator = translator;
    }

    @PostMapping("/translate")
    public NlQueryTranslateResponse translate(@RequestBody NlQueryTranslateRequest request) {
        log.info("POST /api/logs/query/translate queryLanguage={} prompt='{}'",
                request.queryLanguage(), request.prompt());
        return translator.translate(request.prompt(), request.queryLanguage());
    }
}
