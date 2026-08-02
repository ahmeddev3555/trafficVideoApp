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

## Direction analysis (compass + moving camera)

**Area:** `app/src/main/java/com/trafficwatch/app/core/util/CompassProvider.kt`
(single-snapshot capture side), `server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt`
(`absoluteBearingDegrees = (compassHeadingDegrees + frameBearing) % 360.0`)

- **Absolute bearing conversion assumes a stationary camera; recordings from
  a moving vehicle can produce a wrong-way verdict miss even when both the
  vehicle detection and the frame-relative tracking are correct.** Root-caused
  via a real diagnosed clip: the frame-relative bearing computed for a
  confirmed wrong-way motorcycle (171°, from a 10-point/~1.1s tracked
  trajectory) checked out as numerically solid — 7 of 9 consecutive
  frame-to-frame segments clustered tightly around it. But converting it to
  an absolute compass bearing via a single compass reading taken once at
  recording start assumes that reading describes the camera's orientation
  for the whole clip. In this report, the device's own submitted GPS
  metadata showed `bearing: 0.0°, speed: 0.0 m/s` at that same instant
  (car likely stationary or GPS not yet locked when the snapshot was
  taken), while the video shows a busy, fast-moving multi-lane road
  throughout — strongly suggesting the vehicle started moving sometime
  during the clip, invalidating the single static compass snapshot as a
  stand-in for the whole recording's camera orientation. Independent
  corroboration: normal, correctly-flowing traffic's own frame-relative
  bearings clustered around 245-287° (mostly lateral/"leftward" apparent
  motion) rather than near 0° (straight-ahead-and-receding, what you'd
  expect from a truly stationary camera pointed down a straight road) -
  consistent with the camera itself actively moving during the clip.
  This is the same "designed for a stationary bystander, not a moving
  dashcam" gap already noted for corridor clustering/cohesion
  (`docs/superpowers/specs/2026-08-02-corridor-cohesion-locality-fix-design.md`)
  and the displacement floor
  (`docs/superpowers/specs/2026-08-02-displacement-floor-bbox-relative-fix-design.md`),
  but showing up here in the bearing conversion itself rather than in
  clustering. Real fix is a bigger effort than those two - likely either
  continuous compass sampling throughout the clip (not just at recording
  start) so the true camera orientation is known at the moment each
  vehicle's bearing is measured, or a different direction-determination
  approach that doesn't depend on a single static reading at all.
  *(added 2026-08-02)*
