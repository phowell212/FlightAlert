import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const DEFAULT_TRACE_PROCESSOR = "C:\\Users\\h\\AppData\\Local\\Temp\\flightalert-perfetto-tools\\trace_processor";
const DEFAULT_PYTHON = "C:\\Users\\h\\.cache\\codex-runtimes\\codex-primary-runtime\\dependencies\\python\\python.exe";

const APP_METRICS = [
  "app_frame",
  "flightalert_map",
  "flightalert_traffic",
  "flightalert_aircraft",
  "flightalert_direct",
  "flightalert_state",
  "flightalert_dense_dot_state",
  "flightalert_dense_symbol_state",
  "flightalert_symbol_cache",
  "flightalert_ref_draw",
  "flightalert_ref_prefetch",
];

const RENDER_METRICS = [
  "record_view_draw",
  "draw_vri",
  "post_and_wait",
  "rt_drawframes",
  "rt_flush_commands",
  "gpu_wait",
  "prepare_tree",
];

const TIMELINE_METRICS = [
  "timeline_actual",
  "timeline_present_interval",
  "timeline_app_deadline_missed",
];

const LOG_METRICS = [
  "drawPasses",
  "symbols",
  "dots",
  "mapTiles",
  "loaded",
  "requested",
  "reference",
  "refPlan",
  "refDraw",
  "refPrefetch",
  "directSymbols",
  "directDrawn",
  "directIcon",
  "directCull",
  "symbolMaskPixels",
];

function parseArgs(argv) {
  const args = {
    runDir: "",
    trace: "",
    logcat: "",
    out: "",
    traceProcessor: process.env.FLIGHTALERT_TRACE_PROCESSOR || DEFAULT_TRACE_PROCESSOR,
    python: process.env.FLIGHTALERT_PYTHON || DEFAULT_PYTHON,
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--run-dir") args.runDir = path.resolve(argv[++i]);
    else if (arg === "--trace") args.trace = path.resolve(argv[++i]);
    else if (arg === "--logcat") args.logcat = path.resolve(argv[++i]);
    else if (arg === "--out") args.out = path.resolve(argv[++i]);
    else if (arg === "--trace-processor") args.traceProcessor = path.resolve(argv[++i]);
    else if (arg === "--python") args.python = path.resolve(argv[++i]);
    else if (arg === "--help" || arg === "-h") {
      printUsage();
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  if (!args.runDir && !args.trace) throw new Error("Pass --run-dir or --trace.");
  if (!args.runDir && !args.out) throw new Error("Pass --out when analyzing a trace outside a run directory.");
  if (args.runDir) {
    args.trace ||= newestFile(args.runDir, /\.perfetto-trace$/i);
    args.logcat ||= newestFile(args.runDir, /-logcat\.txt$/i);
    args.out ||= path.join(args.runDir, "frame-correlation-summary.json");
  }
  if (!args.trace) throw new Error(`No .perfetto-trace found${args.runDir ? ` in ${args.runDir}` : ""}.`);
  if (!fs.existsSync(args.trace)) throw new Error(`Trace not found: ${args.trace}`);
  if (!args.logcat || !fs.existsSync(args.logcat)) {
    throw new Error(`Logcat artifact not found. Pass --logcat or use --run-dir with a *-logcat.txt file.`);
  }
  if (!fs.existsSync(args.traceProcessor)) throw new Error(`Trace processor wrapper not found: ${args.traceProcessor}`);
  if (!fs.existsSync(args.python)) throw new Error(`Python executable not found: ${args.python}`);
  return args;
}

function printUsage() {
  console.log(`Usage:
  node tools/perf/AnalyzeFrameCorrelation.mjs --run-dir tools/perf/out/<run>

Optional:
  --trace <trace.perfetto-trace>
  --logcat <run-logcat.txt>
  --out <frame-correlation-summary.json>
  --trace-processor <path-to-perfetto-trace_processor-wrapper>
  --python <python.exe>`);
}

function newestFile(dir, pattern) {
  if (!fs.existsSync(dir)) return "";
  const files = fs.readdirSync(dir)
    .filter((name) => pattern.test(name))
    .map((name) => {
      const full = path.join(dir, name);
      return { full, mtimeMs: fs.statSync(full).mtimeMs };
    })
    .sort((a, b) => b.mtimeMs - a.mtimeMs);
  return files[0]?.full || "";
}

function parseLogcat(logcatPath) {
  const text = fs.readFileSync(logcatPath, "utf8");
  const ranges = [];
  let appPid = 0;
  let lastRange = null;
  for (const line of text.split(/\r?\n/)) {
    if (!line.includes("FlightAlert:")) continue;
    const pidMatch = line.match(/^\d\d-\d\d\s+\S+\s+(\d+)\s+\d+\s+[A-Z]\s+FlightAlert:/);
    if (pidMatch) appPid = Number(pidMatch[1]);
    const marker = "Debug draw perf";
    const markerAt = line.indexOf(marker);
    if (markerAt < 0) continue;
    const body = line.slice(markerAt + marker.length).trim();
    const tokens = parsePerfTokens(body);
    if (tokens.detailBlock === "traffic") {
      if (lastRange) {
        for (const [key, value] of Object.entries(tokens)) {
          if (key !== "frames" && key !== "detailBlock") lastRange[`traffic_${key}`] = value;
        }
      }
      continue;
    }
    const first = numberValue(tokens.drawSeqFirst);
    const last = numberValue(tokens.drawSeq);
    if (!Number.isFinite(first) || !Number.isFinite(last) || first <= 0 || last < first) continue;
    lastRange = {
      firstSeq: first,
      lastSeq: last,
      rawFrames: numberValue(tokens.frames),
      rawTimestamp: line.slice(0, 18).trim(),
      ...tokens,
    };
    ranges.push(lastRange);
  }
  return { appPid, ranges };
}

function parsePerfTokens(text) {
  const tokens = {};
  for (const part of text.split(/\s+/)) {
    const eq = part.indexOf("=");
    if (eq <= 0) continue;
    const key = part.slice(0, eq).replace(/[^A-Za-z0-9_]/g, "");
    let value = part.slice(eq + 1).replace(/[,;]+$/g, "");
    if (!key) continue;
    if (value.endsWith("ms")) value = value.slice(0, -2);
    tokens[key] = value;
  }
  return tokens;
}

function numberValue(value) {
  if (value === null || value === undefined || value === "") return NaN;
  const text = String(value).replace(/[^0-9.+-]/g, "");
  if (!text) return NaN;
  return Number(text);
}

function traceQuery(args, sql) {
  const result = spawnSync(args.python, [args.traceProcessor, "query", args.trace, sql], {
    cwd: args.runDir || process.cwd(),
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`trace_processor query failed (${result.status}):\n${result.stdout}\n${result.stderr}`);
  }
  return parseCsvOutput(result.stdout);
}

function parseCsvOutput(output) {
  const rows = [];
  let header = null;
  for (const rawLine of output.replace(/\r/g, "\n").split(/\n/)) {
    const line = rawLine.trim();
    if (!line) continue;
    if (line.startsWith("Loading trace:") || line.startsWith("Trace ") || line.startsWith("[") || line.startsWith("column ")) {
      continue;
    }
    if (!line.includes(",")) continue;
    const cells = parseCsvLine(line);
    if (!header) {
      header = cells;
      continue;
    }
    if (cells.length !== header.length) continue;
    const row = {};
    for (let i = 0; i < header.length; i++) row[header[i]] = cells[i] === "[NULL]" ? "" : cells[i];
    rows.push(row);
  }
  return rows;
}

function parseCsvLine(line) {
  const cells = [];
  let current = "";
  let quoted = false;
  for (let i = 0; i < line.length; i++) {
    const char = line[i];
    if (quoted) {
      if (char === '"' && line[i + 1] === '"') {
        current += '"';
        i++;
      } else if (char === '"') {
        quoted = false;
      } else {
        current += char;
      }
    } else if (char === '"') {
      quoted = true;
    } else if (char === ",") {
      cells.push(current);
      current = "";
    } else {
      current += char;
    }
  }
  cells.push(current);
  return cells;
}

function frameSql(appPid) {
  const pidFilter = appPid > 0 ? `p.pid = ${appPid}` : "1 = 1";
  return `
with
frame_base as (
  select
    id,
    ts,
    dur,
    cast(substr(name, length('FlightAlert.frame.') + 1) as int) as draw_seq
  from slice
  where name glob 'FlightAlert.frame.*'
),
frames as (
  select
    id,
    ts,
    dur,
    ts + dur as draw_end_ts,
    draw_seq,
    coalesce(lead(ts) over (order by ts), ts + dur) as next_ts
  from frame_base
),
app_metrics as (
  select
    f.draw_seq,
    case s.name
      when 'FlightAlert.map' then 'flightalert_map'
      when 'FlightAlert.traffic' then 'flightalert_traffic'
      when 'FlightAlert.aircraft' then 'flightalert_aircraft'
      when 'FlightAlert.directSymbols' then 'flightalert_direct'
      when 'FlightAlert.trafficState' then 'flightalert_state'
      when 'FlightAlert.denseDotState' then 'flightalert_dense_dot_state'
      when 'FlightAlert.denseSymbolState' then 'flightalert_dense_symbol_state'
      when 'FlightAlert.symbolCache' then 'flightalert_symbol_cache'
      when 'FlightAlert.refDraw' then 'flightalert_ref_draw'
      when 'FlightAlert.refPrefetch' then 'flightalert_ref_prefetch'
      else s.name
    end as metric,
    sum((min(s.ts + s.dur, f.draw_end_ts) - max(s.ts, f.ts)) / 1000000.0) as ms,
    count(distinct s.id) as slice_count
  from frames f
  join slice s
    on s.ts < f.draw_end_ts
   and s.ts + s.dur > f.ts
   and s.name in (
     'FlightAlert.map',
     'FlightAlert.traffic',
     'FlightAlert.aircraft',
     'FlightAlert.directSymbols',
     'FlightAlert.trafficState',
     'FlightAlert.denseDotState',
     'FlightAlert.denseSymbolState',
     'FlightAlert.symbolCache',
     'FlightAlert.refDraw',
     'FlightAlert.refPrefetch'
   )
  group by f.draw_seq, metric
),
render_metrics as (
  select
    f.draw_seq,
    case
      when s.name = 'Record View#draw()' then 'record_view_draw'
      when s.name glob 'draw-VRI*' then 'draw_vri'
      when s.name = 'postAndWait' then 'post_and_wait'
      when th.name = 'RenderThread' and s.name glob 'DrawFrames *' then 'rt_drawframes'
      when th.name = 'RenderThread' and s.name = 'flush commands' then 'rt_flush_commands'
      when th.name = 'GPU completion' and s.name glob 'waiting for GPU completion *' then 'gpu_wait'
      when s.name = 'prepareTree' then 'prepare_tree'
      else s.name
    end as metric,
    sum((min(s.ts + s.dur, f.next_ts) - max(s.ts, f.ts)) / 1000000.0) as ms,
    count(distinct s.id) as slice_count
  from frames f
  join slice s
    on s.ts < f.next_ts
   and s.ts + s.dur > f.ts
  join thread_track tt on s.track_id = tt.id
  join thread th on tt.utid = th.utid
  left join process p on th.upid = p.upid
  where ${pidFilter}
    and (
      s.name = 'Record View#draw()'
      or s.name glob 'draw-VRI*'
      or s.name = 'postAndWait'
      or (th.name = 'RenderThread' and s.name glob 'DrawFrames *')
      or (th.name = 'RenderThread' and s.name = 'flush commands')
      or (th.name = 'GPU completion' and s.name glob 'waiting for GPU completion *')
      or s.name = 'prepareTree'
  )
  group by f.draw_seq, metric
),
app_process as (
  select upid
  from process
  where ${appPid > 0 ? `pid = ${appPid}` : "upid is not null"}
  limit 1
),
actuals as (
  select
    a.*,
    (lead(a.ts) over (order by a.ts) - a.ts) / 1000000.0 as present_interval_ms
  from actual_frame_timeline_slice a
  where a.upid = (select upid from app_process)
),
timeline_candidates as (
  select
    f.draw_seq,
    a.dur / 1000000.0 as actual_ms,
    a.present_interval_ms,
    case when a.jank_type like '%App Deadline Missed%' then 1.0 else 0.0 end as app_deadline_missed,
    (min(a.ts + a.dur, f.next_ts) - max(a.ts, f.ts)) as overlap_ns,
    row_number() over (
      partition by f.draw_seq
      order by (min(a.ts + a.dur, f.next_ts) - max(a.ts, f.ts)) desc, a.dur desc
    ) as rn
  from frames f
  join actuals a
    on a.ts < f.next_ts
   and a.ts + a.dur > f.ts
),
timeline_metrics as (
  select draw_seq, 'timeline_actual' as metric, actual_ms as ms, 1 as slice_count
  from timeline_candidates
  where rn = 1
  union all
  select draw_seq, 'timeline_present_interval' as metric, present_interval_ms as ms, 1 as slice_count
  from timeline_candidates
  where rn = 1 and present_interval_ms is not null
  union all
  select draw_seq, 'timeline_app_deadline_missed' as metric, app_deadline_missed as ms, 1 as slice_count
  from timeline_candidates
  where rn = 1
)
select draw_seq, 'app_frame' as metric, dur / 1000000.0 as ms, 1 as slice_count
from frames
union all
select draw_seq, metric, ms, slice_count from app_metrics
union all
select draw_seq, metric, ms, slice_count from render_metrics
union all
select draw_seq, metric, ms, slice_count from timeline_metrics
order by draw_seq, metric`;
}

function pivotTraceRows(rows, logRanges) {
  const bySeq = new Map();
  for (const row of rows) {
    const seq = Number(row.draw_seq);
    const ms = Number(row.ms);
    if (!Number.isFinite(seq) || !Number.isFinite(ms)) continue;
    if (!bySeq.has(seq)) bySeq.set(seq, { drawSeq: seq });
    bySeq.get(seq)[row.metric] = ms;
    bySeq.get(seq)[`${row.metric}_count`] = Number(row.slice_count) || 0;
  }
  const sortedRanges = [...logRanges].sort((a, b) => a.firstSeq - b.firstSeq);
  for (const frame of bySeq.values()) {
    const range = sortedRanges.find((item) => frame.drawSeq >= item.firstSeq && frame.drawSeq <= item.lastSeq);
    if (!range) continue;
    frame.logRangeFirstSeq = range.firstSeq;
    frame.logRangeLastSeq = range.lastSeq;
    for (const key of LOG_METRICS) {
      const value = numberValue(range[key] ?? range[`traffic_${key}`]);
      if (Number.isFinite(value)) frame[`log_${key}`] = value;
    }
  }
  return [...bySeq.values()].sort((a, b) => a.drawSeq - b.drawSeq);
}

function spearman(xs, ys) {
  const pairs = xs.map((x, i) => [x, ys[i]])
    .filter(([x, y]) => Number.isFinite(x) && Number.isFinite(y));
  if (pairs.length < 6) return { n: pairs.length, rho: null };
  const rx = ranks(pairs.map((p) => p[0]));
  const ry = ranks(pairs.map((p) => p[1]));
  return { n: pairs.length, rho: pearson(rx, ry) };
}

function ranks(values) {
  const sorted = values.map((value, index) => ({ value, index }))
    .sort((a, b) => a.value - b.value);
  const out = Array(values.length);
  for (let i = 0; i < sorted.length;) {
    let j = i + 1;
    while (j < sorted.length && sorted[j].value === sorted[i].value) j++;
    const rank = (i + 1 + j) / 2;
    for (let k = i; k < j; k++) out[sorted[k].index] = rank;
    i = j;
  }
  return out;
}

function pearson(xs, ys) {
  const n = xs.length;
  const avgX = xs.reduce((sum, value) => sum + value, 0) / n;
  const avgY = ys.reduce((sum, value) => sum + value, 0) / n;
  let num = 0;
  let denX = 0;
  let denY = 0;
  for (let i = 0; i < n; i++) {
    const dx = xs[i] - avgX;
    const dy = ys[i] - avgY;
    num += dx * dy;
    denX += dx * dx;
    denY += dy * dy;
  }
  const den = Math.sqrt(denX * denY);
  return den > 0 ? num / den : null;
}

function quartileDelta(frames, sourceMetric, targetMetric) {
  const pairs = frames.map((frame) => ({
    source: frame[sourceMetric],
    target: frame[targetMetric],
  })).filter((row) => Number.isFinite(row.source) && Number.isFinite(row.target));
  if (pairs.length < 8) return { n: pairs.length, bottomMean: null, topMean: null, deltaMs: null, deltaPct: null };
  pairs.sort((a, b) => a.source - b.source);
  const q = Math.max(2, Math.floor(pairs.length / 4));
  const bottom = pairs.slice(0, q);
  const top = pairs.slice(-q);
  const bottomMean = average(bottom.map((row) => row.target));
  const topMean = average(top.map((row) => row.target));
  const deltaMs = topMean - bottomMean;
  const deltaPct = bottomMean > 0 ? deltaMs / bottomMean : null;
  return { n: pairs.length, bottomMean, topMean, deltaMs, deltaPct };
}

function average(values) {
  return values.reduce((sum, value) => sum + value, 0) / Math.max(1, values.length);
}

function metricSummary(frames, metric) {
  const values = frames.map((frame) => frame[metric]).filter(Number.isFinite).sort((a, b) => a - b);
  if (values.length === 0) return { n: 0 };
  return {
    n: values.length,
    avgMs: average(values),
    p50Ms: percentile(values, 0.5),
    p95Ms: percentile(values, 0.95),
    maxMs: values[values.length - 1],
  };
}

function percentile(sorted, p) {
  if (sorted.length === 0) return null;
  const index = (sorted.length - 1) * p;
  const lo = Math.floor(index);
  const hi = Math.ceil(index);
  if (lo === hi) return sorted[lo];
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (index - lo);
}

function buildCorrelations(frames) {
  const sources = [
    ...APP_METRICS,
    ...LOG_METRICS.map((key) => `log_${key}`),
  ];
  const targets = [
    "post_and_wait",
    "rt_flush_commands",
    "rt_drawframes",
    "gpu_wait",
    "record_view_draw",
    "draw_vri",
    "timeline_actual",
    "timeline_present_interval",
  ];
  const rows = [];
  for (const source of sources) {
    const sourceValues = frames.map((frame) => frame[source]);
    if (sourceValues.filter(Number.isFinite).length < 6) continue;
    const item = { source };
    for (const target of targets) {
      const targetValues = frames.map((frame) => frame[target]);
      const corr = spearman(sourceValues, targetValues);
      const q = quartileDelta(frames, source, target);
      item[target] = {
        n: corr.n,
        spearman: corr.rho,
        topMinusBottomMs: q.deltaMs,
        topMinusBottomPct: q.deltaPct,
      };
    }
    const post = item.post_and_wait;
    const flush = item.rt_flush_commands;
    item.goCandidate = Boolean(
      post?.spearman !== null &&
      flush?.spearman !== null &&
      post.spearman >= 0.60 &&
      flush.spearman >= 0.60 &&
      (
        post.topMinusBottomMs >= 8 ||
        flush.topMinusBottomMs >= 8 ||
        post.topMinusBottomPct >= 0.25 ||
        flush.topMinusBottomPct >= 0.25
      )
    );
    item.score = Math.max(
      Math.abs(post?.spearman ?? 0),
      Math.abs(flush?.spearman ?? 0),
      Math.abs(item.rt_drawframes?.spearman ?? 0),
      Math.abs(item.gpu_wait?.spearman ?? 0)
    );
    rows.push(item);
  }
  rows.sort((a, b) => Number(b.goCandidate) - Number(a.goCandidate) || b.score - a.score);
  return rows;
}

function writeFrameCsv(frames, csvPath) {
  const traceMetrics = [...APP_METRICS, ...RENDER_METRICS, ...TIMELINE_METRICS];
  const headers = [
    "drawSeq",
    ...traceMetrics.flatMap((metric) => [metric, `${metric}_count`]),
    ...LOG_METRICS.map((key) => `log_${key}`),
  ];
  const lines = [headers.join(",")];
  for (const frame of frames) {
    lines.push(headers.map((key) => csvCell(frame[key])).join(","));
  }
  fs.writeFileSync(csvPath, `${lines.join("\n")}\n`);
}

function csvCell(value) {
  if (value === undefined || value === null) return "";
  const text = typeof value === "number" ? String(Number(value.toFixed(6))) : String(value);
  return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const log = parseLogcat(args.logcat);
  const rows = traceQuery(args, frameSql(log.appPid));
  const frames = pivotTraceRows(rows, log.ranges);
  const metrics = {};
  for (const metric of [...APP_METRICS, ...RENDER_METRICS, ...TIMELINE_METRICS]) {
    metrics[metric] = metricSummary(frames, metric);
  }
  const correlations = buildCorrelations(frames);
  const summary = {
    runDir: args.runDir ? path.relative(process.cwd(), args.runDir).replace(/\\/g, "/") : "",
    traceFile: path.relative(process.cwd(), args.trace).replace(/\\/g, "/"),
    logcatFile: path.relative(process.cwd(), args.logcat).replace(/\\/g, "/"),
    appPid: log.appPid,
    logRanges: log.ranges.length,
    frames: frames.length,
    framesWithLogCounters: frames.filter((frame) => Number.isFinite(frame.logRangeFirstSeq)).length,
    goGate: "GO only when one source has Spearman >= 0.60 with both post_and_wait and rt_flush_commands, and top-quartile source load increases either target by >=8ms or >=25% versus bottom quartile across three comparable captures.",
    metrics,
    correlations,
    goCandidates: correlations.filter((row) => row.goCandidate),
  };
  fs.mkdirSync(path.dirname(args.out), { recursive: true });
  fs.writeFileSync(args.out, `${JSON.stringify(summary, null, 2)}\n`);
  const csvPath = path.join(path.dirname(args.out), "frame-correlation-frames.csv");
  writeFrameCsv(frames, csvPath);
  console.log(JSON.stringify({
    out: args.out,
    csv: csvPath,
    appPid: log.appPid,
    logRanges: log.ranges.length,
    frames: frames.length,
    framesWithLogCounters: summary.framesWithLogCounters,
    goCandidates: summary.goCandidates.map((row) => row.source),
  }, null, 2));
}

main();
