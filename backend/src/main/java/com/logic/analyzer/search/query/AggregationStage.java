package com.logic.analyzer.search.query;

import java.time.Duration;

/** The one aggregation stage a query-bar query may end with, per language. */
public sealed interface AggregationStage {

    /** SPL-subset: {@code | stats count by <field>} - counts matching docs grouped by a keyword field's value. */
    record StatsCountByStage(String groupByField) implements AggregationStage {
    }

    /** LogQL-subset: {@code count_over_time({...}[5m])} - matching-doc counts bucketed by time. */
    record CountOverTimeStage(Duration bucket) implements AggregationStage {
    }

    /** LogQL-subset: {@code rate({...}[1m])} - same bucketing as CountOverTimeStage, expressed as count/second. */
    record RateStage(Duration bucket) implements AggregationStage {
    }
}
