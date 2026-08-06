# Trim Preview Boundary Fix Design

## Context

Backlog item (`docs/improvements-backlog.md`, "Navigation / UI flow", added
2026-08-04, item 2): the Trim screen's "Preview" button doesn't actually
preview the trimmed clip. `TrimScreen.kt`'s `ExoPlayer` is loaded with the
raw (untrimmed) recording; the Preview button
(`TrimScreen.kt:321-328`) does only:

```kotlin
onClick = {
    exoPlayer.seekTo(uiState.trimStartMs)
    exoPlayer.play()
}
```

It seeks into the raw video at the selection's start but never stops or
loops at `uiState.trimEndMs` — playback continues past the selection into
the rest of the raw clip, and `repeatMode = Player.REPEAT_MODE_ONE`
(`TrimScreen.kt:84`) loops the *whole raw file*, not the selected window.

Root cause was already understood when this item was filed (unlike item 1)
— this is a design-choice fix, not an investigation.

## Approach

Two candidates were considered:

- **(a) Bound playback to `[trimStartMs, trimEndMs]` on the existing
  raw-video player.** The actual trim output is just a time-windowed view
  of the same raw source frames, so this achieves the same user-facing
  result with no re-encode and no latency.
- **(b) Run the real trim operation first and preview the actual output
  file.** More faithful to "what will actually be submitted," but slower —
  trimming takes real time — and would need eager re-trimming on every tap
  or a cache-invalidate-on-selection-change scheme.

**Chosen: (a).** No new dependency on `TrimVideoUseCase`/`VideoTrimmer` for
a preview path, no latency added to tapping Preview, and visually
indistinguishable from the real output.

**Scope of enforcement (confirmed with user):** the boundary is enforced
**only** for playback triggered via the Preview button — not for the
built-in ExoPlayer controls exposed by `PlayerView(useController = true)`
(`TrimScreen.kt:210-218`), which remain free to scrub/play the full raw
video as they do today outside of an active Preview session — see Scope
clarification below for the in-session case. This is the narrower of two
options discussed; the broader "always enforce while playing" option was
not chosen.

## Fix

Add local Compose state to `TrimScreen.kt` (no `TrimViewModel`/`TrimUiState`
changes — this is presentation-only, matching the file's existing pattern
of keeping ExoPlayer wiring local to the composable):

```kotlin
var previewToken by remember { mutableIntStateOf(0) }
```

Preview button's `onClick` becomes:

```kotlin
onClick = {
    exoPlayer.seekTo(uiState.trimStartMs)
    exoPlayer.play()
    previewToken++
}
```

A new effect polls playback position and loops it back to `trimStartMs`
whenever it reaches `trimEndMs`, for as long as the player keeps playing:

```kotlin
LaunchedEffect(previewToken) {
    if (previewToken == 0) return@LaunchedEffect
    while (exoPlayer.isPlaying) {
        if (exoPlayer.currentPosition >= uiState.trimEndMs) {
            exoPlayer.seekTo(uiState.trimStartMs)
        }
        delay(150)
    }
}
```

**Behavior at `trimEndMs`: loop back to `trimStartMs` and keep playing**
(not stop-and-pause) — matches common trim-preview conventions (Instagram/
TikTok-style clip preview) and lets the user watch the selection repeatedly
without re-tapping Preview.

**Why a polling loop, not a listener:** Media3's `Player.Listener` has no
continuous "position changed during playback" callback — only
discontinuity/state-change events. A short-interval poll (150ms, well under
human perception for a loop-back) is the standard, simple way to watch
`currentPosition` during normal playback and is self-contained to this one
effect.

**Why keyed on an incrementing counter, not `Unit`:** `LaunchedEffect`
restarts its coroutine only when its key changes. Re-tapping Preview while
already mid-preview must restart the poll cleanly (in particular, so it
picks up a `trimStartMs`/`trimEndMs` that may have changed since the last
tap) — an `Int` key that increments on every tap guarantees a fresh
coroutine launch each time, including consecutive taps.

**Why the loop self-terminates on `!exoPlayer.isPlaying`, not a separate
"stop enforcing" flag:** any reason playback stops — the user pausing via
the built-in controls, navigating away (which disposes the composable and
cancels the effect via structural concurrency), or the screen being torn
down — should stop the enforcement with it. Checking `isPlaying` each
iteration means pausing to look at a frame doesn't fight an unwanted
auto-resume; tapping Preview again cleanly starts a new bounded session.

**Known minor edge case, not fixed:** if the selection's end coincides
exactly with the end of the raw video (`trimEndMs == totalDurationMs`),
the poll's `seekTo(trimStartMs)` and ExoPlayer's own `REPEAT_MODE_ONE`
end-of-file restart (to position `0`, not `trimStartMs`) race within the
same ~150ms window. Both outcomes result in playback looping rather than
stopping, and the window is a single frame at most — not worth the
added complexity of temporarily swapping `repeatMode` for this narrow case.

## Testing

No existing test infrastructure covers ExoPlayer wiring or
`LaunchedEffect` polling in this codebase (same situation as item 1 — this
is Compose-effect/player-state behavior, not a pure function). Verification
is manual: build, install, open Trim on a clip longer than the max window,
select a sub-window, tap Preview, and confirm playback loops within
`[trimStartMs, trimEndMs]` repeatedly without drifting into the rest of the
raw clip. Also confirm: tapping Preview again mid-loop restarts cleanly;
dragging the built-in ExoPlayer seek bar past `trimEndMs` while a Preview
session is still playing gets pulled back into the loop within ~150ms
(the boundary applies to the whole session once triggered by Preview, not
just the moment it was tapped - see the scope clarification below);
pausing via the built-in controls stops the loop-back until Preview is
tapped again.

**Scope clarification (resolved 2026-08-06, during task review):** "scoped
only to the Preview button" means a bounded session must *originate* from a
Preview tap - it does not mean every individual seek within that session is
exempt if it happens to come from the built-in controls. The implementation
has no way to distinguish "played naturally to `trimEndMs`" from "user
manually scrubbed past `trimEndMs` via the built-in seek bar while a
Preview session is still active" (both look identical: `currentPosition`
crossed `trimEndMs` while `isPlaying` is true) - and there is no need to
add that distinction. Once Preview is tapped, the session stays bounded
until playback actually stops (pause or navigating away); only playback
that was never Preview-triggered in the first place (`previewToken == 0`)
is exempt from the boundary.

## Non-goals

- Enforcing the boundary for playback triggered any other way than the
  Preview button (explicitly descoped per user's choice).
- Running the real trim operation to preview the literal output file
  (approach (b), not chosen).
- Item 3 (upload progress indicator) — separate, independent fix per the
  confirmed decomposition.
