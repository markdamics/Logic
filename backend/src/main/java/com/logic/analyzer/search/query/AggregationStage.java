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

    /**
     * SPL-subset: {@code | stats avg(field.duration_ms) by source} - a
     * numeric statistic (avg/min/max/sum/percentile) over a field.&lt;name&gt;#num
     * value, grouped by a keyword field. Mirrors StatsCountByStage's shape.
     * {@code groupByField} may be null (e.g. {@code | stats avg(field.duration_ms)}
     * with no {@code by} clause) meaning a single aggregate over every matched doc.
     */
    record NumericStatsByStage(String numericField, NumericStatFunction function, String groupByField)
            implements AggregationStage {
    }

    /**
     * LogQL-subset: {@code avg_over_time(field.duration_ms{...}[5m])} - the
     * same numeric statistic, time-bucketed. Mirrors CountOverTimeStage's shape.
     */
    record NumericStatsOverTimeStage(String numericField, NumericStatFunction function, Duration bucket)
            implements AggregationStage {
    }

    /** Computed from the log data Logic already indexes, not a separately ingested metrics pipeline. */
    enum NumericStatFunction {
        AVG, MIN, MAX, SUM, P50, P95, P99
    }
}
