# Improvements Backlog

Ideas identified during development that aren't scheduled yet. Grouped by
project area. **When work touches a listed area, check this list and
surface the relevant item(s) before/while working**, so they can be
considered rather than forgotten.

---

## Camera / Recording

**Area:** `app/src/main/java/com/trafficwatch/app/feature/camera/`

- **Allow zoom while recording.** No zoom control exists today. Explicitly
  deferred out of scope during the 2026-08-01 motorcycle-detection fix
  (trade-off: zoom narrows the field of view, which the corridor-consensus
  direction-analysis logic depends on seeing multiple vehicles across a
  wide lane to establish a reliable "normal flow" baseline — see
  `docs/superpowers/specs/2026-08-01-motorcycle-detection-resolution-fix-design.md`).
  Revisit alongside any future change to how corridor consensus is
  computed, or if users specifically ask for it again.
  *(added 2026-08-02)*

## Location / GPS accuracy

**Area:** `app/src/main/java/com/trafficwatch/app/core/util/LocationUtil.kt`
(capture side), `server/src/main/kotlin/com/trafficwatch/server/geo/`
(OSM street resolution side)

- **Prompt the user to confirm/correct the exact location on a map when GPS
  accuracy is weak** (e.g. accuracy > 10m). Confirmed real-world impact:
  a submitted report with 37.7m GPS accuracy resolved to the wrong OSM
  street ("Street 4", a small residential road) instead of the actual road
  in the video ("Khayaban-e-Jinnah", a major arterial) — the true position
  was ~56m from the reported point, exceeding both the phone's own accuracy
  estimate and the server's fixed 50m Overpass search radius. The server
  currently searches a flat 50m radius around the raw GPS point regardless
  of how uncertain that point actually is. A map-confirmation prompt (or,
  alternatively, widening the search radius to scale with reported
  accuracy) would directly address this.
  *(added 2026-08-02)*
