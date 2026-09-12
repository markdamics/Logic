package com.logic.analyzer.redaction;

import com.logic.analyzer.redaction.dto.RedactionRuleCreateRequest;
import com.logic.analyzer.redaction.dto.RedactionRuleResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/redaction/rules")
public class RedactionRuleController {

    private final RedactionRuleService service;

    public RedactionRuleController(RedactionRuleService service) {
        this.service = service;
    }

    @GetMapping
    public List<RedactionRuleResponse> list() {
        return service.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RedactionRuleResponse create(@Valid @RequestBody RedactionRuleCreateRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public RedactionRuleResponse update(@PathVariable Long id, @Valid @RequestBody RedactionRuleCreateRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
