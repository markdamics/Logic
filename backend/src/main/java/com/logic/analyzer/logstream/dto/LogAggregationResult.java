package com.logic.analyzer.logstream.dto;

import java.util.List;

/**
 * Result of a query-bar aggregation stage ({@code stats count by <field>},
 * {@code count_over_time(...)}, {@code rate(...)}) - carried on
 * {@link LogQueryResult#aggregation()} instead of {@code content} when the
 * parsed query included one.
 */
public record LogAggregationResult(
        String groupField,
        List<Bucket> buckets,
        long totalMatched
) {
    /**
     * @param key       the group's field value for stats-count-by, or the bucket's
     *                  start instant (ISO-8601) for time-bucketed aggregations
     * @param rate      count/second - only set for {@code rate(...)}, null otherwise
     * @param statValue the computed avg/min/max/sum/percentile value - only set for a
     *                  NumericStatsByStage/NumericStatsOverTimeStage query, null otherwise
     */
    public record Bucket(String key, long count, Double rate, Double statValue) {
    }
}
