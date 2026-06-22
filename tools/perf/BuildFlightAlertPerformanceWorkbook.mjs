import fs from "node:fs/promises";
import path from "node:path";
import { spawn } from "node:child_process";

const ROOT = process.argv.includes("--root")
  ? path.resolve(process.argv[process.argv.indexOf("--root") + 1])
  : "C:\\Users\\h\\AndroidStudioProjects\\FlightAlert";
const PERF_OUT = path.join(ROOT, "tools", "perf", "out");
const AGENTS_PATH = path.join(ROOT, "AGENTS.md");
const CANONICAL_WORKBOOK_PATH = path.join(ROOT, "docs", "flightalert-performance-metrics.xlsx");
const WORKBOOK_PATH = process.argv.includes("--output")
  ? path.resolve(process.argv[process.argv.indexOf("--output") + 1])
  : CANONICAL_WORKBOOK_PATH;
const AUDIT_RAW_SNIPPET_MAX_CHARS = 600;
const PYTHON = process.env.FLIGHTALERT_PYTHON
  || "C:\\Users\\h\\.cache\\codex-runtimes\\codex-primary-runtime\\dependencies\\python\\python.exe";
const PREVIEW_DIR = path.join(
  process.env.TEMP || path.join(ROOT, "build", "tmp"),
  "flightalert-performance-workbook-preview"
);
const CONTROLLED_THERMAL_STATUS = 0;
const CONTROLLED_PACKAGE_DEXOPT_STATE_DEFAULT = "verify/install-speg";
const CONTROLLED_PACKAGE_DEXOPT_STATE_RESET_INSTALL = "verify/install";
const CONTROLLED_DEXOPT_NORMALIZATION_MODE_NONE = "none";
const CONTROLLED_DEXOPT_NORMALIZATION_MODE_POST_INSTALL_RESET = "post_install_reset_v1";
const CONTROLLED_ART_COMPILE_MODE = "InstallDefault";

const CHART_EXCLUSION_PATTERNS = [
  ["dirty/unaccepted run", /dirty|unaccepted|uncommitted/i],
  ["rejected/reverted experiment", /refparentfast|rectreuse|rejected[-_]?ref|rejected[-_]?reference[-_]?fallback|reverted/i],
  ["skip/layer isolation", /skiptraffic|skipchrome|skipcontrols|skiptopstatus|skiptrafficpanel|layeriso|maponly/i],
  ["trace/correlation diagnostic", /perfetto|atrace|framecorr|framemetrics|tracehook/i],
  ["diagnostic instrumentation", /diagnostic|diag|compileab|compilediag|artcompile|manualmatrix|densesymseen|sourcediag|symbolmiss|directcount|directsubphase|directicon|framefields|refpfcounters|refpfcpu|refpfphase|refpfkind|refpfqueuedgen|breakdown/i],
  ["video/visual-evidence run", /(?:^|[\\/_-])(?:video|roadmotion|motionstrip)(?:[\\/_-]|$)/i],
  ["sample-only run", /directionsample|sample\b/i],
];

const DETAIL_KEYS = [
  "frames",
  "detailBlock",
  "avg",
  "max",
  "map",
  "traffic",
  "chrome",
  "last",
  "lastMap",
  "lastTraffic",
  "drawSeqFirst",
  "drawSeq",
  "drawStartMs",
  "drawStartNs",
  "drawEndNs",
  "drawIntervalMs",
  "drawPasses",
  "sameCameraLast",
  "symbols",
  "dots",
  "stateBuild",
  "dotState",
  "symOverlay",
  "symMode",
  "symBuild",
  "symShift",
  "symSourceSample",
  "symSourceDetail",
  "symQuery",
  "symFilter",
  "symGrid",
  "symCache",
  "symQueryCount",
  "symSeenTrack",
  "symAccepted",
  "tickerPosts",
  "iOnly",
  "sameCameraTraffic",
  "sameCameraTrafficAvg",
  "mapTiles",
  "loaded",
  "requested",
  "fallback",
  "satLod",
  "lodAlpha",
  "reference",
  "refPlan",
  "refDraw",
  "refPrefetch",
  "refAlpha",
  "refBook",
  "refGen",
  "refProtect",
  "refOther",
  "refOvl",
  "refPf",
  "refPfCpu",
  "refPfWait",
  "refPfCpuMiss",
  "refPfRange",
  "refPfEnum",
  "refPfMemLookup",
  "refPfUrl",
  "refPfSubmitNs",
  "refPfTiles",
  "refPfMem",
  "refPfMiss",
  "refPfSubmit",
  "refPfQueued",
  "refPfQSame",
  "refPfQRecent",
  "refPfLod",
  "refPfPan",
  "refPfLodTiles",
  "refPfPanTiles",
  "refPfLodSub",
  "refPfPanSub",
  "refPfLodQ",
  "refPfPanQ",
  "refPfLodMaxGrid",
  "refPfPanMaxGrid",
  "refPfMem2",
  "refPfDeny",
  "refPfMaxGrid",
  "refPfAsyncOffer",
  "refPfAsyncCoalesce",
  "refPfAsyncBatch",
  "refPfAsyncPlan",
  "refPfAsyncTiles",
  "refPfAsyncMem",
  "refPfAsyncMiss",
  "refPfAsyncSubmit",
  "refPfAsyncQueued",
  "refPfAsyncMem2",
  "refPfAsyncStale",
  "refPfAsyncSuper",
  "refPfAsyncDeny",
  "refPfAsyncNs",
  "refPfAsyncMaxNs",
  "symbolCache",
  "reason",
  "direct",
  "cacheAttempt",
  "directSymbols",
  "directCalls",
  "directPartial",
  "directInput",
  "directExcluded",
  "directOffscreen",
  "directDrawn",
  "directCull",
  "directIcon",
  "directGeometry",
  "directCanvasSubmit",
  "directSymbolFg",
  "directRotorFg",
  "directDotIcon",
  "directSymbolCount",
  "directRotorCount",
  "directSurfaceCount",
  "directDotCount",
  "directMatrix",
  "directCanvasCalls",
  "directIconSampPhase",
  "directIconSamp",
  "directIconSampNonRotor",
  "directIconSampTotal",
  "directIconSampPrep",
  "directIconSampShadow",
  "directIconSampSelection",
  "directIconSampMatrix",
  "directIconSampFill",
  "directIconSampStroke",
  "directIconSampRotor",
  "directIconSampOther",
  "directIconSampShadowCount",
  "directIconSampSelectionCount",
  "directIconSampMatrixCount",
  "directIconSampFillCount",
  "directIconSampStrokeCount",
  "directIconSampRotorCount",
  "directIconSampZeroAlpha",
  "directIconSampFillAlphaZero",
  "directIconSampStrokeAlphaZero",
  "directIconSampEmptyFill",
  "directIconSampEmptyStroke",
  "directIconSampMaskPixels",
  "symbolStyle",
  "symbolShadow",
  "maskSample",
  "symbolMask",
  "symbolMaskAcquire",
  "symbolMaskCreate",
  "symbolMaskCanvas",
  "symbolMaskRaster",
  "symbolMaskPrepare",
  "symbolMaskSetup",
  "symbolMaskFill",
  "symbolMaskStroke",
  "symbolMaskComposite",
  "symbolMaskDraws",
  "symbolMaskPixels",
  "symbolMaskMiss",
  "symbolMaskGenPixels",
  "directLabels",
  "labels",
  "labelsDrawn",
  "fmCallbacks",
  "fmJoined",
  "fmMissed",
  "fmDropped",
  "fmDraw",
  "fmSync",
  "fmCommandIssue",
  "fmSwapBuffers",
  "fmGpu",
  "fmTotal",
  "fmMaxTotal",
  "fmMaxCommandIssue",
  "fmMaxGpu",
  "fmDirectSymbols",
  "fmDirectIcon",
  "fmDirectDrawn",
  "fmMaxDirectSymbols",
  "fmSamples",
  "fmDirectCmdR",
  "fmDirectGpuR",
  "fmIconCmdR",
  "fmIconGpuR",
  "fmDirectCmdRho",
  "fmDirectGpuRho",
  "fmIconCmdRho",
  "fmIconGpuRho",
  "fmCmdSlowDirectLift",
  "fmGpuSlowDirectLift",
  "fmCmdSlowIconLift",
  "fmGpuSlowIconLift",
  "centerLat",
  "centerLon",
  "focusLat",
  "focusLon",
  "targetLat",
  "targetLon",
  "zoom",
];

const SOURCE_DETAIL_KEYS = [
  "srcCur",
  "srcCached",
  "srcSame",
  "srcAdd",
  "srcDrop",
  "srcPos",
  "srcMove",
  "srcFresh",
  "srcRef",
  "srcIdxRef",
  "srcMaxLatE6",
  "srcMaxLonE6",
  "srcBound",
  "srcCurS",
  "srcCachedS",
  "srcCurStep",
  "srcCachedStep",
];

function safeText(value, max = 2000) {
  if (value == null) return "";
  const text = String(value).replace(/\u0000/g, "");
  return text.length > max ? `${text.slice(0, max - 14)} [truncated]` : text;
}

function chunkText(value, size = 12000, chunks = 3) {
  const text = value == null ? "" : String(value).replace(/\u0000/g, "");
  const parts = [];
  for (let i = 0; i < chunks; i++) {
    parts.push(text.slice(i * size, (i + 1) * size));
  }
  if (text.length > size * chunks) {
    parts[chunks - 1] = `${parts[chunks - 1].slice(0, size - 14)} [truncated]`;
  }
  return parts;
}

function parseCsvLine(line) {
  const cells = [];
  let current = "";
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === "\"") {
      if (inQuotes && line[i + 1] === "\"") {
        current += "\"";
        i++;
      } else {
        inQuotes = !inQuotes;
      }
    } else if (ch === "," && !inQuotes) {
      cells.push(current);
      current = "";
    } else {
      current += ch;
    }
  }
  cells.push(current);
  return cells;
}

function maybeNumber(value) {
  if (value == null || value === "") return "";
  const text = String(value).trim();
  const jank = text.match(/Janky frames:\s*\d+\s*\(([0-9.]+)%\)/);
  if (jank) return Number(jank[1]);
  const percent = text.match(/^(-?\d+(?:\.\d+)?)%$/);
  if (percent) return Number(percent[1]);
  const ms = text.match(/^(-?\d+(?:\.\d+)?)ms$/);
  if (ms) return Number(ms[1]);
  if (/^-?\d+(?:\.\d+)?$/.test(text)) return Number(text);
  return text;
}

function normalizeBoolean(value) {
  if (value === true || value === false) return value;
  if (value == null || value === "") return null;
  const text = String(value).trim().toLowerCase();
  if (["true", "1", "yes", "on"].includes(text)) return true;
  if (["false", "0", "no", "off"].includes(text)) return false;
  return null;
}

function isTrueValue(value) {
  return normalizeBoolean(value) === true;
}

function isFalseValue(value) {
  return normalizeBoolean(value) === false;
}

function finiteNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function rounded(value, digits = 2) {
  const number = finiteNumber(value);
  if (number == null) return "";
  const factor = 10 ** digits;
  return Math.round(number * factor) / factor;
}

function percentileValue(values, percentile) {
  if (!values.length) return "";
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.max(0, Math.min(sorted.length - 1, Math.ceil((percentile / 100) * sorted.length) - 1));
  return sorted[index];
}

function meanValue(values) {
  if (!values.length) return "";
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function presentIntervalsMs(presentSeconds) {
  if (presentSeconds.length < 2) return [];
  const sorted = [...new Set(presentSeconds)].sort((a, b) => a - b);
  const intervals = [];
  for (let index = 1; index < sorted.length; index++) {
    const delta = (sorted[index] - sorted[index - 1]) * 1000.0;
    if (delta > 0.0 && delta < 200.0) intervals.push(delta);
  }
  return intervals;
}

function canonicalTestName(testName) {
  return String(testName || "")
    .replace(/^flightalert-perf-/, "")
    .replace(/-framestats\.txt$/i, "")
    .trim();
}

function chartWorkloadLevel(row) {
  const test = canonicalTestName(row.testName);
  if (/^satellitePanZoomSanityPerf$/i.test(test)) return "Satellite Pan/Zoom Sanity";
  if (/^satelliteBenchmarkPanZoomWorkloadPerf$/i.test(test)) return "Satellite Benchmark Pan/Zoom";
  if (/^panAcrossZoomLevels$/i.test(test)) return "Pan Across Zoom Levels";
  if (/^countryScaleZoomContinuitySatellite(?:Perf)?(?:-continent)?$/i.test(test)) return "Satellite Country/Continent Continuity";
  if (/^countryScaleZoomContinuityStreet(?:Perf)?(?:-continent)?$/i.test(test)) return "Street Country/Continent Continuity";
  if (/^wideScaleZoomContinuitySatellite(?:Perf)?$/i.test(test)) return "Satellite Wide-Scale Continuity";
  if (/^wideScaleZoomContinuityStreet(?:Perf)?$/i.test(test)) return "Street Wide-Scale Continuity";
  if (/^closeScaleZoomContinuitySatellite(?:Perf)?$/i.test(test)) return "Satellite Close-Scale Continuity";
  return "";
}

function workbookTestLane(row) {
  const test = canonicalTestName(row.testName);
  if (/^satelliteBenchmarkPanZoomWorkloadPerf$/i.test(test)) return "Standard 60s Satellite Multi-Level Benchmark";
  return "";
}

function parseTargetMotionMs(value) {
  const match = String(value || "").match(/target_motion_ms=(\d+)/i);
  return match ? maybeNumber(match[1]) : "";
}

function benchmarkRegion(row) {
  const city = String(row.city || row.expectedCity || "").toLowerCase();
  if (/london|amsterdam|frankfurt|paris|madrid/.test(city)) return "Europe";
  if (/dallas|fort worth|atlanta|phoenix|las vegas|chicago|new york|los angeles/.test(city)) return "United States";
  return "";
}

function chartDiagnosticReason(row) {
  const text = [
    row.runId,
    row.artifactDir,
    row.testName,
    row.summaryFile,
    row.benchmarkRole,
    row.harnessExecutionMode,
    row.artCompileMode,
  ].filter(Boolean).join(" ");
  for (const [reason, pattern] of CHART_EXCLUSION_PATTERNS) {
    if (pattern.test(text)) return reason;
  }
  return "";
}

function fullVisibilityExclusionReason(row) {
  const flags = [
    ["skip_chrome", row.skipChrome],
    ["skip_top_status", row.skipTopStatus],
    ["skip_controls", row.skipControls],
    ["skip_traffic_panel", row.skipTrafficPanel],
    ["skip_traffic", row.skipTraffic],
  ];
  const enabledSkips = flags.filter(([, value]) => isTrueValue(value)).map(([name]) => name);
  if (enabledSkips.length) return `disabled layer(s): ${enabledSkips.join(", ")}`;
  const unknown = flags.filter(([, value]) => !isFalseValue(value)).map(([name]) => name);
  if (unknown.length) return `full visibility not explicit: ${unknown.join(", ")}`;
  return "";
}

function gitCleanExclusionReason(row) {
  if (!isTrueValue(row.gitMetadataAvailable)) return "git metadata missing/unavailable";
  const dirty = normalizeBoolean(row.gitWorktreeDirty);
  if (dirty == null) return "git worktree cleanliness missing";
  if (!dirty) return "";
  const status = safeText(row.gitStatusShort, 160);
  return status ? `dirty git worktree at run start: ${status}` : "dirty git worktree at run start";
}

function hasAircraftDrawEvidence(evidence) {
  return Boolean(evidence && evidence.hasAircraftDrawEvidence);
}

function aircraftEvidenceLabel(evidence) {
  if (!evidence) return "";
  const directDrawn = evidence.hasDirectDrawnMetric
    ? `drawn=${evidence.maxDirectDrawn || 0}`
    : "drawn=n/a";
  return [
    `symbols=${evidence.maxSymbols || 0}`,
    `dots=${evidence.maxDots || 0}`,
    `direct=${evidence.maxDirect || 0}`,
    directDrawn,
  ].join(" ");
}

function buildAircraftEvidenceByRun(auditRows) {
  const byRun = new Map();
  for (const row of auditRows) {
    const runId = row.runId || "";
    if (!runId) continue;
    const current = byRun.get(runId) || {
      maxSymbols: 0,
      maxDots: 0,
      maxDirect: 0,
      maxDirectDrawn: 0,
      hasDirectDrawnMetric: false,
      hasAircraftDrawEvidence: false,
    };
    current.maxSymbols = Math.max(current.maxSymbols, finiteNumber(row.symbols) || 0);
    current.maxDots = Math.max(current.maxDots, finiteNumber(row.dots) || 0);
    current.maxDirect = Math.max(current.maxDirect, finiteNumber(row.direct) || 0);
    const rawDirectDrawn = row.directDrawn;
    const hasDirectDrawnMetric = rawDirectDrawn !== undefined &&
      rawDirectDrawn !== null &&
      String(rawDirectDrawn).trim() !== "";
    const directDrawn = hasDirectDrawnMetric ? finiteNumber(rawDirectDrawn) : null;
    if (Number.isFinite(directDrawn)) {
      current.maxDirectDrawn = Math.max(current.maxDirectDrawn, directDrawn);
      current.hasDirectDrawnMetric = true;
    }
    current.hasAircraftDrawEvidence = current.maxSymbols > 0 || current.maxDots > 0 || current.maxDirect > 0 || current.maxDirectDrawn > 0;
    byRun.set(runId, current);
  }
  return byRun;
}

function chartExclusionReason(row, workloadLevel, aircraftEvidence) {
  const reasons = [];
  if (!workloadLevel) reasons.push("not a holistic benchmark workload");
  const gitReason = gitCleanExclusionReason(row);
  if (gitReason) reasons.push(gitReason);
  if (!isTrueValue(row.routeFocusPassed)) reasons.push("route proof failed or missing");
  const visibilityReason = fullVisibilityExclusionReason(row);
  if (visibilityReason) reasons.push(visibilityReason);
  if (!hasAircraftDrawEvidence(aircraftEvidence)) reasons.push("no aircraft draw evidence in Debug draw perf");
  if (row.benchmarkRole && !/^workbook$/i.test(String(row.benchmarkRole))) {
    reasons.push(`benchmark role is ${row.benchmarkRole}`);
  }
  if (row.harnessExecutionMode && !isChartControlledHarness(row)) {
    reasons.push(`nonstandard harness execution: ${row.harnessExecutionMode}`);
  }
  reasons.push(...comparisonEnvironmentControlReasons(row));
  if (isTrueValue(row.trafficDetailTiming) || isTrueValue(row.mapDetailTiming)) reasons.push("detail-timing diagnostic run");
  if (isTrueValue(row.recordVideo) || (finiteNumber(row.videosCount) || 0) > 0) reasons.push("video capture run");
  const diagnosticReason = chartDiagnosticReason(row);
  if (diagnosticReason) reasons.push(diagnosticReason);
  for (const [label, value] of [
    ["Produced FPS", row.producedFps],
    ["Present Mean FPS", row.presentMeanFps],
    ["P95 ms", row.p95Ms],
    ["Android Jank %", row.androidJankPct],
  ]) {
    if (finiteNumber(value) == null) reasons.push(`missing ${label}`);
  }
  return Array.from(new Set(reasons)).join("; ");
}

function isChartControlledHarness(row) {
  const mode = String(row.harnessExecutionMode || "").trim().toLowerCase();
  if (!mode || mode === "gradleconnected") return true;
  return mode === "splitinstall" && isTrueValue(row.controlledPreflightPassed);
}

function normalizeDexoptNormalizationMode(value) {
  const mode = String(value || "").trim();
  if (!mode || /^none$/i.test(mode)) return CONTROLLED_DEXOPT_NORMALIZATION_MODE_NONE;
  if (/^(post[-_ ]?install[-_ ]?reset[-_ ]?v?1|postinstallresetv1|resetinstall)$/i.test(mode)) {
    return CONTROLLED_DEXOPT_NORMALIZATION_MODE_POST_INSTALL_RESET;
  }
  return mode;
}

function controlledDexoptNormalizationMode(row) {
  return normalizeDexoptNormalizationMode(row.controlledDexoptNormalizationMode || row.controlledPreflightRepairMode || "");
}

function controlledExpectedDexoptState(row) {
  const recorded = String(row.controlledPreflightExpectedDexopt || "").trim();
  if (recorded) return recorded;
  const normalizationMode = controlledDexoptNormalizationMode(row).toLowerCase();
  if (normalizationMode === CONTROLLED_DEXOPT_NORMALIZATION_MODE_POST_INSTALL_RESET.toLowerCase()) {
    return CONTROLLED_PACKAGE_DEXOPT_STATE_RESET_INSTALL;
  }
  return CONTROLLED_PACKAGE_DEXOPT_STATE_DEFAULT;
}

function controlledDexoptLaneLabel(row) {
  return `${controlledExpectedDexoptState(row)} via ${controlledDexoptNormalizationMode(row)}`;
}

function comparisonEnvironmentControlReasons(row) {
  const reasons = [];
  const thermalStatus = finiteNumber(row.thermalStatus);
  if (thermalStatus == null) {
    reasons.push("missing thermal status control");
  } else if (thermalStatus !== CONTROLLED_THERMAL_STATUS) {
    reasons.push(`thermal status ${row.thermalStatus} != ${CONTROLLED_THERMAL_STATUS}`);
  }

  const normalizationMode = controlledDexoptNormalizationMode(row);
  const normalizationModeKey = normalizationMode.toLowerCase();
  const allowedNormalizationModes = new Set([
    CONTROLLED_DEXOPT_NORMALIZATION_MODE_NONE.toLowerCase(),
    CONTROLLED_DEXOPT_NORMALIZATION_MODE_POST_INSTALL_RESET.toLowerCase(),
  ]);
  if (!allowedNormalizationModes.has(normalizationModeKey)) {
    reasons.push(`unknown controlled dexopt normalization mode ${normalizationMode}`);
  }

  const packageDexoptState = String(row.packageDexoptState || "").trim();
  const expectedDexoptState = controlledExpectedDexoptState(row);
  if (!packageDexoptState) {
    reasons.push("missing package dexopt state");
  } else if (packageDexoptState.toLowerCase() !== expectedDexoptState.toLowerCase()) {
    reasons.push(`package dexopt state ${packageDexoptState} != ${expectedDexoptState} for ${normalizationMode} lane`);
  }

  const postRunPackageDexoptState = String(row.postRunPackageDexoptState || "").trim();
  if (!postRunPackageDexoptState) {
    reasons.push("missing post-run package dexopt recheck");
  } else if (postRunPackageDexoptState.toLowerCase() !== expectedDexoptState.toLowerCase()) {
    reasons.push(`post-run package dexopt state ${postRunPackageDexoptState} != ${expectedDexoptState}`);
  }

  const packageDexoptFingerprint = String(row.packageDexoptFingerprint || "").trim();
  const postRunPackageDexoptFingerprint = String(row.postRunPackageDexoptFingerprint || "").trim();
  if (!packageDexoptFingerprint) {
    reasons.push("missing package dexopt fingerprint");
  } else if (!postRunPackageDexoptFingerprint) {
    reasons.push("missing post-run package dexopt fingerprint");
  } else if (packageDexoptFingerprint.toLowerCase() !== postRunPackageDexoptFingerprint.toLowerCase()) {
    reasons.push("post-run package dexopt fingerprint changed");
  }

  const artCompileMode = String(row.artCompileMode || "").trim();
  if (!artCompileMode) {
    reasons.push("missing ART compile mode");
  } else if (artCompileMode.toLowerCase() !== CONTROLLED_ART_COMPILE_MODE.toLowerCase()) {
    reasons.push(`ART compile mode ${artCompileMode} != ${CONTROLLED_ART_COMPILE_MODE}`);
  }
  return reasons;
}

function withChartEligibility(row, aircraftEvidence = null) {
  const workloadLevel = chartWorkloadLevel(row);
  const exclusionReason = chartExclusionReason(row, workloadLevel, aircraftEvidence);
  const lane = workbookTestLane(row);
  const workbookReasons = [];
  if (exclusionReason) workbookReasons.push(exclusionReason);
  if (!lane) workbookReasons.push("not a standardized workbook-test lane");
  const maxRunSeconds = finiteNumber(row.maxRunSeconds);
  if (maxRunSeconds != null && maxRunSeconds < 60) workbookReasons.push(`run budget below 60s (${maxRunSeconds}s)`);
  const workloadMs = finiteNumber(row.workloadTargetMs);
  if (lane && (workloadMs == null || workloadMs < 55000)) {
    workbookReasons.push(workloadMs == null ? "missing 60s workload target" : `workload target below 55s (${workloadMs}ms)`);
  }
  return {
    ...row,
    chartWorkloadLevel: workloadLevel,
    chartEligible: exclusionReason ? "No" : "Yes",
    chartExclusionReason: exclusionReason,
    aircraftDrawEvidence: aircraftEvidenceLabel(aircraftEvidence),
    workbookTestLane: lane,
    benchmarkRegion: benchmarkRegion(row),
    controlledDexoptNormalizationModeLabel: controlledDexoptNormalizationMode(row),
    controlledExpectedDexoptState: controlledExpectedDexoptState(row),
    controlledDexoptLane: controlledDexoptLaneLabel(row),
    workbookTestEligible: workbookReasons.length ? "No" : "Yes",
    workbookTestExclusionReason: Array.from(new Set(workbookReasons)).join("; "),
  };
}

function compareRunPerformance(a, b) {
  const producedDelta = (finiteNumber(chartProducedFps(b)) || -Infinity) - (finiteNumber(chartProducedFps(a)) || -Infinity);
  if (producedDelta) return producedDelta;
  const presentDelta = (finiteNumber(b.presentMeanFps) || -Infinity) - (finiteNumber(a.presentMeanFps) || -Infinity);
  if (presentDelta) return presentDelta;
  const p95Delta = (finiteNumber(chartP95Ms(a)) || Infinity) - (finiteNumber(chartP95Ms(b)) || Infinity);
  if (p95Delta) return p95Delta;
  const jankDelta = (finiteNumber(a.androidJankPct) || Infinity) - (finiteNumber(b.androidJankPct) || Infinity);
  if (jankDelta) return jankDelta;
  const presentP95Delta = (finiteNumber(a.presentP95Ms) || Infinity) - (finiteNumber(b.presentP95Ms) || Infinity);
  if (presentP95Delta) return presentP95Delta;
  return new Date(b.runDate) - new Date(a.runDate);
}

function comparableSeriesKey(row, includeCity = true) {
  return [
    row.workbookTestLane || "",
    row.harnessExecutionMode || "",
    includeCity ? (row.city || "") : (row.benchmarkRegion || ""),
    row.mapSource || "",
    row.roads || "",
    row.borders || "",
    detailTimingLabel(row.trafficDetailTiming),
    detailTimingLabel(row.mapDetailTiming),
    row.packageDexoptState || "",
    row.packageDexoptFingerprint || "",
    row.artCompileMode || "",
    controlledDexoptNormalizationMode(row),
    controlledExpectedDexoptState(row),
  ].join(" | ");
}

function chartProducedFps(row) {
  return finiteNumber(row.androidProducedFps) ?? row.producedFps ?? "";
}

function chartP95Ms(row) {
  return finiteNumber(row.androidP95Ms) ?? row.p95Ms ?? "";
}

function detailTimingLabel(value) {
  if (isTrueValue(value)) return "On";
  if (isFalseValue(value)) return "Off";
  return "Unknown";
}

function dexoptSummary(text) {
  const match = String(text || "").match(/Dexopt state:.*?arm64:\s*\[status=([^\]]+)\]\s*\[reason=([^\]]+)\]/s);
  return match ? `${match[1]}/${match[2]}` : "";
}

function dexoptFingerprint(text) {
  const entries = [];
  const regex = /([A-Za-z0-9_.-]+):\s*\[status=([^\]]+)\]\s*\[reason=([^\]]+)\]/g;
  for (const match of String(text || "").matchAll(regex)) {
    entries.push(`${match[1]}=${match[2]}/${match[3]}`);
  }
  return entries.sort().join(";");
}

function shortVersionLabel(row) {
  const version = workbookTestVersionLabel(row);
  if (version.includes("one-huge-file")) return "one-huge-file";
  if (version.includes("optimizer-master-exhausted")) return "optbaseline";
  if (version.includes("current-before-refplanlazy")) return "current";
  if (version.includes("rejected-reference-fallback")) return "rejected-ref";
  return safeText(version, 24);
}

function shortChartRunLabel(row) {
  const runId = String(row.runId || "");
  const run = runId.match(/(?:^|-)r(\d+)(?:valid)?(?:$|-)/i)?.[1];
  const suffix = run ? ` r${run}` : "";
  const hash = runId.match(/-([0-9a-f]{7,40})(?:-|$)/i)?.[1]?.slice(0, 7);
  const version = hash ? `${shortVersionLabel(row)} ${hash}` : shortVersionLabel(row);
  const rejected = /rectreuse|refparentfast|rejected/i.test(runId) ? " rejected" : "";
  const restored = /restored/i.test(runId) ? " restored" : "";
  const city = row.city ? ` ${row.city}` : "";
  return safeText(`${version}${rejected}${restored}${suffix}${city}`, 52);
}

function workbookTestVersionLabel(row) {
  const id = String(row.runId || "").toLowerCase();
  if (id.includes("optbaseline")) return "optimizer-master-exhausted-baseline-20260621";
  if (id.includes("onehugefile") || id.includes("onehuge")) return "checkpoint-one-huge-file-20260621-fair-master";
  if (id.includes("refparentfast")) return "rejected-reference-fallback-20260622";
  if (id.includes("current")) return "backup/current-before-refplanlazy-restore-20260622";
  return "unlabeled-comparable-version";
}

function finiteMetricValues(rows, selector) {
  return rows.map(selector).map(finiteNumber).filter((value) => value != null);
}

function averageMetric(rows, selector) {
  const values = finiteMetricValues(rows, selector);
  return values.length ? rounded(values.reduce((sum, value) => sum + value, 0) / values.length, 2) : "";
}

function buildVersionSummary(workbookTestRows) {
  const groups = new Map();
  for (const row of workbookTestRows) {
    const version = workbookTestVersionLabel(row);
    const key = [version, comparableSeriesKey(row, true)].join(" | ");
    if (!groups.has(key)) {
      groups.set(key, {
        version,
        workbookTestLane: row.workbookTestLane,
        harnessExecutionMode: row.harnessExecutionMode,
        benchmarkRegion: row.benchmarkRegion,
        city: row.city,
        mapSource: row.mapSource,
        roads: row.roads,
        borders: row.borders,
        trafficDetailTiming: detailTimingLabel(row.trafficDetailTiming),
        mapDetailTiming: detailTimingLabel(row.mapDetailTiming),
        packageDexoptState: row.packageDexoptState,
        packageDexoptFingerprint: row.packageDexoptFingerprint,
        artCompileMode: row.artCompileMode,
        controlledDexoptNormalizationMode: row.controlledDexoptNormalizationModeLabel,
        controlledExpectedDexoptState: row.controlledExpectedDexoptState,
        rows: [],
      });
    }
    groups.get(key).rows.push(row);
  }
  return Array.from(groups.values()).sort(compareVersionSummaryForDisplay);
}

function compareVersionSummaryPerformance(a, b) {
  const producedDelta = (finiteNumber(averageMetric(b.rows, chartProducedFps)) ?? -Infinity) - (finiteNumber(averageMetric(a.rows, chartProducedFps)) ?? -Infinity);
  if (producedDelta) return producedDelta;
  const presentDelta = (finiteNumber(averageMetric(b.rows, (row) => row.presentMeanFps)) ?? -Infinity) - (finiteNumber(averageMetric(a.rows, (row) => row.presentMeanFps)) ?? -Infinity);
  if (presentDelta) return presentDelta;
  const p95Delta = (finiteNumber(averageMetric(a.rows, chartP95Ms)) ?? Infinity) - (finiteNumber(averageMetric(b.rows, chartP95Ms)) ?? Infinity);
  if (p95Delta) return p95Delta;
  const jankDelta = (finiteNumber(averageMetric(a.rows, (row) => row.androidJankPct)) ?? Infinity) - (finiteNumber(averageMetric(b.rows, (row) => row.androidJankPct)) ?? Infinity);
  if (jankDelta) return jankDelta;
  const runCountDelta = (b.rows?.length || 0) - (a.rows?.length || 0);
  if (runCountDelta) return runCountDelta;
  return String(a.version || "").localeCompare(String(b.version || ""));
}

function compareVersionSummaryForDisplay(a, b) {
  const byLane = String(a.workbookTestLane).localeCompare(String(b.workbookTestLane));
  if (byLane) return byLane;
  const byRegion = String(a.benchmarkRegion).localeCompare(String(b.benchmarkRegion));
  if (byRegion) return byRegion;
  const byCity = String(a.city).localeCompare(String(b.city));
  if (byCity) return byCity;
  return compareVersionSummaryPerformance(a, b);
}

async function exists(filePath) {
  try {
    await fs.access(filePath);
    return true;
  } catch {
    return false;
  }
}

async function listFiles(dir) {
  try {
    const entries = await fs.readdir(dir, { withFileTypes: true });
    return entries.filter((entry) => entry.isFile()).map((entry) => path.join(dir, entry.name));
  } catch {
    return [];
  }
}

async function listDirs(dir) {
  try {
    const entries = await fs.readdir(dir, { withFileTypes: true });
    return entries.filter((entry) => entry.isDirectory()).map((entry) => path.join(dir, entry.name));
  } catch {
    return [];
  }
}

async function runPython(script, args, label) {
  await fs.mkdir(PREVIEW_DIR, { recursive: true });
  const scriptPath = path.join(PREVIEW_DIR, `flightalert-workbook-${Date.now()}-${Math.random().toString(16).slice(2)}.py`);
  await fs.writeFile(scriptPath, script, "utf8");
  return new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [scriptPath, ...args], { cwd: ROOT, windowsHide: true });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk.toString(); });
    child.stderr.on("data", (chunk) => { stderr += chunk.toString(); });
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) {
        resolve(stdout.trim());
      } else {
        reject(new Error(`${label || "python"} failed with exit ${code}: ${stderr || stdout}`));
      }
    });
  });
}

function parseKeyValueFile(text) {
  const parsed = {};
  for (const line of text.split(/\r?\n/)) {
    const match = line.match(/^([A-Za-z0-9_.-]+)=(.*)$/);
    if (match) parsed[match[1]] = match[2];
  }
  return parsed;
}

function parsePerfTokens(text) {
  const parsed = {};
  const re = /(^|\s)([A-Za-z][A-Za-z0-9_]*?)=([^\s]+)/g;
  let match;
  while ((match = re.exec(text)) !== null) {
    let value = match[3];
    if (value.endsWith(",")) value = value.slice(0, -1);
    parsed[match[2]] = maybeNumber(value);
  }
  return parsed;
}

function parseAndroidGfxInfo(text) {
  const value = (pattern) => {
    const match = text.match(pattern);
    return match ? maybeNumber(match[1]) : "";
  };
  const uptimeMs = value(/^Uptime:\s*(\d+)/m);
  const statsSinceNs = value(/^Stats since:\s*(\d+)ns/m);
  const frames = value(/^Total frames rendered:\s*(\d+)/m);
  const uptimeNumber = finiteNumber(uptimeMs);
  const statsSinceNumber = finiteNumber(statsSinceNs);
  const frameNumber = finiteNumber(frames);
  const sampleSeconds = uptimeNumber != null && statsSinceNumber != null
    ? Math.max(0, (uptimeNumber - statsSinceNumber / 1000000.0) / 1000.0)
    : "";
  const produced = finiteNumber(sampleSeconds) != null && Number(sampleSeconds) > 0 && frameNumber != null
    ? frameNumber / Number(sampleSeconds)
    : "";
  return {
    androidFrames: frames,
    androidSampleSeconds: Number.isFinite(Number(sampleSeconds)) ? Number(sampleSeconds.toFixed(3)) : "",
    androidProducedFps: Number.isFinite(Number(produced)) ? Number(produced.toFixed(1)) : "",
    androidP50Ms: value(/^50th percentile:\s*([0-9.]+)ms/m),
    androidP90Ms: value(/^90th percentile:\s*([0-9.]+)ms/m),
    androidP95Ms: value(/^95th percentile:\s*([0-9.]+)ms/m),
    androidP99Ms: value(/^99th percentile:\s*([0-9.]+)ms/m),
  };
}

function parseFrameTimelineMetrics(text, targetHz = 120) {
  const lines = text.split(/\r?\n/);
  let headerIndex = -1;
  for (let index = lines.length - 1; index >= 0; index--) {
    if (lines[index].startsWith("Flags,FrameTimelineVsyncId,")) {
      headerIndex = index;
      break;
    }
  }
  if (headerIndex < 0) return {};

  const headers = lines[headerIndex].split(",").filter(Boolean);
  const intendedIndex = headers.indexOf("IntendedVsync");
  const completedIndex = headers.indexOf("FrameCompleted");
  const presentIndex = headers.indexOf("DisplayPresentTime");
  if (intendedIndex < 0 || completedIndex < 0) return {};

  const durations = [];
  const intendedSeconds = [];
  const presentSeconds = [];
  for (let index = headerIndex + 1; index < lines.length; index++) {
    const line = lines[index].trim();
    if (!line || !/^[-\d]/.test(line)) continue;
    const parts = line.split(",");
    if (parts.length <= Math.max(intendedIndex, completedIndex)) continue;
    const intended = Number(parts[intendedIndex]);
    const completed = Number(parts[completedIndex]);
    if (!Number.isFinite(intended) || !Number.isFinite(completed) || intended <= 0 || completed <= intended) continue;
    const durationMs = (completed - intended) / 1000000.0;
    if (durationMs <= 0.0 || durationMs > 5000.0) continue;
    durations.push(durationMs);
    intendedSeconds.push(intended / 1000000000.0);
    if (presentIndex >= 0 && parts.length > presentIndex) {
      const present = Number(parts[presentIndex]);
      if (Number.isFinite(present) && present > 0) presentSeconds.push(present / 1000000000.0);
    }
  }
  if (!durations.length) return {};

  const targetBudgetMs = 1000.0 / (finiteNumber(targetHz) || 120);
  const presentIntervals = presentIntervalsMs(presentSeconds);
  const sampleSeconds = intendedSeconds.length > 1
    ? intendedSeconds[intendedSeconds.length - 1] - intendedSeconds[0]
    : "";
  const producedFps = finiteNumber(sampleSeconds) != null && sampleSeconds > 0
    ? durations.length / sampleSeconds
    : "";
  const presentMean = meanValue(presentIntervals);
  const overTarget = durations.filter((duration) => duration > targetBudgetMs).length;
  const presentDrops = presentIntervals.filter((interval) => interval > targetBudgetMs * 1.5).length;

  return {
    frames: durations.length,
    rawTimelineFrames: durations.length,
    presentIntervals: presentIntervals.length,
    sampleSeconds: rounded(sampleSeconds, 3),
    producedFps: rounded(producedFps, 1),
    presentMeanFps: finiteNumber(presentMean) != null && presentMean > 0 ? rounded(1000.0 / presentMean, 1) : "",
    p50Ms: rounded(percentileValue(durations, 50), 2),
    p95Ms: rounded(percentileValue(durations, 95), 2),
    p99Ms: rounded(percentileValue(durations, 99), 2),
    presentP50Ms: rounded(percentileValue(presentIntervals, 50), 2),
    presentP95Ms: rounded(percentileValue(presentIntervals, 95), 2),
    presentP99Ms: rounded(percentileValue(presentIntervals, 99), 2),
    presentDrop120Pct: presentIntervals.length ? rounded((presentDrops * 100.0) / presentIntervals.length, 2) : "",
    latencyMiss120Pct: durations.length ? rounded((overTarget * 100.0) / durations.length, 2) : "",
  };
}

function compactPerfTokens(parsed, keys) {
  return keys
    .filter((key) => parsed[key] !== undefined && parsed[key] !== "")
    .map((key) => `${key}=${parsed[key]}`)
    .join(" ");
}

function statusFromNote(note) {
  const lower = note.toLowerCase();
  const tick = note.match(/^`([^`]+)`/);
  if (tick) return tick[1];
  if (lower.startsWith("rejected ")) return "rejected";
  if (lower.startsWith("not accepted")) return "not accepted";
  if (lower.includes("diagnostic/deferred")) return "diagnostic/deferred";
  if (lower.includes("diagnostic")) return "diagnostic";
  if (lower.includes("deferred")) return "deferred";
  if (lower.startsWith("kept ") || lower.includes(" kept ") || lower.includes("accepted") || lower.includes("keeper")) return "keeper/accepted";
  if (lower.includes(" rejected ") || lower.includes("do not retry")) return "rejected";
  if (lower.includes("exhausted")) return "exhausted";
  return "note";
}

function dedupeLedgerRows(rows) {
  const result = [];
  const seen = new Set();
  for (const row of rows) {
    const key = [
      row.source,
      row.section,
      row.title,
      row.artifacts,
      row.note1,
      row.note2,
      row.note3,
    ].map((value) => String(value ?? "")).join("\u001f");
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(row);
  }
  return result;
}

function titleFromNote(note) {
  let clean = note.replace(/^`[^`]+`:\s*/, "").replace(/^[-\s]+/, "");
  const colon = clean.indexOf(":");
  if (colon > 12 && colon < 140) clean = clean.slice(0, colon);
  return safeText(clean.slice(0, 180), 180);
}

function extractArtifacts(note) {
  const artifacts = new Set();
  const re = /tools\/perf\/out\/[A-Za-z0-9_.\-/]+/g;
  let match;
  while ((match = re.exec(note)) !== null) {
    artifacts.add(match[0].replace(/[),.;:]+$/, ""));
  }
  return Array.from(artifacts).slice(0, 24).join("\n");
}

function extractMetricsText(note) {
  const parts = [];
  const patterns = [
    /produced FPS `?([0-9.]+)/gi,
    /present mean FPS `?([0-9.]+)/gi,
    /present p95 `?([0-9.]+)/gi,
    /p95 [`:]?([0-9.]+)\s*ms/gi,
    /Android jank `?([0-9.]+)%/gi,
    /maxDistanceKm=?`?([0-9.]+)/gi,
  ];
  for (const pattern of patterns) {
    let match;
    while ((match = pattern.exec(note)) !== null) parts.push(match[0].replace(/`/g, ""));
  }
  return safeText(Array.from(new Set(parts)).join("; "), 1000);
}

function parseAgentsLedger(text) {
  const sectionMatch = text.match(/^##\s+16\.\s+.*Optimization Ledger.*$/m);
  const start = sectionMatch ? sectionMatch.index : -1;
  const end = text.indexOf("\n## 17.", start);
  if (start < 0 || end < 0) return [];
  const sectionText = text.slice(start, end);
  let section = "Optimization Ledger";
  let current = null;
  let lineNo = 0;
  const rows = [];
  const flush = () => {
    if (!current) return;
    const full = current.lines.join("\n").trim();
    current = null;
    if (!full) return;
    const chunks = chunkText(full);
    const artifacts = extractArtifacts(full);
    rows.push({
      source: "AGENTS.md migration",
      section,
      line: lineNo,
      status: statusFromNote(full),
      title: titleFromNote(full),
      dateText: (full.match(/(?:June|July|August|September|October|November|December)\s+\d{1,2},\s+2026/) || [""])[0],
      artifactCount: artifacts ? artifacts.split("\n").length : 0,
      artifacts,
      metrics: extractMetricsText(full),
      note1: chunks[0],
      note2: chunks[1],
      note3: chunks[2],
    });
  };
  for (const raw of sectionText.split(/\r?\n/)) {
    lineNo++;
    const line = raw.trimEnd();
    if (/^##\s/.test(line)) continue;
    if (line.endsWith(":") && !line.startsWith("- ") && line.length < 120) {
      flush();
      section = line.replace(/:$/, "").trim();
      continue;
    }
    if (line.startsWith("Current target:")) {
      flush();
      current = { lines: [line] };
      section = "Current target";
      continue;
    }
    if (line.startsWith("- ")) {
      flush();
      current = { lines: [line.slice(2)] };
    } else if (current && line.trim() !== "") {
      current.lines.push(line.trim());
    }
  }
  flush();
  return rows;
}

function extraLedgerRows() {
  const note = process.env.FLIGHTALERT_EXTRA_LEDGER_NOTE;
  if (!note || !note.trim()) return [];
  const full = note.trim();
  const chunks = chunkText(full);
  const artifacts = extractArtifacts(full);
  return [{
    source: "Workbook extra note",
    section: "Current iteration",
    line: "",
    status: statusFromNote(full),
    title: titleFromNote(full),
    dateText: (full.match(/(?:June|July|August|September|October|November|December)\s+\d{1,2},\s+2026/) || [""])[0],
    artifactCount: artifacts ? artifacts.split("\n").length : 0,
    artifacts,
    metrics: extractMetricsText(full),
    note1: chunks[0],
    note2: chunks[1],
    note3: chunks[2],
  }];
}

async function previousLedgerRows() {
  const sources = Array.from(new Set([CANONICAL_WORKBOOK_PATH, WORKBOOK_PATH]));
  const existing = [];
  for (const source of sources) {
    if (await exists(source)) existing.push(source);
  }
  if (!existing.length) return [];
  try {
    const script = String.raw`
import json
import os
import sys
from openpyxl import load_workbook

rows = []
for path in sys.argv[1:]:
    if not path or not os.path.exists(path):
        continue
    try:
        wb = load_workbook(path, read_only=True, data_only=True)
    except Exception:
        continue
    if "Optimization Ledger" not in wb.sheetnames:
        continue
    ws = wb["Optimization Ledger"]
    for row in ws.iter_rows(min_row=2, values_only=True):
        if not row or not any(cell not in (None, "") for cell in row):
            continue
        values = list(row) + [""] * 12
        rows.append({
            "source": values[0] or "",
            "section": values[1] or "",
            "line": values[2] or "",
            "status": values[3] or "",
            "title": values[4] or "",
            "dateText": values[5] or "",
            "artifactCount": values[6] or "",
            "artifacts": values[7] or "",
            "metrics": values[8] or "",
            "note1": values[9] or "",
            "note2": values[10] or "",
            "note3": values[11] or "",
        })
print(json.dumps(rows, default=str))
`;
    const stdout = await runPython(script, existing, "read previous ledger");
    const parsed = JSON.parse(stdout || "[]");
    if (!Array.isArray(parsed)) return [];
    return parsed.map((row) => {
      const full = [row.note1, row.note2, row.note3].filter(Boolean).join("\n").trim();
      return full ? { ...row, status: statusFromNote(full) } : row;
    });
  } catch {
    return [];
  }
}

async function collectPerformanceRows() {
  const runRows = [];
  const auditRows = [];
  const dirs = await listDirs(PERF_OUT);
  for (const dir of dirs) {
    const files = await listFiles(dir);
    const runId = path.basename(dir);
    const summary = files.find((file) => /summary-120hz\.csv$/i.test(file));
    const routeProof = files.find((file) => /route-proof\.txt$/i.test(file));
    const target = files.find((file) => /target\.txt$/i.test(file));
    const logcat = files.find((file) => /logcat\.txt$/i.test(file));
    const frameStats = files.find((file) => /-framestats\.txt$/i.test(file));
    const stat = summary ? await fs.stat(summary) : await fs.stat(dir).catch(() => ({ mtime: "" }));
    const route = routeProof && await exists(routeProof) ? parseKeyValueFile(await fs.readFile(routeProof, "utf8")) : {};
    const targetKv = target && await exists(target) ? parseKeyValueFile(await fs.readFile(target, "utf8")) : {};
    const frameStatsText = frameStats && await exists(frameStats) ? await fs.readFile(frameStats, "utf8") : "";
    const androidGfx = frameStatsText ? parseAndroidGfxInfo(frameStatsText) : {};
    if (summary && await exists(summary)) {
      const csv = await fs.readFile(summary, "utf8");
      const lines = csv.trim().split(/\r?\n/).filter(Boolean);
      if (lines.length >= 2) {
        const headers = parseCsvLine(lines[0]);
        const values = parseCsvLine(lines[1]);
        const row = {};
        headers.forEach((header, index) => { row[header] = maybeNumber(values[index]); });
        const frameTimeline = frameStatsText ? parseFrameTimelineMetrics(frameStatsText, finiteNumber(row.TargetHz) || 120) : {};
        const timelineMetric = (key, fallback) => (
          frameTimeline[key] !== undefined && frameTimeline[key] !== "" ? frameTimeline[key] : fallback
        );
        const inRunPackageCompileEvidence = route.in_run_package_compile_evidence || route.package_compile_evidence || "";
        const postRunPackageCompileEvidence = route.post_run_package_compile_evidence || "";
        const artCompilePackageEvidence = route.art_compile_package_evidence || "";
        const inRunPackageDexoptState = dexoptSummary(inRunPackageCompileEvidence);
        const postRunPackageDexoptState = route.post_run_package_dexopt_state || dexoptSummary(postRunPackageCompileEvidence);
        const artCompilePackageDexoptState = dexoptSummary(artCompilePackageEvidence);
        const inRunPackageDexoptFingerprint = dexoptFingerprint(inRunPackageCompileEvidence);
        const postRunPackageDexoptFingerprint = route.post_run_package_dexopt_fingerprint || dexoptFingerprint(postRunPackageCompileEvidence);
        const artCompilePackageDexoptFingerprint = dexoptFingerprint(artCompilePackageEvidence);
        runRows.push({
          runDate: stat.mtime || "",
          runId,
          artifactDir: path.relative(ROOT, dir).replace(/\\/g, "/"),
          testName: String(row.File || "").replace(/^flightalert-perf-/, "").replace(/-framestats\.txt$/, ""),
          gitMetadataAvailable: route.git_metadata_available || "",
          gitBranch: route.git_branch || "",
          gitCommit: route.git_commit || "",
          gitWorktreeDirty: route.git_worktree_dirty || "",
          gitStatusCount: maybeNumber(route.git_status_count),
          gitStatusShort: route.git_status_short || "",
          gitError: route.git_error || "",
          benchmarkRole: route.benchmark_role || "",
          harnessExecutionMode: route.harness_execution_mode || "",
          controlledPreflightRequired: route.controlled_preflight_required || "",
          controlledPreflightPassed: route.controlled_preflight_passed || "",
          controlledPreflightExpectedDexopt: route.controlled_preflight_expected_dexopt || "",
          controlledDexoptNormalizationMode: route.controlled_dexopt_normalization_mode || route.controlled_preflight_repair_mode || "",
          controlledPreflightEvidence: route.controlled_preflight_evidence || "",
          controlledDexoptNormalizationFile: route.controlled_dexopt_normalization_file || route.controlled_dexopt_repair_file || "",
          controlledDexoptNormalizationCommand: route.controlled_dexopt_normalization_command || route.controlled_dexopt_repair_command || "",
          controlledDexoptNormalizationExitCode: maybeNumber(route.controlled_dexopt_normalization_exit_code || route.controlled_dexopt_repair_exit_code),
          controlledDexoptNormalizationOutput: route.controlled_dexopt_normalization_output || route.controlled_dexopt_repair_output || "",
          controlledDexoptNormalizationPreState: route.controlled_dexopt_normalization_pre_state || "",
          controlledDexoptNormalizationPreFingerprint: route.controlled_dexopt_normalization_pre_fingerprint || "",
          controlledDexoptNormalizationPackageState: route.controlled_dexopt_normalization_package_state || route.controlled_dexopt_repair_package_state || "",
          controlledDexoptNormalizationPackageFingerprint: route.controlled_dexopt_normalization_package_fingerprint || "",
          controlledDexoptNormalizationPackageEvidence: route.controlled_dexopt_normalization_package_evidence || route.controlled_dexopt_repair_package_evidence || "",
          instrumentationComponent: route.instrumentation_component || "",
          splitInstallExitCode: maybeNumber(route.split_install_exit_code),
          artCompileMode: route.art_compile_mode || "",
          artCompileCommand: route.art_compile_command || "",
          artCompileExitCode: maybeNumber(route.art_compile_exit_code),
          artCompileOutput: route.art_compile_output || "",
          artCompilePackageEvidence,
          debugApkSha256: route.debug_apk_sha256 || "",
          debugApkPath: route.debug_apk_path || "",
          debugApkBytes: maybeNumber(route.debug_apk_bytes),
          debugApkLastWriteUtc: route.debug_apk_last_write_utc || "",
          testApkSha256: route.test_apk_sha256 || "",
          testApkPath: route.test_apk_path || "",
          testApkBytes: maybeNumber(route.test_apk_bytes),
          testApkLastWriteUtc: route.test_apk_last_write_utc || "",
          devicePackagePaths: route.device_package_paths || "",
          inRunPackageCompileEvidence,
          postRunPackageCompileEvidence,
          inRunPackageDexoptState,
          postRunPackageDexoptState,
          artCompilePackageDexoptState,
          packageDexoptState: inRunPackageDexoptState || postRunPackageDexoptState || artCompilePackageDexoptState,
          inRunPackageDexoptFingerprint,
          postRunPackageDexoptFingerprint,
          artCompilePackageDexoptFingerprint,
          packageDexoptFingerprint: inRunPackageDexoptFingerprint || postRunPackageDexoptFingerprint || artCompilePackageDexoptFingerprint,
          batteryLevel: maybeNumber(route.battery_level),
          batteryTempC: maybeNumber(route.battery_temp_c),
          batteryStatus: route.battery_status || "",
          batteryPlugged: route.battery_plugged || "",
          thermalStatus: route.thermal_status || "",
          thermalEvidence: route.thermal_evidence || "",
          inRunDisplayRefreshEvidence: route.in_run_display_refresh_evidence || "",
          postRunDisplayRefreshEvidence: route.post_run_display_refresh_evidence || route.display_refresh_evidence || "",
          deviceBuildFingerprint: route.device_build_fingerprint || "",
          deviceBuildVersion: route.device_build_version || "",
          deviceBuildSdk: route.device_build_sdk || "",
          deviceArtEvidence: route.device_art_evidence || "",
          deviceEvidenceError: route.device_evidence_error || "",
          city: route.route_focus_city || targetKv.city || "",
          expectedCity: route.route_focus_expected_city || "",
          mapSource: targetKv.map_source || "",
          roads: route.map_roads_argument || "",
          borders: route.map_borders_argument || "",
          mapDetailTiming: targetKv.map_detail_timing || "",
          trafficDetailTiming: targetKv.traffic_detail_timing || (runId.includes("trafficdetail") || runId.includes("detail") ? "true" : ""),
          frameMetricsProbe: targetKv.frame_metrics_probe || route.frame_metrics_probe || "",
          skipChrome: route.skip_chrome || targetKv.skip_chrome || "",
          skipTopStatus: route.skip_top_status || targetKv.skip_top_status || "",
          skipControls: route.skip_controls || targetKv.skip_controls || "",
          skipTrafficPanel: route.skip_traffic_panel || targetKv.skip_traffic_panel || "",
          skipTraffic: route.skip_traffic || targetKv.skip_traffic || "",
          recordVideo: route.record_video || targetKv.record_video || "",
          videosCount: maybeNumber(route.videos_count),
          screenshotsCount: maybeNumber(route.screenshots_count),
          maxRunSeconds: maybeNumber(route.max_run_seconds),
          scaleBands: targetKv.scale_bands || "",
          phaseName: targetKv.phase_name || "",
          phaseZoomPlan: targetKv.phase_zoom_plan || "",
          phaseGesturePlan: targetKv.phase_gesture_plan || "",
          workloadTargetMs: parseTargetMotionMs(targetKv.phase_zoom_plan),
          routeFocusPassed: route.route_focus_passed || "",
          acceptedEvidence: route.accepted_optimizer_evidence || "",
          acceptedReason: route.accepted_optimizer_evidence_reason || "",
          samples: maybeNumber(route.route_focus_samples),
          maxDistanceKm: maybeNumber(route.route_focus_max_distance_km),
          frames: timelineMetric("frames", row.Frames),
          rawTimelineFrames: timelineMetric("rawTimelineFrames", row.RawTimelineFrames),
          histogramFrames: row.HistogramFrames,
          presentIntervals: timelineMetric("presentIntervals", row.PresentIntervals),
          sampleSeconds: timelineMetric("sampleSeconds", row.SampleSeconds),
          producedFps: timelineMetric("producedFps", row.ProducedFps),
          presentMeanFps: timelineMetric("presentMeanFps", row.PresentMeanFps),
          p50Ms: timelineMetric("p50Ms", row.P50Ms),
          p95Ms: timelineMetric("p95Ms", row.P95Ms),
          p99Ms: timelineMetric("p99Ms", row.P99Ms),
          androidFrames: androidGfx.androidFrames || "",
          androidSampleSeconds: androidGfx.androidSampleSeconds || "",
          androidProducedFps: androidGfx.androidProducedFps || "",
          androidP50Ms: androidGfx.androidP50Ms || "",
          androidP90Ms: androidGfx.androidP90Ms || "",
          androidP95Ms: androidGfx.androidP95Ms || "",
          androidP99Ms: androidGfx.androidP99Ms || "",
          presentP50Ms: timelineMetric("presentP50Ms", row.PresentP50Ms),
          presentP95Ms: timelineMetric("presentP95Ms", row.PresentP95Ms),
          presentP99Ms: timelineMetric("presentP99Ms", row.PresentP99Ms),
          presentDrop120Pct: timelineMetric("presentDrop120Pct", row.PresentDrop120Pct),
          latencyMiss120Pct: timelineMetric("latencyMiss120Pct", row.LatencyMiss120Pct),
          androidJankPct: row.AndroidJank,
          summaryFile: path.relative(ROOT, summary).replace(/\\/g, "/"),
        });
      }
    }
    if (logcat && await exists(logcat)) {
      const logText = await fs.readFile(logcat, "utf8");
      let sequence = 0;
      let phase = "";
      for (const line of logText.split(/\r?\n/)) {
        if (line.includes("FlightAlertPerfPhase:")) {
          const body = line.slice(line.indexOf("FlightAlertPerfPhase:") + "FlightAlertPerfPhase:".length);
          const tokens = parsePerfTokens(body);
          if (tokens.detail) phase = String(tokens.detail);
          continue;
        }
        if (!line.includes("Debug draw perf")) continue;
        sequence++;
        const timestamp = line.slice(0, 18).trim();
        const body = line.slice(line.indexOf("Debug draw perf") + "Debug draw perf".length).trim();
        const splitAt = body.indexOf(" maxFrameDetail=");
        const segments = [
          ["current", splitAt >= 0 ? body.slice(0, splitAt) : body],
          ["maxFrameDetail", splitAt >= 0 ? body.slice(splitAt + " maxFrameDetail=".length) : ""],
        ];
        for (const [kind, source] of segments) {
          if (!source) continue;
          const parsed = parsePerfTokens(source);
          const row = {
            runDate: stat.mtime || "",
            runId,
            artifactDir: path.relative(ROOT, dir).replace(/\\/g, "/"),
            logFile: path.relative(ROOT, logcat).replace(/\\/g, "/"),
            sequence,
            kind,
            timestamp,
            phase,
            city: route.route_focus_city || targetKv.city || "",
            routeFocusPassed: route.route_focus_passed || "",
            mapSource: targetKv.map_source || "",
            mapDetailTiming: targetKv.map_detail_timing || "",
            trafficDetailTiming: targetKv.traffic_detail_timing || "",
            frameMetricsProbe: targetKv.frame_metrics_probe || route.frame_metrics_probe || "",
            sourceDetailTokens: compactPerfTokens(parsed, SOURCE_DETAIL_KEYS),
            rawSnippet: safeText(source, AUDIT_RAW_SNIPPET_MAX_CHARS),
          };
          for (const key of DETAIL_KEYS) row[key] = parsed[key] ?? "";
          auditRows.push(row);
        }
      }
    }
  }
  runRows.sort((a, b) => new Date(a.runDate) - new Date(b.runDate) || String(a.runId).localeCompare(String(b.runId)));
  auditRows.sort((a, b) => new Date(a.runDate) - new Date(b.runDate) || String(a.runId).localeCompare(String(b.runId)) || a.sequence - b.sequence || String(a.kind).localeCompare(String(b.kind)));
  return { runRows, auditRows };
}

function compactTraceItems(items, fields) {
  if (!Array.isArray(items)) return "";
  return items.map((item) => fields
    .filter((field) => item[field] !== undefined && item[field] !== "")
    .map((field) => `${field}=${item[field]}`)
    .join(" "))
    .filter(Boolean)
    .join("\n");
}

function formatOptionalNumber(value, digits = 3) {
  const number = maybeNumber(value);
  return Number.isFinite(number) ? number.toFixed(digits) : "";
}

function compactFrameCorrelationDecision(frameCorrelation) {
  if (!frameCorrelation || !frameCorrelation.frames) return "";
  const candidates = Array.isArray(frameCorrelation.goCandidates) ? frameCorrelation.goCandidates : [];
  const top = Array.isArray(frameCorrelation.correlations) ? frameCorrelation.correlations[0] || {} : {};
  const post = top.post_and_wait || {};
  const flush = top.rt_flush_commands || {};
  if (candidates.length) {
    return `GO candidate(s): ${candidates.map((row) => row.source).filter(Boolean).join(", ")}`;
  }
  return [
    "STOP: 0 frame-correlation GO candidates",
    top.source ? `top=${top.source}` : "",
    `post rho=${formatOptionalNumber(post.spearman)} deltaMs=${formatOptionalNumber(post.topMinusBottomMs)}`,
    `flush rho=${formatOptionalNumber(flush.spearman)} deltaMs=${formatOptionalNumber(flush.topMinusBottomMs)}`,
  ].filter(Boolean).join("; ");
}

async function collectTraceAuditRows() {
  const rows = [];
  const dirs = await listDirs(PERF_OUT);
  for (const dir of dirs) {
    const runId = path.basename(dir);
    const summaryPath = path.join(dir, "frametimeline-summary.json");
    const frameCorrelationPath = path.join(dir, "frame-correlation-summary.json");
    const hasFrameTimelineSummary = await exists(summaryPath);
    const hasFrameCorrelationSummary = await exists(frameCorrelationPath);
    if (!hasFrameTimelineSummary && !hasFrameCorrelationSummary) continue;
    try {
      const summary = hasFrameTimelineSummary ? JSON.parse(await fs.readFile(summaryPath, "utf8")) : {};
      const frameCorrelation = hasFrameCorrelationSummary
        ? JSON.parse(await fs.readFile(frameCorrelationPath, "utf8"))
        : {};
      const exclusiveSummaryPath = path.join(dir, "exclusive-traffic-summary.json");
      const exclusive = (await exists(exclusiveSummaryPath))
        ? JSON.parse(await fs.readFile(exclusiveSummaryPath, "utf8"))
        : {};
      const renderSummaryPath = path.join(dir, "renderthread-frame-summary.json");
      const render = (await exists(renderSummaryPath))
        ? JSON.parse(await fs.readFile(renderSummaryPath, "utf8"))
        : {};
      const files = await listFiles(dir);
      const traceFile = files.find((file) => /\.perfetto-trace$/i.test(file));
      const stat = traceFile ? await fs.stat(traceFile) : await fs.stat(summaryPath);
      const frames = summary.frames || {};
      const gate = summary.direct_gate || {};
      const exclusiveGate = exclusive.gate || {};
      const renderMetrics = render.metric_summary || {};
      const renderMetric = (name) => renderMetrics[name] || {};
      const frameCorrelationMetrics = frameCorrelation.metrics || {};
      const frameCorrelationMetric = (name) => frameCorrelationMetrics[name] || {};
      const frameCorrelationTop = Array.isArray(frameCorrelation.correlations)
        ? frameCorrelation.correlations[0] || {}
        : {};
      const frameCorrelationPost = frameCorrelationTop.post_and_wait || {};
      const frameCorrelationFlush = frameCorrelationTop.rt_flush_commands || {};
      const frameCorrelationGoCandidates = Array.isArray(frameCorrelation.goCandidates)
        ? frameCorrelation.goCandidates.map((row) => row.source).filter(Boolean).join(", ")
        : "";
      const appDeadline = maybeNumber(gate.app_deadline_missed);
      const directCouldMeet = maybeNumber(gate.direct_removal_could_meet_deadline);
      const exclusiveMissed = maybeNumber(exclusive.app_deadline_missed) || appDeadline;
      const exclusiveRescue = maybeNumber(exclusive.exclusive_rescue_frames);
      const exclusiveRescuePct = maybeNumber(exclusive.exclusive_rescue_pct_of_missed);
      const exclusiveP95 = maybeNumber(exclusive.traffic_exclusive_frame_p95_ms);
      const exclusiveTrafficDecision = exclusive.run
        ? (exclusiveGate.rescue_pct_lt_10 || !exclusiveGate.p95_exclusive_frame_ms_ge_6
          ? `STOP: exclusive traffic p95=${exclusiveP95 || 0}ms; removal could meet ${exclusiveRescue || 0}/${exclusiveMissed || 0} AppDeadlineMissed frames (${exclusiveRescuePct || 0}%)`
          : `GO: exclusive traffic p95=${exclusiveP95 || 0}ms; removal could meet ${exclusiveRescue || 0}/${exclusiveMissed || 0} AppDeadlineMissed frames (${exclusiveRescuePct || 0}%)`)
        : "";
      const postWait = renderMetric("post_and_wait");
      const rtDraw = renderMetric("rt_drawframes");
      const rtFlush = renderMetric("rt_flush_commands");
      const gpuWait = renderMetric("gpu_wait");
      const renderDecision = render.run
        ? `RenderThread diagnostic: postAndWait p95=${maybeNumber(postWait.slice_p95_ms) || 0}ms, RT DrawFrames p95=${maybeNumber(rtDraw.slice_p95_ms) || 0}ms, flush p95=${maybeNumber(rtFlush.slice_p95_ms) || 0}ms, GPU wait p95=${maybeNumber(gpuWait.slice_p95_ms) || 0}ms`
        : "";
      rows.push({
        runDate: stat.mtime || "",
        runId,
        artifactDir: path.relative(ROOT, dir).replace(/\\/g, "/"),
        traceFile: traceFile ? path.relative(ROOT, traceFile).replace(/\\/g, "/") : "",
        traceMb: maybeNumber(summary.trace_mb),
        frames: maybeNumber(frames.frames),
        appDeadlineMissed: maybeNumber(frames.app_deadline_missed),
        bufferStuffing: maybeNumber(frames.buffer_stuffing),
        surfaceFlingerStuffing: maybeNumber(frames.sf_stuffing),
        displayHal: maybeNumber(frames.display_hal),
        actualAvgMs: maybeNumber(frames.actual_avg_ms),
        actualMaxMs: maybeNumber(frames.actual_max_ms),
        framesWithDirect: maybeNumber(gate.frames_with_direct),
        avgDirectOverlapMs: maybeNumber(gate.avg_direct_overlap_ms),
        maxDirectOverlapMs: maybeNumber(gate.max_direct_overlap_ms),
        directRemovalCouldMeetDeadline: directCouldMeet,
        avgNeededReductionMs: maybeNumber(gate.avg_needed_reduction_ms),
        minNeededReductionMs: maybeNumber(gate.min_needed_reduction_ms),
        trafficTotalFrameP95Ms: maybeNumber(exclusive.traffic_total_frame_p95_ms),
        trafficExclusiveFrameAvgMs: maybeNumber(exclusive.traffic_exclusive_frame_avg_ms),
        trafficExclusiveFrameP95Ms: exclusiveP95,
        trafficExclusiveFrameMaxMs: maybeNumber(exclusive.traffic_exclusive_frame_max_ms),
        trafficExclusiveSliceP95Ms: maybeNumber(exclusive.traffic_exclusive_slice_p95_ms),
        exclusiveTrafficRescueFrames: exclusiveRescue,
        exclusiveTrafficRescuePct: exclusiveRescuePct,
        exclusiveTrafficDecision,
        postAndWaitP95Ms: maybeNumber(postWait.slice_p95_ms),
        postAndWaitMaxMs: maybeNumber(postWait.slice_max_ms),
        postAndWaitRescueFrames: maybeNumber(postWait.could_meet_deadline_frames),
        renderThreadDrawFramesP95Ms: maybeNumber(rtDraw.slice_p95_ms),
        renderThreadFlushP95Ms: maybeNumber(rtFlush.slice_p95_ms),
        renderThreadSkiaExecuteP95Ms: maybeNumber(renderMetric("rt_skia_execute").slice_p95_ms),
        renderThreadSkiaPrepareP95Ms: maybeNumber(renderMetric("rt_skia_prepare").slice_p95_ms),
        gpuWaitP95Ms: maybeNumber(gpuWait.slice_p95_ms),
        gpuWaitRescueFrames: maybeNumber(gpuWait.could_meet_deadline_frames),
        recordViewDrawP95Ms: maybeNumber(renderMetric("record_view_draw").slice_p95_ms),
        renderThreadDecision: renderDecision,
        frameCorrelationSummaryFile: hasFrameCorrelationSummary ? path.relative(ROOT, frameCorrelationPath).replace(/\\/g, "/") : "",
        frameCorrelationFrames: maybeNumber(frameCorrelation.frames),
        frameCorrelationLogFrames: maybeNumber(frameCorrelation.framesWithLogCounters),
        frameCorrelationPostN: maybeNumber(frameCorrelationMetric("post_and_wait").n),
        frameCorrelationPostP95Ms: maybeNumber(frameCorrelationMetric("post_and_wait").p95Ms),
        frameCorrelationFlushN: maybeNumber(frameCorrelationMetric("rt_flush_commands").n),
        frameCorrelationFlushP95Ms: maybeNumber(frameCorrelationMetric("rt_flush_commands").p95Ms),
        frameCorrelationTopSource: frameCorrelationTop.source || "",
        frameCorrelationTopPostRho: maybeNumber(frameCorrelationPost.spearman),
        frameCorrelationTopPostDeltaMs: maybeNumber(frameCorrelationPost.topMinusBottomMs),
        frameCorrelationTopFlushRho: maybeNumber(frameCorrelationFlush.spearman),
        frameCorrelationTopFlushDeltaMs: maybeNumber(frameCorrelationFlush.topMinusBottomMs),
        frameCorrelationGoCandidates,
        frameCorrelationDecision: compactFrameCorrelationDecision(frameCorrelation),
        topJankTypes: compactTraceItems(summary.top_jank, ["jank_type", "n"]),
        topAppTraceSlices: compactTraceItems(summary.top_slices, ["name", "n", "avg_ms", "p95_ms", "max_ms"]),
        decision: appDeadline
          ? `DirectSymbols removal could meet ${directCouldMeet || 0}/${appDeadline} AppDeadlineMissed frames`
          : "",
      });
    } catch (error) {
      rows.push({
        runDate: "",
        runId,
        artifactDir: path.relative(ROOT, dir).replace(/\\/g, "/"),
        traceFile: "",
        traceMb: "",
        frames: "",
        appDeadlineMissed: "",
        bufferStuffing: "",
        surfaceFlingerStuffing: "",
        displayHal: "",
        actualAvgMs: "",
        actualMaxMs: "",
        framesWithDirect: "",
        avgDirectOverlapMs: "",
        maxDirectOverlapMs: "",
        directRemovalCouldMeetDeadline: "",
        avgNeededReductionMs: "",
        minNeededReductionMs: "",
        trafficTotalFrameP95Ms: "",
        trafficExclusiveFrameAvgMs: "",
        trafficExclusiveFrameP95Ms: "",
        trafficExclusiveFrameMaxMs: "",
        trafficExclusiveSliceP95Ms: "",
        exclusiveTrafficRescueFrames: "",
        exclusiveTrafficRescuePct: "",
        exclusiveTrafficDecision: "",
        postAndWaitP95Ms: "",
        postAndWaitMaxMs: "",
        postAndWaitRescueFrames: "",
        renderThreadDrawFramesP95Ms: "",
        renderThreadFlushP95Ms: "",
        renderThreadSkiaExecuteP95Ms: "",
        renderThreadSkiaPrepareP95Ms: "",
        gpuWaitP95Ms: "",
        gpuWaitRescueFrames: "",
        recordViewDrawP95Ms: "",
        renderThreadDecision: "",
        frameCorrelationSummaryFile: "",
        frameCorrelationFrames: "",
        frameCorrelationLogFrames: "",
        frameCorrelationPostN: "",
        frameCorrelationPostP95Ms: "",
        frameCorrelationFlushN: "",
        frameCorrelationFlushP95Ms: "",
        frameCorrelationTopSource: "",
        frameCorrelationTopPostRho: "",
        frameCorrelationTopPostDeltaMs: "",
        frameCorrelationTopFlushRho: "",
        frameCorrelationTopFlushDeltaMs: "",
        frameCorrelationGoCandidates: "",
        frameCorrelationDecision: "",
        topJankTypes: "",
        topAppTraceSlices: "",
        decision: safeText(`Failed to parse trace audit artifacts in ${runId}: ${error.message}`, 1000),
      });
    }
  }
  rows.sort((a, b) => new Date(a.runDate) - new Date(b.runDate) || String(a.runId).localeCompare(String(b.runId)));
  return rows;
}

async function collectFrameCorrelationRows() {
  const rows = [];
  const headers = [];
  const seenHeaders = new Set();
  const dirs = await listDirs(PERF_OUT);
  for (const dir of dirs) {
    const runId = path.basename(dir);
    const csvPath = path.join(dir, "frame-correlation-frames.csv");
    if (!(await exists(csvPath))) continue;
    const summaryPath = path.join(dir, "frame-correlation-summary.json");
    const stat = await fs.stat(csvPath);
    const text = await fs.readFile(csvPath, "utf8");
    const lines = text.trim().split(/\r?\n/).filter(Boolean);
    if (lines.length < 2) continue;
    const csvHeaders = parseCsvLine(lines[0]);
    for (const header of csvHeaders) {
      if (seenHeaders.has(header)) continue;
      seenHeaders.add(header);
      headers.push(header);
    }
    const hasSummary = await exists(summaryPath);
    for (const line of lines.slice(1)) {
      const values = parseCsvLine(line);
      const detail = {};
      csvHeaders.forEach((header, index) => {
        detail[header] = maybeNumber(values[index]);
      });
      rows.push({
        runDate: stat.mtime || "",
        runId,
        artifactDir: path.relative(ROOT, dir).replace(/\\/g, "/"),
        summaryFile: hasSummary ? path.relative(ROOT, summaryPath).replace(/\\/g, "/") : "",
        csvFile: path.relative(ROOT, csvPath).replace(/\\/g, "/"),
        detail,
      });
    }
  }
  rows.sort((a, b) => {
    const byDate = new Date(a.runDate) - new Date(b.runDate);
    if (byDate) return byDate;
    const byRun = String(a.runId).localeCompare(String(b.runId));
    if (byRun) return byRun;
    return maybeNumber(a.detail.drawSeq) - maybeNumber(b.detail.drawSeq);
  });
  return { headers, rows };
}

function buildWorkbookModel(runRows, auditRows, traceAuditRows, frameCorrelationRows, ledgerRows) {
  const aircraftEvidenceByRun = buildAircraftEvidenceByRun(auditRows);
  const enrichedRunRows = runRows.map((row) => withChartEligibility(row, aircraftEvidenceByRun.get(row.runId)));
  const eligibleChartRows = enrichedRunRows.filter((row) => row.chartEligible === "Yes");
  const workbookTestRows = enrichedRunRows.filter((row) => row.workbookTestEligible === "Yes");
  const latest = enrichedRunRows[enrichedRunRows.length - 1] || {};
  const latestEligible = workbookTestRows[workbookTestRows.length - 1] || {};
  const bestEligible = workbookTestRows.slice().sort(compareRunPerformance)[0] || {};
  const versionSummary = buildVersionSummary(workbookTestRows);
  const bestVersionSummary = versionSummary.slice().sort(compareVersionSummaryPerformance)[0] || {};
  const activeChartKey = latestEligible.runId ? comparableSeriesKey(latestEligible, true) : "";
  const activeChartRows = activeChartKey
    ? workbookTestRows.filter((row) => comparableSeriesKey(row, true) === activeChartKey)
    : workbookTestRows;
  const activeChartRecentRows = activeChartRows.slice(-40);
  const activeChartRunIds = new Set(activeChartRecentRows.map((row) => row.runId).filter(Boolean));
  const dashboardRows = [
    ["Flight Alert Performance Workbook"],
    [],
    ["Metric", "Value", "Notes"],
    ["Workbook generated at", new Date().toISOString(), "If the workbook open in Excel does not show this timestamp, it is a stale/locked copy; rebuild or close Excel and rerun the builder."],
    ["Runs captured", runRows.length, "One row per 120 Hz framestats summary"],
    ["Chart-eligible full runs", eligibleChartRows.length, "Route proof passed, full UI/traffic visibility explicit, no skip/video/diagnostic run, holistic workload, thermal 0, controlled dexopt lane, InstallDefault ART"],
    ["Workbook-test comparable runs", workbookTestRows.length, "Controlled standardized benchmark lane used for charts and checkpoint decisions"],
    ["Detailed audit rows", auditRows.length, "Parsed Debug draw perf current/maxFrameDetail rows with phase-level timing columns"],
    ["Trace audit rows", traceAuditRows.length, "Parsed FrameTimeline/Perfetto summaries where present"],
    ["Frame correlation rows", frameCorrelationRows.rows.length, "One row per matched frame-tokened Perfetto frame"],
    ["Optimization ledger rows", ledgerRows.length, "Moved from AGENTS.md; full notes are split across text columns"],
    ["Latest raw run (all artifacts)", latest.runId || "", "May be excluded from Workbook Tests; artifact: " + (latest.artifactDir || "")],
    ["Latest raw full produced FPS", chartProducedFps(latest) || "", "Raw latest artifact; from Android full-window gfxinfo when available, otherwise FrameTimeline"],
    ["Latest raw FrameTimeline produced FPS", latest.producedFps || "", "Raw latest artifact from summary-120hz.csv FrameTimeline rows"],
    ["Latest raw present mean FPS", latest.presentMeanFps || "", "Raw latest artifact from FrameTimeline present intervals"],
    ["Latest raw full P95 ms", chartP95Ms(latest) || "", "Raw latest artifact; from Android full-window gfxinfo when available, otherwise FrameTimeline"],
    ["Latest raw FrameTimeline P95 ms", latest.p95Ms || "", "Raw latest artifact FrameTimeline p95"],
    ["Latest raw Android jank %", latest.androidJankPct || "", "Raw latest artifact parsed AndroidJank percentage"],
    ["Latest raw workbook-test eligible", latest.workbookTestEligible || "", latest.workbookTestExclusionReason || "If No, see Runs eligibility/reason columns"],
    ["Latest raw chart eligible", latest.chartEligible || "", latest.chartExclusionReason || "If No, see Runs eligibility/reason columns"],
    ["Latest raw in Workbook Tests chart", latest.runId && activeChartRunIds.has(latest.runId) ? "Yes" : "No", "Workbook Tests shows the latest active comparable chart lane; all runs remain in Runs"],
    ["Latest workbook-test run", latestEligible.runId || "", latestEligible.artifactDir || ""],
    ["Latest workbook-test full produced FPS", chartProducedFps(latestEligible) || "", "Chart-eligible comparable run used for checkpoint decisions"],
    ["Latest workbook-test present mean FPS", latestEligible.presentMeanFps || "", "Chart-eligible comparable run used for checkpoint decisions"],
    ["Latest workbook-test full P95 ms", chartP95Ms(latestEligible) || "", "Chart-eligible comparable run used for checkpoint decisions"],
    ["Latest workbook-test Android jank %", latestEligible.androidJankPct || "", "Chart-eligible comparable run used for checkpoint decisions"],
    ["Best single workbook-test run", bestEligible.runId || "", "Single-run marker only; use repeated same-lane rows and the Dashboard version average for checkpoint decisions"],
    ["Best workbook-test version", bestVersionSummary.version || "", `${bestVersionSummary.rows?.length || 0} comparable run(s); avg full FPS ${averageMetric(bestVersionSummary.rows || [], chartProducedFps) || ""}; avg present FPS ${averageMetric(bestVersionSummary.rows || [], (row) => row.presentMeanFps) || ""}`],
    ["User-facing chart scope", activeChartKey || "", "Latest comparable workbook-test series; requires clean git, aircraft draw evidence, thermal 0, matching package dexopt fingerprint/normalization mode, and InstallDefault ART; dirty/nonmatching/uncontrolled series stay out of Workbook Tests"],
  ];

  const runHeaders = [
    "Run Date",
    "Run ID",
    "Artifact Dir",
    "Test",
    "Git Metadata",
    "Git Branch",
    "Git Commit",
    "Git Worktree Dirty",
    "Git Status Count",
    "Git Status Short",
    "Git Error",
    "Benchmark Role",
    "Harness Execution Mode",
    "Controlled Preflight Required",
    "Controlled Preflight Passed",
    "Controlled Expected Dexopt",
    "Controlled Dexopt Normalization Mode",
    "Controlled Preflight Evidence",
    "Controlled Dexopt Normalization File",
    "Controlled Dexopt Normalization Command",
    "Controlled Dexopt Normalization Exit Code",
    "Controlled Dexopt Normalization Output",
    "Controlled Dexopt Normalization Pre State",
    "Controlled Dexopt Normalization Pre Fingerprint",
    "Controlled Dexopt Normalization Package State",
    "Controlled Dexopt Normalization Package Fingerprint",
    "Controlled Dexopt Normalization Package Evidence",
    "Instrumentation Component",
    "Split Install Exit Code",
    "ART Compile Mode",
    "ART Compile Command",
    "ART Compile Exit Code",
    "ART Compile Output",
    "ART Compile Package Evidence",
    "Debug APK SHA256",
    "Debug APK Path",
    "Debug APK Bytes",
    "Debug APK Last Write UTC",
    "Test APK SHA256",
    "Test APK Path",
    "Test APK Bytes",
    "Test APK Last Write UTC",
    "Device Package Paths",
    "In-Run Package Compile Evidence",
    "Post-Run Package Compile Evidence",
    "In-Run Package Dexopt State",
    "In-Run Package Dexopt Fingerprint",
    "Post-Run Package Dexopt State",
    "Post-Run Package Dexopt Fingerprint",
    "ART Compile Package Dexopt State",
    "ART Compile Package Dexopt Fingerprint",
    "Package Dexopt State",
    "Package Dexopt Fingerprint",
    "Battery Level",
    "Battery Temp C",
    "Battery Status",
    "Battery Plugged",
    "Thermal Status",
    "Thermal Evidence",
    "In-Run Display Refresh Evidence",
    "Post-Run Display Refresh Evidence",
    "Device Build Fingerprint",
    "Device Build Version",
    "Device Build SDK",
    "Device ART Evidence",
    "Device Evidence Error",
    "City",
    "Expected City",
    "Map Source",
    "Roads",
    "Borders",
    "Map Detail Timing",
    "Traffic Detail Timing",
    "Frame Metrics Probe",
    "Skip Chrome",
    "Skip Top Status",
    "Skip Controls",
    "Skip Traffic Panel",
    "Skip Traffic",
    "Record Video",
    "Videos",
    "Screenshots",
    "Max Run Seconds",
    "Workload Target ms",
    "Scale Bands",
    "Phase Name",
    "Phase Zoom Plan",
    "Phase Gesture Plan",
    "Chart Eligible",
    "Chart Workload Level",
    "Chart Exclusion Reason",
    "Aircraft Draw Evidence",
    "Workbook Test Eligible",
    "Workbook Test Lane",
    "Benchmark Region",
    "Workbook Test Exclusion Reason",
    "Route Proof",
    "Accepted Evidence",
    "Accepted Reason",
    "Route Samples",
    "Max Distance Km",
    "Frames",
    "Raw Timeline Frames",
    "Histogram Frames",
    "Present Intervals",
    "Sample Seconds",
    "Produced FPS",
    "Present Mean FPS",
    "FrameTimeline P50 ms",
    "FrameTimeline P95 ms",
    "FrameTimeline P99 ms",
    "Android Full Frames",
    "Android Full Sample Seconds",
    "Android Full Produced FPS",
    "Android Full P50 ms",
    "Android Full P90 ms",
    "Android Full P95 ms",
    "Android Full P99 ms",
    "Present P50 ms",
    "Present P95 ms",
    "Present P99 ms",
    "Present Drop 120 %",
    "Latency Miss 120 %",
    "Android Jank %",
    "Summary File",
  ];
  const runData = [runHeaders, ...enrichedRunRows.map((row) => [
    row.runDate,
    row.runId,
    row.artifactDir,
    row.testName,
    row.gitMetadataAvailable,
    row.gitBranch,
    row.gitCommit,
    row.gitWorktreeDirty,
    row.gitStatusCount,
    safeText(row.gitStatusShort, 1000),
    safeText(row.gitError, 1000),
    row.benchmarkRole,
    row.harnessExecutionMode,
    row.controlledPreflightRequired,
    row.controlledPreflightPassed,
    row.controlledExpectedDexoptState,
    row.controlledDexoptNormalizationModeLabel,
    safeText(row.controlledPreflightEvidence, 2400),
    safeText(row.controlledDexoptNormalizationFile, 1000),
    safeText(row.controlledDexoptNormalizationCommand, 1000),
    row.controlledDexoptNormalizationExitCode,
    safeText(row.controlledDexoptNormalizationOutput, 2400),
    row.controlledDexoptNormalizationPreState,
    row.controlledDexoptNormalizationPreFingerprint,
    row.controlledDexoptNormalizationPackageState,
    row.controlledDexoptNormalizationPackageFingerprint,
    safeText(row.controlledDexoptNormalizationPackageEvidence, 3600),
    row.instrumentationComponent,
    row.splitInstallExitCode,
    row.artCompileMode,
    safeText(row.artCompileCommand, 1000),
    row.artCompileExitCode,
    safeText(row.artCompileOutput, 2400),
    safeText(row.artCompilePackageEvidence, 3600),
    row.debugApkSha256,
    safeText(row.debugApkPath, 1000),
    row.debugApkBytes,
    row.debugApkLastWriteUtc,
    row.testApkSha256,
    safeText(row.testApkPath, 1000),
    row.testApkBytes,
    row.testApkLastWriteUtc,
    safeText(row.devicePackagePaths, 1200),
    safeText(row.inRunPackageCompileEvidence, 3600),
    safeText(row.postRunPackageCompileEvidence, 2200),
    row.inRunPackageDexoptState,
    row.inRunPackageDexoptFingerprint,
    row.postRunPackageDexoptState,
    row.postRunPackageDexoptFingerprint,
    row.artCompilePackageDexoptState,
    row.artCompilePackageDexoptFingerprint,
    row.packageDexoptState,
    row.packageDexoptFingerprint,
    row.batteryLevel,
    row.batteryTempC,
    row.batteryStatus,
    row.batteryPlugged,
    row.thermalStatus,
    safeText(row.thermalEvidence, 1800),
    safeText(row.inRunDisplayRefreshEvidence, 3200),
    safeText(row.postRunDisplayRefreshEvidence, 2200),
    safeText(row.deviceBuildFingerprint, 1000),
    row.deviceBuildVersion,
    row.deviceBuildSdk,
    safeText(row.deviceArtEvidence, 2600),
    safeText(row.deviceEvidenceError, 1000),
    row.city,
    row.expectedCity,
    row.mapSource,
    row.roads,
    row.borders,
    row.mapDetailTiming,
    row.trafficDetailTiming,
    row.frameMetricsProbe,
    row.skipChrome,
    row.skipTopStatus,
    row.skipControls,
    row.skipTrafficPanel,
    row.skipTraffic,
    row.recordVideo,
    row.videosCount,
    row.screenshotsCount,
    row.maxRunSeconds,
    row.workloadTargetMs,
    row.scaleBands,
    row.phaseName,
    row.phaseZoomPlan,
    row.phaseGesturePlan,
    row.chartEligible,
    row.chartWorkloadLevel,
    row.chartExclusionReason,
    row.aircraftDrawEvidence,
    row.workbookTestEligible,
    row.workbookTestLane,
    row.benchmarkRegion,
    row.workbookTestExclusionReason,
    row.routeFocusPassed,
    row.acceptedEvidence,
    safeText(row.acceptedReason, 1000),
    row.samples,
    row.maxDistanceKm,
    row.frames,
    row.rawTimelineFrames,
    row.histogramFrames,
    row.presentIntervals,
    row.sampleSeconds,
    row.producedFps,
    row.presentMeanFps,
    row.p50Ms,
    row.p95Ms,
    row.p99Ms,
    row.androidFrames,
    row.androidSampleSeconds,
    row.androidProducedFps,
    row.androidP50Ms,
    row.androidP90Ms,
    row.androidP95Ms,
    row.androidP99Ms,
    row.presentP50Ms,
    row.presentP95Ms,
    row.presentP99Ms,
    row.presentDrop120Pct,
    row.latencyMiss120Pct,
    row.androidJankPct,
    row.summaryFile,
  ])];

  const auditHeaders = [
    "Run Date",
    "Run ID",
    "Artifact Dir",
    "Log File",
    "Seq",
    "Kind",
    "Timestamp",
    "Phase",
    "City",
    "Route Proof",
    "Map Source",
    "Map Detail Timing",
    "Traffic Detail Timing",
    "Frame Metrics Probe",
    ...DETAIL_KEYS,
    "Source Detail Tokens",
    "Raw Detail Snippet",
  ];
  const auditData = [auditHeaders, ...auditRows.map((row) => [
    row.runDate,
    row.runId,
    row.artifactDir,
    row.logFile,
    row.sequence,
    row.kind,
    row.timestamp,
    row.phase,
    row.city,
    row.routeFocusPassed,
    row.mapSource,
    row.mapDetailTiming,
    row.trafficDetailTiming,
    row.frameMetricsProbe,
    ...DETAIL_KEYS.map((key) => row[key]),
    row.sourceDetailTokens,
    row.rawSnippet,
  ])];

  const traceAuditHeaders = [
    "Run Date",
    "Run ID",
    "Artifact Dir",
    "Trace File",
    "Trace MB",
    "Frames",
    "App Deadline Missed",
    "Buffer Stuffing",
    "SurfaceFlinger Stuffing",
    "Display HAL",
    "Actual Avg ms",
    "Actual Max ms",
    "Frames With Direct",
    "Avg Direct Overlap ms",
    "Max Direct Overlap ms",
    "Direct Removal Could Meet Deadline",
    "Avg Needed Reduction ms",
    "Min Needed Reduction ms",
    "Traffic Total Frame P95 ms",
    "Traffic Exclusive Frame Avg ms",
    "Traffic Exclusive Frame P95 ms",
    "Traffic Exclusive Frame Max ms",
    "Traffic Exclusive Slice P95 ms",
    "Exclusive Traffic Rescue Frames",
    "Exclusive Traffic Rescue % of Missed",
    "Exclusive Traffic Decision",
    "PostAndWait P95 ms",
    "PostAndWait Max ms",
    "PostAndWait Rescue Frames",
    "RenderThread DrawFrames P95 ms",
    "RenderThread Flush P95 ms",
    "RenderThread Skia Execute P95 ms",
    "RenderThread Skia Prepare P95 ms",
    "GPU Wait P95 ms",
    "GPU Wait Rescue Frames",
    "Record View Draw P95 ms",
    "RenderThread Decision",
    "Frame Correlation Summary File",
    "Frame Correlation Frames",
    "Frame Correlation Log Frames",
    "Frame Correlation PostAndWait N",
    "Frame Correlation PostAndWait P95 ms",
    "Frame Correlation Flush N",
    "Frame Correlation Flush P95 ms",
    "Frame Correlation Top Source",
    "Frame Correlation Top Post rho",
    "Frame Correlation Top Post Delta ms",
    "Frame Correlation Top Flush rho",
    "Frame Correlation Top Flush Delta ms",
    "Frame Correlation GO Candidates",
    "Frame Correlation Decision",
    "Top Jank Types",
    "Top App Trace Slices",
    "Decision",
  ];
  const traceAuditData = [traceAuditHeaders, ...traceAuditRows.map((row) => [
    row.runDate,
    row.runId,
    row.artifactDir,
    row.traceFile,
    row.traceMb,
    row.frames,
    row.appDeadlineMissed,
    row.bufferStuffing,
    row.surfaceFlingerStuffing,
    row.displayHal,
    row.actualAvgMs,
    row.actualMaxMs,
    row.framesWithDirect,
    row.avgDirectOverlapMs,
    row.maxDirectOverlapMs,
    row.directRemovalCouldMeetDeadline,
    row.avgNeededReductionMs,
    row.minNeededReductionMs,
    row.trafficTotalFrameP95Ms,
    row.trafficExclusiveFrameAvgMs,
    row.trafficExclusiveFrameP95Ms,
    row.trafficExclusiveFrameMaxMs,
    row.trafficExclusiveSliceP95Ms,
    row.exclusiveTrafficRescueFrames,
    row.exclusiveTrafficRescuePct,
    row.exclusiveTrafficDecision,
    row.postAndWaitP95Ms,
    row.postAndWaitMaxMs,
    row.postAndWaitRescueFrames,
    row.renderThreadDrawFramesP95Ms,
    row.renderThreadFlushP95Ms,
    row.renderThreadSkiaExecuteP95Ms,
    row.renderThreadSkiaPrepareP95Ms,
    row.gpuWaitP95Ms,
    row.gpuWaitRescueFrames,
    row.recordViewDrawP95Ms,
    row.renderThreadDecision,
    row.frameCorrelationSummaryFile,
    row.frameCorrelationFrames,
    row.frameCorrelationLogFrames,
    row.frameCorrelationPostN,
    row.frameCorrelationPostP95Ms,
    row.frameCorrelationFlushN,
    row.frameCorrelationFlushP95Ms,
    row.frameCorrelationTopSource,
    row.frameCorrelationTopPostRho,
    row.frameCorrelationTopPostDeltaMs,
    row.frameCorrelationTopFlushRho,
    row.frameCorrelationTopFlushDeltaMs,
    row.frameCorrelationGoCandidates,
    row.frameCorrelationDecision,
    row.topJankTypes,
    row.topAppTraceSlices,
    row.decision,
  ])];

  const frameCorrelationHeaders = [
    "Run Date",
    "Run ID",
    "Artifact Dir",
    "Summary File",
    "CSV File",
    ...frameCorrelationRows.headers,
  ];
  const frameCorrelationData = [frameCorrelationHeaders, ...frameCorrelationRows.rows.map((row) => [
    row.runDate,
    row.runId,
    row.artifactDir,
    row.summaryFile,
    row.csvFile,
    ...frameCorrelationRows.headers.map((header) => row.detail[header] ?? ""),
  ])];

  const ledgerHeaders = [
    "Source",
    "Section",
    "AGENTS Line",
    "Status",
    "Title",
    "Date Text",
    "Artifact Count",
    "Artifacts",
    "Metrics Mentioned",
    "Full Note 1",
    "Full Note 2",
    "Full Note 3",
  ];
  const ledgerData = [ledgerHeaders, ...ledgerRows.map((row) => [
    row.source,
    row.section,
    row.line,
    row.status,
    row.title,
    row.dateText,
    row.artifactCount,
    row.artifacts,
    row.metrics,
    row.note1,
    row.note2,
    row.note3,
  ])];

  const recent = activeChartRecentRows;
  const chartNote = recent.length < 2
    ? "Controlled chart needs at least two comparable rows in the same workload and dexopt lane. Rerun suspected versions under thermal 0, matching package dexopt fingerprint/normalization mode, unchanged post-run dexopt, and InstallDefault ART before selecting a best iteration."
    : "";
  const chartRows = [
    ["Run", "Full Produced FPS", "FrameTimeline Present Mean FPS", "Full P95 ms", "Android Jank %", "Thermal Status", "Package Dexopt State", "Package Dexopt Fingerprint", "Post-Run Package Dexopt State", "ART Compile Mode", "Controlled Expected Dexopt", "Controlled Dexopt Normalization Mode", "Aircraft Draw Evidence", "Run ID", "Artifact Dir", "Chart Status", "Harness Execution Mode"],
    ...recent.map((row) => [
      shortChartRunLabel(row),
      chartProducedFps(row),
      row.presentMeanFps || "",
      chartP95Ms(row),
      row.androidJankPct || "",
      row.thermalStatus || "",
      row.packageDexoptState || "",
      row.packageDexoptFingerprint || "",
      row.postRunPackageDexoptState || "",
      row.artCompileMode || "",
      row.controlledExpectedDexoptState || "",
      row.controlledDexoptNormalizationModeLabel || "",
      row.aircraftDrawEvidence || "",
      row.runId || "",
      row.artifactDir || "",
      chartNote,
      row.harnessExecutionMode || "",
    ]),
  ];
  const workbookRuleRows = [
    ["Topic", "Rule", "How To Satisfy / Where To Check"],
    ["Every optimization iteration", "Run one chart-grade apples-to-apples workbook test unless the iteration is explicitly diagnostic-only or preflight rejects before capture.", "Use the standard harness command with BenchmarkRole=Workbook, SplitInstall, InstallDefault, RequireControlledPreflight, ControlledDexoptNormalizationMode=PostInstallResetV1, timetable-selected dense EU/US city, full visible UI/traffic, and no detail timing/video unless the run is intentionally diagnostic."],
    ["Workbook Tests", "Only valid active-lane workbook tests appear here and power the user-facing charts.", "A new run must be clean git, route-proofed, full traffic/UI, aircraft draw evidence present, non-video, non-diagnostic, thermal_status=0, controlled package dexopt state/fingerprint present, post-run dexopt unchanged, ART InstallDefault, and same active lane/city/map/roads/borders/detail flags."],
    ["When a new run is missing from the chart", "Do not guess; check Runs first.", "Runs includes Chart Eligible, Chart Exclusion Reason, Workbook Test Eligible, Workbook Test Exclusion Reason, aircraft evidence, thermal, dexopt, and route-proof fields for every parsed artifact."],
    ["Checkpoint decisions", "Use the active apples-to-apples chart lane plus repeated same-version evidence.", "Use Dashboard best-version summaries for averages; do not pick a best checkpoint from one lucky run."],
    ["Runs", "Every parsed perf artifact belongs here even if it is not chartable.", "If a run is absent from Runs, the artifact path/prefix was not parsed by BuildFlightAlertPerformanceWorkbook.mjs or the artifact was not copied into tools/perf/out."],
    ["Detailed Audits", "Keep detailed Debug draw perf timing/counter rows.", "Use this sheet for phase-level evidence and to ensure diagnostics are not reduced to simplified summaries."],
    ["Canonical workbook locking", "If Excel has docs/flightalert-performance-metrics.xlsx open, the builder cannot overwrite it.", "The builder will fail with PermissionError for the canonical file. Close Excel or use the verified preview at %TEMP%\\flightalert-performance-preview.xlsx, then rerun the builder."],
    ["Experiment rows", "Diagnostic/detail/video/dirty/thermal/dexopt-invalid rows stay out of Workbook Tests.", "They remain in Runs, Detailed Audits, Trace Audits, Frame Correlations, and Optimization Ledger for auditability; their exclusion reasons are visible in Runs."],
    ["Visual claims", "Satellite roads/labels/borders visual claims require motion-video evidence.", "Workbook metrics prove frame timing, not temporal visual fidelity. Store video artifact paths in the run/ledger notes."],
  ];
  return {
    workbookPath: WORKBOOK_PATH,
    sheets: [
      {
        name: "Dashboard",
        rows: dashboardRows,
        widths: [40, 74, 108, 12, 12, 12],
        freeze: "A3",
        merges: ["A1:F1"],
        titleCells: ["A1"],
        numberFormats: [["B12:B17", "0.0"], ["B19:B22", "0.0"]],
      },
      {
        name: "Rules",
        rows: workbookRuleRows,
        widths: [30, 72, 112],
        freeze: "A2",
        table: "WorkbookRules",
        wrapCols: [2, 3],
      },
      {
        name: "Runs",
        rows: runData,
        widths: [20, 42, 54, 34, 22, 22, 14, 10, 10, 16, 18, 12, 16, 12, 18, 12, 14, 10, 12, 16, 18, 18, 18, 50, 34, 44, 22, 58, 12, 16, 60, 12, 16, 10, 18, 16, 16, 14, 14, 18, 12, 12, 12, 16, 16, 16, 18, 18, 16, 58],
        freeze: "C2",
        table: "PerformanceRuns",
        numberFormats: [["A2:A1048576", "yyyy-mm-dd hh:mm"], ["Q2:T1048576", "0.0"], ["AE2:AV1048576", "0.0"]],
        wrapCols: [23, 27, 30],
      },
      {
        name: "Detailed Audits",
        rows: auditData,
        widths: [18, 42, 48, 52, 8, 16, 18, 24, 20, 12, 14, 16, ...Array(DETAIL_KEYS.length).fill(12), 62, 70],
        freeze: "C2",
        numberFormats: [["A2:A1048576", "yyyy-mm-dd hh:mm"]],
      },
      {
        name: "Trace Audits",
        rows: traceAuditData,
        widths: [18, 42, 48, 58, 12, 10, 18, 16, 22, 12, 14, 14, 18, 20, 20, 28, 22, 22, 22, 26, 26, 26, 26, 28, 28, 30, 76, 22, 22, 26, 32, 28, 34, 34, 22, 24, 26, 86, 16, 16, 18, 22, 18, 22, 28, 18, 22, 20, 24, 34, 86, 72, 92, 52],
        freeze: "C2",
        numberFormats: [["A2:A1048576", "yyyy-mm-dd hh:mm"], ["E2:AZ1048576", "0.0"]],
        wrapCols: [27, 38, 52, 53, 54],
      },
      {
        name: "Frame Correlations",
        rows: frameCorrelationData,
        widths: [18, 42, 48, 58, 58, ...Array(frameCorrelationRows.headers.length).fill(13)],
        freeze: "F2",
        numberFormats: [["A2:A1048576", "yyyy-mm-dd hh:mm"], ["F2:ZZ1048576", "0.00"]],
      },
      {
        name: "Optimization Ledger",
        rows: ledgerData,
        widths: [22, 34, 12, 24, 48, 18, 12, 58, 64, 100, 100, 100],
        freeze: "E2",
        table: "OptimizationLedger",
        wrapCols: [8, 9, 10, 11, 12],
      },
      {
        name: "Workbook Tests",
        rows: chartRows,
        widths: [52, 18, 28, 14, 18, 14, 22, 42, 24, 18, 26, 32, 58, 72, 72, 58, 22],
        freeze: "A2",
        table: "WorkbookTests",
        charts: recent.length >= 2,
        numberFormats: [["B2:E1048576", "0.0"], ["F2:F1048576", "0.0"]],
        wrapCols: [8, 13, 14, 15, 16],
      },
    ],
  };
}

async function exportWorkbookWithOpenpyxl(model) {
  await fs.rm(PREVIEW_DIR, { recursive: true, force: true });
  await fs.mkdir(PREVIEW_DIR, { recursive: true });
  const modelPath = path.join(PREVIEW_DIR, "workbook-model.json");
  await fs.writeFile(modelPath, JSON.stringify(model), "utf8");
  const script = String.raw`
import json
import os
import re
import sys
import xlsxwriter
from openpyxl import load_workbook
from xlsxwriter.utility import xl_cell_to_rowcol

model_path = sys.argv[1]
output_path = sys.argv[2]
with open(model_path, "r", encoding="utf-8") as handle:
    model = json.load(handle)

workbook = xlsxwriter.Workbook(output_path, {
    "constant_memory": True,
    "strings_to_urls": False,
    "nan_inf_to_errors": True,
})
header_format = workbook.add_format({
    "bold": True,
    "font_color": "#FFFFFF",
    "bg_color": "#1F4E79",
    "text_wrap": True,
    "valign": "top",
})
title_format = workbook.add_format({
    "bold": True,
    "font_color": "#FFFFFF",
    "bg_color": "#1F4E79",
    "font_size": 16,
    "text_wrap": True,
    "valign": "top",
})
wrap_format = workbook.add_format({"text_wrap": True, "valign": "top"})

def excel_value(value):
    if value is None:
        return ""
    if isinstance(value, str):
        return value.replace("\x00", "")
    return value

for spec in model["sheets"]:
    rows = spec["rows"]
    max_col = max((len(row) for row in spec["rows"]), default=1)
    ws = workbook.add_worksheet(spec["name"][:31])
    for index, width in enumerate(spec.get("widths", [])):
        ws.set_column(index, index, width)
    for col_index in spec.get("wrapCols", []):
        width = spec.get("widths", [""] * col_index)[col_index - 1] if col_index - 1 < len(spec.get("widths", [])) else None
        ws.set_column(col_index - 1, col_index - 1, width, wrap_format)
    if spec.get("freeze"):
        row, col = xl_cell_to_rowcol(spec["freeze"])
        ws.freeze_panes(row, col)
    for merge in spec.get("merges", []):
        if merge == "A1:F1" and rows and rows[0]:
            ws.merge_range(merge, excel_value(rows[0][0]), title_format)
    for row_index, row in enumerate(rows):
        for col_index in range(max_col):
            if row_index == 0 and col_index == 0 and "A1:F1" in spec.get("merges", []):
                continue
            value = excel_value(row[col_index]) if col_index < len(row) else ""
            cell_format = header_format if row_index == 0 else None
            ws.write(row_index, col_index, value, cell_format)
    if rows and max_col:
        ws.autofilter(0, 0, max(len(rows) - 1, 0), max_col - 1)
    if spec.get("charts") and len(rows) >= 2:
        last_row = len(rows)
        sheet_name = spec["name"]
        fps = workbook.add_chart({"type": "line"})
        fps.set_title({"name": "Workbook Tests: Full Produced vs Present FPS"})
        fps.set_y_axis({"name": "FPS"})
        for col in ("B", "C"):
            fps.add_series({
                "name": "='%s'!$%s$1" % (sheet_name, col),
                "categories": "='%s'!$A$2:$A$%d" % (sheet_name, last_row),
                "values": "='%s'!$%s$2:$%s$%d" % (sheet_name, col, col, last_row),
                "marker": {"type": "circle", "size": 5},
            })
        ws.insert_chart("S2", fps, {"x_scale": 1.45, "y_scale": 1.25})

        timing = workbook.add_chart({"type": "line"})
        timing.set_title({"name": "Workbook Tests: Full P95 Frame Time And Jank"})
        timing.set_y_axis({"name": "ms / %"})
        for col in ("D", "E"):
            timing.add_series({
                "name": "='%s'!$%s$1" % (sheet_name, col),
                "categories": "='%s'!$A$2:$A$%d" % (sheet_name, last_row),
                "values": "='%s'!$%s$2:$%s$%d" % (sheet_name, col, col, last_row),
                "marker": {"type": "circle", "size": 5},
            })
        ws.insert_chart("S20", timing, {"x_scale": 1.45, "y_scale": 1.25})

os.makedirs(os.path.dirname(output_path), exist_ok=True)
workbook.close()

verify = load_workbook(output_path, read_only=True, data_only=False)
error_tokens = ("#REF!", "#DIV/0!", "#VALUE!", "#NAME?", "#N/A")
matches = []
for sheet_name in ("Dashboard", "Rules", "Workbook Tests"):
    if sheet_name not in verify.sheetnames:
        continue
    ws = verify[sheet_name]
    for row in ws.iter_rows(values_only=True):
        for cell in row:
            if isinstance(cell, str) and any(token in cell for token in error_tokens):
                matches.append({"sheet": ws.title, "value": cell[:120]})
                if len(matches) >= 20:
                    break
        if len(matches) >= 20:
            break
    if len(matches) >= 20:
        break
print(json.dumps({
    "workbookPath": output_path,
    "sheets": verify.sheetnames,
    "sheetRows": {spec["name"]: len(spec["rows"]) for spec in model["sheets"]},
    "formulaErrorMatches": matches,
}, default=str))
`;
  const stdout = await runPython(script, [modelPath, WORKBOOK_PATH], "xlsxwriter workbook export");
  return JSON.parse(stdout);
}

async function main() {
  const { runRows, auditRows } = await collectPerformanceRows();
  const traceAuditRows = await collectTraceAuditRows();
  const frameCorrelationRows = await collectFrameCorrelationRows();
  const agentsText = await fs.readFile(AGENTS_PATH, "utf8");
  let ledgerRows = parseAgentsLedger(agentsText);
  const previous = await previousLedgerRows();
  if (ledgerRows.length < 10 && previous.length > ledgerRows.length) ledgerRows = [...ledgerRows, ...previous];
  ledgerRows = dedupeLedgerRows([...ledgerRows, ...previous, ...extraLedgerRows()]);
  const model = buildWorkbookModel(runRows, auditRows, traceAuditRows, frameCorrelationRows, ledgerRows);
  const exportResult = await exportWorkbookWithOpenpyxl(model);

  console.log(JSON.stringify({
    workbookPath: WORKBOOK_PATH,
    runRows: runRows.length,
    auditRows: auditRows.length,
    traceAuditRows: traceAuditRows.length,
    frameCorrelationRows: frameCorrelationRows.rows.length,
    ledgerRows: ledgerRows.length,
    formulaErrorMatches: exportResult.formulaErrorMatches,
    sheetRows: exportResult.sheetRows,
    sheets: exportResult.sheets,
    exporter: "xlsxwriter",
  }, null, 2));
}

await main();
