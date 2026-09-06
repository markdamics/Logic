import { useEffect, useRef, useState } from "react";
import type { MouseEventHandler } from "react";
import type { LogEntry, LogLevel } from "../api/types";

interface WindowOption {
  minutes: number;
  label: string;
  bucketMs: number;
}

// 60 buckets in every window - only the bucket width changes, so "count per
// 1s/10s bucket" scales with how far back the window looks without the axis
// ever getting denser or sparser.
const WINDOW_OPTIONS: WindowOption[] = [
  { minutes: 1, label: "1m", bucketMs: 1000 },
  { minutes: 5, label: "5m", bucketMs: 5000 },
  { minutes: 15, label: "15m", bucketMs: 15000 },
];
const BUCKET_COUNT = 60;
const TICK_MS = 1000;
const LEVELS_STACK_ORDER: LogLevel[] = ["DEBUG", "INFO", "WARN", "ERROR"];

interface Bucket {
  start: number;
  counts: Record<LogLevel, number>;
}

function computeBuckets(entries: LogEntry[], windowMs: number, bucketMs: number, now: number): Bucket[] {
  const windowStart = now - windowMs;
  const buckets: Bucket[] = Array.from({ length: BUCKET_COUNT }, (_, i) => ({
    start: windowStart + i * bucketMs,
    counts: { ERROR: 0, WARN: 0, INFO: 0, DEBUG: 0 },
  }));
  for (const entry of entries) {
    const t = new Date(entry.timestamp).getTime();
    if (t < windowStart || t > now) continue;
    const idx = Math.min(BUCKET_COUNT - 1, Math.floor((t - windowStart) / bucketMs));
    buckets[idx].counts[entry.level]++;
  }
  return buckets;
}

function readCssVar(varName: string, fallback: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(varName).trim() || fallback;
}

interface RateHistogramProps {
  entries: LogEntry[];
}

/** Canvas-based, stacked-by-level rate histogram over a rolling window of the live SSE buffer - redraws on a fixed-size bucket grid so it never accumulates DOM nodes or grows memory across a long-running session. */
export function RateHistogram({ entries }: RateHistogramProps) {
  const [windowMinutes, setWindowMinutes] = useState(WINDOW_OPTIONS[1].minutes);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const entriesRef = useRef(entries);
  entriesRef.current = entries;
  const windowOptionRef = useRef(WINDOW_OPTIONS[1]);
  windowOptionRef.current = WINDOW_OPTIONS.find((w) => w.minutes === windowMinutes) ?? WINDOW_OPTIONS[1];
  const [hover, setHover] = useState<{ x: number; y: number; align: "left" | "center" | "right"; bucket: Bucket } | null>(null);
  const lastBucketsRef = useRef<Bucket[]>([]);

  useEffect(() => {
    const canvas = canvasRef.current;
    const container = containerRef.current;
    if (!canvas || !container) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const draw = () => {
      // Re-read on every frame (cheap, ticks once a second) rather than once at
      // mount, so switching the app's theme while a live view is open doesn't
      // leave the chart's colors stuck on whatever theme was active on mount.
      const colors: Record<LogLevel, string> = {
        ERROR: readCssVar("--color-error", "#E4714D"),
        WARN: readCssVar("--color-warning", "#C4963F"),
        INFO: readCssVar("--color-primary", "#8CBFE4"),
        DEBUG: readCssVar("--color-text-muted", "#8794A4"),
      };
      const gridColor = readCssVar("--color-border", "rgba(46, 59, 76, .6)");
      const textColor = readCssVar("--color-text-muted", "#8794A4");
      const fontFamily = readCssVar("--font-mono", "monospace");

      const dpr = window.devicePixelRatio || 1;
      const width = container.clientWidth;
      const height = 140;
      canvas.width = width * dpr;
      canvas.height = height * dpr;
      canvas.style.width = `${width}px`;
      canvas.style.height = `${height}px`;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, width, height);

      const { bucketMs } = windowOptionRef.current;
      const windowMs = windowOptionRef.current.minutes * 60_000;
      const now = Date.now();
      const buckets = computeBuckets(entriesRef.current, windowMs, bucketMs, now);
      lastBucketsRef.current = buckets;

      const axisH = 16;
      const plotH = height - axisH;
      const maxTotal = Math.max(1, ...buckets.map((b) => LEVELS_STACK_ORDER.reduce((sum, lvl) => sum + b.counts[lvl], 0)));

      // Baseline + a couple of horizontal guide lines.
      ctx.strokeStyle = gridColor;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(0, plotH + 0.5);
      ctx.lineTo(width, plotH + 0.5);
      ctx.stroke();

      const barGap = 1;
      const barWidth = Math.max(1, width / BUCKET_COUNT - barGap);
      buckets.forEach((bucket, i) => {
        const x = i * (width / BUCKET_COUNT);
        let y = plotH;
        for (const level of LEVELS_STACK_ORDER) {
          const count = bucket.counts[level];
          if (count === 0) continue;
          const segH = (count / maxTotal) * (plotH - 4);
          ctx.fillStyle = colors[level];
          ctx.fillRect(x, y - segH, barWidth, segH);
          y -= segH;
        }
      });

      // Time-axis labels: window start and "now".
      ctx.fillStyle = textColor;
      ctx.font = `10px ${fontFamily}`;
      ctx.textBaseline = "top";
      const fmt = (ms: number) => new Date(ms).toLocaleTimeString([], { hour12: false });
      ctx.textAlign = "left";
      ctx.fillText(fmt(now - windowMs), 2, plotH + 3);
      ctx.textAlign = "right";
      ctx.fillText(fmt(now), width - 2, plotH + 3);
    };

    draw();
    const interval = setInterval(draw, TICK_MS);
    const resizeObserver = new ResizeObserver(draw);
    resizeObserver.observe(container);

    return () => {
      clearInterval(interval);
      resizeObserver.disconnect();
    };
    // windowMinutes is read via windowOptionRef so the draw loop always sees
    // the latest selection without tearing down/rebuilding the interval.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleMouseMove: MouseEventHandler<HTMLCanvasElement> = (e) => {
    const buckets = lastBucketsRef.current;
    if (buckets.length === 0) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const idx = Math.min(buckets.length - 1, Math.max(0, Math.floor((x / rect.width) * buckets.length)));
    // Anchor the tooltip inward near either edge instead of centering, so it
    // never overflows past the panel and gets clipped by the shell's overflow.
    const edgeMargin = 70;
    const align = x < edgeMargin ? "left" : x > rect.width - edgeMargin ? "right" : "center";
    setHover({ x, y: e.clientY - rect.top, align, bucket: buckets[idx] });
  };

  return (
    <div className="rate-histogram" ref={containerRef}>
      <div className="rate-histogram-header">
        <span className="log-presets-label">Live rate</span>
        <div className="rate-histogram-legend">
          {LEVELS_STACK_ORDER.slice().reverse().map((level) => (
            <span key={level} className={`rate-histogram-legend-item level-${level.toLowerCase()}`}>
              <span className="rate-histogram-legend-swatch" />
              {level}
            </span>
          ))}
        </div>
        <select
          className="input rate-histogram-window"
          value={windowMinutes}
          onChange={(e) => setWindowMinutes(Number(e.target.value))}
        >
          {WINDOW_OPTIONS.map((opt) => (
            <option key={opt.minutes} value={opt.minutes}>
              Last {opt.label}
            </option>
          ))}
        </select>
      </div>
      <div className="rate-histogram-canvas-wrap">
        <canvas
          ref={canvasRef}
          onMouseMove={handleMouseMove}
          onMouseLeave={() => setHover(null)}
        />
        {hover && (
          <div
            className={`rate-histogram-tooltip rate-histogram-tooltip-${hover.align}`}
            style={{ left: hover.x, top: hover.y }}
          >
            <div className="rate-histogram-tooltip-time">
              {new Date(hover.bucket.start).toLocaleTimeString([], { hour12: false })}
            </div>
            {LEVELS_STACK_ORDER.slice().reverse().map((level) => (
              hover.bucket.counts[level] > 0 && (
                <div key={level} className={`rate-histogram-tooltip-row level-${level.toLowerCase()}`}>
                  {level}: {hover.bucket.counts[level]}
                </div>
              )
            ))}
            {LEVELS_STACK_ORDER.every((l) => hover.bucket.counts[l] === 0) && (
              <div className="rate-histogram-tooltip-row">No entries</div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
