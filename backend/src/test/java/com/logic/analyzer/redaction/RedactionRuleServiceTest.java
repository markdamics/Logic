package com.logic.analyzer.redaction;

import com.logic.analyzer.exception.RedactionRuleNotFoundException;
import com.logic.analyzer.redaction.dto.RedactionRuleCreateRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedactionRuleServiceTest {

    @Mock
    private RedactionRuleRepository repository;

    private RedactionRuleService service() {
        return new RedactionRuleService(repository);
    }

    @Test
    void rejectsAnInvalidRegexPattern() {
        RedactionRuleCreateRequest request = new RedactionRuleCreateRequest("bad", "[unclosed", null, null, true);

        assertThatThrownBy(() -> service().create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid regex pattern");
    }

    @Test
    void createsAGlobalRuleWithADefaultMaskWhenNoneIsGiven() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RedactionRuleCreateRequest request = new RedactionRuleCreateRequest(
                "emails", "[\\w.+-]+@[\\w-]+\\.[\\w.-]+", null, null, true);

        var response = service().create(request);

        assertThat(response.name()).isEqualTo("emails");
        assertThat(response.source()).isNull();
        assertThat(response.enabled()).isTrue();
    }

    @Test
    void deleteThrowsWhenTheRuleDoesNotExist() {
        when(repository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service().delete(99L))
                .isInstanceOf(RedactionRuleNotFoundException.class);
    }

    @Test
    void rulesForOmitsDisabledRules() {
        RedactionRule disabled = new RedactionRule("off", "secret", null, null, false);
        when(repository.findAll()).thenReturn(List.of(disabled));

        assertThat(service().rulesFor("any-source")).isEmpty();
    }

    @Test
    void rulesForOmitsRulesScopedToADifferentSource() {
        RedactionRule scoped = new RedactionRule("scoped", "secret", null, "other-source", true);
        when(repository.findAll()).thenReturn(List.of(scoped));

        assertThat(service().rulesFor("this-source")).isEmpty();
    }

    @Test
    void rulesForIncludesGlobalAndMatchingScopedRules() {
        RedactionRule global = new RedactionRule("global", "secret", null, null, true);
        RedactionRule scoped = new RedactionRule("scoped", "token", null, "this-source", true);
        when(repository.findAll()).thenReturn(List.of(global, scoped));

        assertThat(service().rulesFor("this-source")).hasSize(2);
    }

    @Test
    void redactMasksEveryMatchWithTheConfiguredReplacement() {
        RedactionRule rule = new RedactionRule("emails", "[\\w.+-]+@[\\w-]+\\.[\\w.-]+", "[EMAIL]", null, true);
        when(repository.findAll()).thenReturn(List.of(rule));

        List<RedactionRuleService.CompiledRule> rules = service().rulesFor("src");
        String result = RedactionRuleService.redact("contact jane@example.com now", rules);

        assertThat(result).isEqualTo("contact [EMAIL] now").doesNotContain("jane@example.com");
    }

    @Test
    void redactFallsBackToADefaultMaskWhenNoReplacementIsConfigured() {
        RedactionRule rule = new RedactionRule("emails", "[\\w.+-]+@[\\w-]+\\.[\\w.-]+", null, null, true);
        when(repository.findAll()).thenReturn(List.of(rule));

        List<RedactionRuleService.CompiledRule> rules = service().rulesFor("src");
        String result = RedactionRuleService.redact("contact jane@example.com now", rules);

        assertThat(result).isEqualTo("contact *** now");
    }

    @Test
    void redactTreatsTheReplacementAsLiteralTextNotARegexBackreference() {
        RedactionRule rule = new RedactionRule("dollar", "secret", "$1 and \\1", null, true);
        when(repository.findAll()).thenReturn(List.of(rule));

        List<RedactionRuleService.CompiledRule> rules = service().rulesFor("src");
        String result = RedactionRuleService.redact("this is secret data", rules);

        assertThat(result).isEqualTo("this is $1 and \\1 data");
    }
}
