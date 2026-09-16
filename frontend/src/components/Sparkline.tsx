interface SparklineProps {
  /** Per-bucket occurrence counts, oldest first. */
  values: number[];
  width?: number;
  height?: number;
}

/**
 * Compact single-series bar trend, no axes/legend (per dataviz guidance, a
 * single series needs neither - the surrounding table row already labels
 * what's being counted). Color comes from --color-primary via CSS so it
 * stays correct across theme switches without recomputing in JS, unlike
 * RateHistogram's canvas draw loop which has to re-read CSS vars per frame.
 */
export function Sparkline({ values, width = 72, height = 22 }: SparklineProps) {
  if (values.length === 0) {
    return null;
  }
  const max = Math.max(...values, 1);
  const barWidth = width / values.length;

  return (
    <svg width={width} height={height} className="sparkline" role="img" aria-label="Occurrence trend over the last 20 minutes">
      {values.map((value, i) => {
        const barHeight = value > 0 ? Math.max((value / max) * height, 2) : 0;
        return (
          <rect
            key={i}
            x={i * barWidth}
            y={height - barHeight}
            width={Math.max(barWidth - 1, 1)}
            height={barHeight}
            rx={1}
          />
        );
      })}
    </svg>
  );
}
