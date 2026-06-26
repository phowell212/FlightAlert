# Performance Debugging

Flight Alert keeps production runtime free of developer-only timing summaries,
perf intent switches, and per-frame diagnostic counters. Use external tools for
performance work:

- Android Studio Profiler: inspect CPU, memory allocations, network, and energy
  while exercising normal app flows.
- Perfetto or Android System Trace: capture frame timing, Choreographer slices,
  render thread behavior, Binder work, and scheduling stalls.
- Android Studio MCP bridge: inspect renderer/cache state through the IDE or
  debugger without adding hot-path app strings or counters.
- Logcat: collect existing user-facing/network/status logs from debug builds
  only; do not add production per-frame timing strings for investigations.

For visual rendering regressions, keep using video evidence from the device so
flicker, popping, and aircraft continuity are judged from motion rather than a
single screenshot.
