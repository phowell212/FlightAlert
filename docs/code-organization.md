# FlightAlert Code Organization

FlightAlert should read like objects doing jobs.

`FlightMapView` is the air traffic controller. It coordinates visible systems, selected aircraft, drawing order, and user input. It should not personally own feed parsing, route validation, photo lookup, impact scoring, settings math, or feature-specific business rules.

## Rules

- Give every major feature its own file or small package.
- Keep settings and tuning values in explicit settings files.
- Keep Kotlin files around 2,000 lines or less unless the file has a clear reason to be larger.
- Treat about 3,000 lines as a hard maximum; a file above that should be split before new feature work continues.
- Prefer names that describe the object doing the work: `CurrentRouteValidator`, `AircraftDetailsClient`, `FlightMapSettings`.
- Add a one-line comment before complicated code only when the purpose is not obvious from the names.
- Do not add comments that restate the line below them.
- New features should start as a new object/file with a narrow public method, then be called by the coordinator.

## Target Packages

- `data`: external feeds, DTOs, parsing, route sources, traces, aviation layers.
- `settings`: persisted app preferences and defaults.
- `service`: Android background service orchestration.
- `ui.map`: the map coordinator and shared map models.
- `ui.map.route`: current-flight route checks and route presentation rules.
- `ui.map.settings`: map-specific tuning values.
- `ui.map.details`: aircraft details panel rows, photo state, and detail formatting.
- `ui.map.render`: canvas drawing helpers for aircraft, panels, labels, paths, and map layers.
- `ui.map.input`: tap, pinch, scroll, and gesture handling.
- `ui.map.impact`: environmental impact estimates and comparisons.

## Refactor Order

1. Extract pure business rules first: route validation, impact scoring, usage stats.
2. Extract presentation builders next: details rows, settings rows, traffic rows.
3. Extract renderers after the data shape is stable.
4. Leave `FlightMapView` as the coordinator that wires state, renderers, and feature objects together.
