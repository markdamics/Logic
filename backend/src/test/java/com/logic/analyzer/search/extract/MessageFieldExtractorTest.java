package com.logic.analyzer.search.extract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageFieldExtractorTest {

    @Test
    void detectsAndFlattensJson() {
        var result = MessageFieldExtractor.extract(
                "{\"level\":\"error\",\"service\":\"payments\",\"details\":{\"retry\":true}}");

        assertThat(result.format()).isEqualTo("json");
        assertThat(result.fields()).extracting(MessageFieldExtractor.Field::name, MessageFieldExtractor.Field::value)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("level", "error"),
                        org.assertj.core.groups.Tuple.tuple("service", "payments"),
                        org.assertj.core.groups.Tuple.tuple("details.retry", "true"));
    }

    @Test
    void detectsSyslog5424() {
        var result = MessageFieldExtractor.extract(
                "<34>1 2026-08-06T08:00:00Z db01 systemd 1234 ID47 Started session");

        assertThat(result.format()).isEqualTo("syslog");
        assertThat(result.fields()).extracting(MessageFieldExtractor.Field::name)
                .contains("priority", "hostname", "appName", "procId", "msgId", "message");
    }

    @Test
    void detectsSyslogTagVariantAfterTimestampIsStripped() {
        var result = MessageFieldExtractor.extract("cache01 systemd[8772]: Started Session 104 of user www-data.");

        assertThat(result.format()).isEqualTo("syslog");
        assertThat(result.fields()).contains(
                new MessageFieldExtractor.Field("hostname", "cache01"),
                new MessageFieldExtractor.Field("tag", "systemd"),
                new MessageFieldExtractor.Field("pid", "8772"),
                new MessageFieldExtractor.Field("message", "Started Session 104 of user www-data."));
    }

    @Test
    void detectsAccessLog() {
        var result = MessageFieldExtractor.extract(
                "203.0.113.17 - - \"GET /favicon.ico HTTP/1.1\" 200 7718 \"-\" \"Mozilla/5.0\"");

        assertThat(result.format()).isEqualTo("accesslog");
        assertThat(result.fields()).contains(
                new MessageFieldExtractor.Field("host", "203.0.113.17"),
                new MessageFieldExtractor.Field("status", "200"),
                new MessageFieldExtractor.Field("bytes", "7718"));
    }

    @Test
    void detectsLogfmt() {
        var result = MessageFieldExtractor.extract("level=error msg=\"card declined\" retries=3 amount=42.50");

        assertThat(result.format()).isEqualTo("logfmt");
        assertThat(result.fields()).contains(
                new MessageFieldExtractor.Field("level", "error"),
                new MessageFieldExtractor.Field("msg", "card declined"),
                new MessageFieldExtractor.Field("retries", "3"));
    }

    @Test
    void fallsBackToUnstructuredForPlainText() {
        var result = MessageFieldExtractor.extract("BillingWorker - Scheduled job export completed");

        assertThat(result.format()).isEqualTo("unstructured");
        assertThat(result.fields()).isEmpty();
    }

    @Test
    void fallsBackToUnstructuredForBlankMessage() {
        assertThat(MessageFieldExtractor.extract("").format()).isEqualTo("unstructured");
        assertThat(MessageFieldExtractor.extract(null).format()).isEqualTo("unstructured");
    }
}
