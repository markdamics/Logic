export interface BarChartItem {
  key: string;
  label: string;
  value: number;
  title?: string;
}

interface BarChartProps {
  items: BarChartItem[];
  emptyMessage?: string;
}

/** A simple vertical bar chart — extracted from Dashboard's "Errors by file" panel so it can also render query-bar aggregation results. */
export function BarChart({ items, emptyMessage = "No data." }: BarChartProps) {
  if (items.length === 0) {
    return <div className="log-empty">{emptyMessage}</div>;
  }
  const max = Math.max(...items.map((item) => item.value), 1);

  // The default gap (tuned for a handful of dashboard columns) would make
  // the mandatory gap space alone exceed the container's width once there
  // are enough items (e.g. a 144-bucket time series), collapsing every
  // column to 0px — shrink it as the item count grows.
  const gap = items.length > 40 ? 1 : items.length > 15 ? 4 : 14;
  // Full text under every single bar is illegible past a handful of items,
  // so only label every Nth bar (capped around a dozen visible labels);
  // every bar still gets its full detail on hover via `title`.
  const labelStride = Math.max(1, Math.ceil(items.length / 12));

  return (
    <div className="bar-chart" style={{ gap: `${gap}px` }}>
      {items.map((item, i) => (
        <div className="bar-chart-col" key={item.key}>
          <div className="bar-chart-plot">
            <div
              className="bar-chart-bar"
              style={{ height: `${(item.value / max) * 100}%` }}
              title={item.title ?? `${item.label}: ${item.value}`}
            />
          </div>
          <div
            className="bar-chart-label"
            title={item.title ?? item.label}
            // Shown labels are wider than their own narrow column once there
            // are many bars, so let them spill over neighboring (unlabeled)
            // columns instead of ellipsis-clipping to nothing.
            style={i % labelStride === 0 ? { overflow: "visible", position: "relative", zIndex: 1 } : undefined}
          >
            {i % labelStride === 0 ? item.label : ""}
          </div>
        </div>
      ))}
    </div>
  );
}
