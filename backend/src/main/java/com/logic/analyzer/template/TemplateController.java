package com.logic.analyzer.template;

import com.logic.analyzer.template.dto.TemplateResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateService service;

    public TemplateController(TemplateService service) {
        this.service = service;
    }

    @GetMapping
    public List<TemplateResponse> list(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String file,
            @RequestParam(required = false, defaultValue = "volume") String sort) {
        return service.list(source, file, sort);
    }
}
