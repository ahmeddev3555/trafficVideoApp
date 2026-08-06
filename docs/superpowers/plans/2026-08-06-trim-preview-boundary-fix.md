# Trim Preview Boundary Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Trim screen's "Preview" button actually preview the selected trim window — playback bounded to `[trimStartMs, trimEndMs]` and looping within that window — instead of playing past the selection into the rest of the raw clip.

**Architecture:** Add local Compose state to `TrimScreen.kt` (an incrementing `previewToken`) and a `LaunchedEffect` that polls `exoPlayer.currentPosition` every 150ms while playing, seeking back to `trimStartMs` whenever it reaches `trimEndMs`. Scoped only to playback triggered by the Preview button — the built-in ExoPlayer controls (`PlayerView(useController = true)`) are unaffected. No `TrimViewModel`/`TrimUiState` changes.

**Tech Stack:** Kotlin, Jetpack Compose, Media3 ExoPlayer (existing Android app — no new dependencies).

## Global Constraints

- Boundary enforcement applies **only** when triggered via the Preview button — not to playback started via the built-in ExoPlayer controls. This is a confirmed, deliberate scope limit, not a gap.
- At `trimEndMs`, playback **loops back to `trimStartMs` and keeps playing** — it does not stop/pause.
- No new `TrimViewModel`/`TrimUiState` fields — this is presentation-only state local to `TrimScreen.kt`, matching the file's existing pattern for ExoPlayer wiring (e.g. `videoAspectRatio`).
- No automated test infrastructure exists for ExoPlayer wiring or `LaunchedEffect` timing in this codebase — verification is manual, on a real device, not a unit test.

---

### Task 1: Bound and loop Preview-button playback within the trim window

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/feature/trim/TrimScreen.kt`

**Interfaces:**
- Consumes: `uiState.trimStartMs: Long`, `uiState.trimEndMs: Long` (existing `TrimUiState` fields, already used elsewhere in this file, e.g. lines 256-262), `exoPlayer: ExoPlayer` (existing `remember`-scoped instance, lines 74-85).
- Produces: nothing new — this is the only task in this plan.

- [ ] **Step 1: Add the two missing imports**

In `app/src/main/java/com/trafficwatch/app/feature/trim/TrimScreen.kt`, find this existing import block (around lines 36-39):

```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
```

Add `mutableIntStateOf` alongside the existing `mutableFloatStateOf` import, and add `kotlinx.coroutines.delay` (needed for the polling loop in Step 3 — this file has no existing coroutine-delay import to reuse):

```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
```

- [ ] **Step 2: Add the `previewToken` state**

Find this existing line (around line 72):

```kotlin
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }
```

Add the new state variable directly after it:

```kotlin
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }

    // Incremented on every Preview-button tap to restart the bounding effect below with a
    // fresh coroutine (so a re-tap mid-preview picks up the current trim window immediately,
    // even on consecutive taps where the value itself wouldn't otherwise change).
    var previewToken by remember { mutableIntStateOf(0) }
```

- [ ] **Step 3: Add the bounding/looping effect**

Find the existing scrub-position effect (around lines 111-124):

```kotlin
    // Freeze on the scrubbed frame while a handle is being dragged. Seeking uses the
    // nearest sync (key) frame instead of an exact decode while scrubbing, since exact
    // seeks are too slow to keep up with rapid drag events on a local file — the frame
    // shown during a drag is just a visual aid and never affects the actual trim points.
    LaunchedEffect(uiState.scrubPositionMs) {
        val positionMs = uiState.scrubPositionMs
        if (positionMs != null) {
            exoPlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            exoPlayer.pause()
            exoPlayer.seekTo(positionMs)
        } else {
            exoPlayer.setSeekParameters(SeekParameters.EXACT)
        }
    }
```

Add the new effect directly after it:

```kotlin
    // Bounds Preview-button playback to [trimStartMs, trimEndMs], looping within that
    // window rather than continuing into the rest of the raw clip. Scoped only to
    // Preview-triggered playback (previewToken > 0) - the built-in ExoPlayer controls
    // (PlayerView's useController = true) are unaffected and can still play/scrub the
    // full raw video, per the confirmed design scope. The loop self-terminates on
    // !exoPlayer.isPlaying for any reason (manual pause via built-in controls,
    // navigating away and disposing this composable, etc.) so pausing never fights an
    // unwanted auto-resume - tapping Preview again starts a fresh bounded session.
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

- [ ] **Step 4: Wire the Preview button to bump `previewToken`**

Find the existing Preview button (around lines 286-292):

```kotlin
                OutlinedButton(
                    onClick = {
                        exoPlayer.seekTo(uiState.trimStartMs)
                        exoPlayer.play()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Preview") }
```

Replace its `onClick` body:

```kotlin
                OutlinedButton(
                    onClick = {
                        exoPlayer.seekTo(uiState.trimStartMs)
                        exoPlayer.play()
                        previewToken++
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Preview") }
```

- [ ] **Step 5: Confirm the app still builds**

Run: `.\gradlew.bat :app:assembleDebug` (from the repo root)
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/trim/TrimScreen.kt
git commit -m "fix: bound and loop Trim Preview playback within the selected window

The Preview button previously seeked to trimStartMs and played with
no upper bound, continuing past the selection into the rest of the
raw clip. A polling LaunchedEffect now loops playback within
[trimStartMs, trimEndMs] for as long as Preview-triggered playback
continues, scoped only to the Preview button per the confirmed
design - the built-in ExoPlayer controls are unaffected."
```

- [ ] **Step 7: Install on device and manually verify**

Run: `.\gradlew.bat :app:installDebug` (device must be connected via adb — run `adb devices` first to confirm)

Manual verification steps:
1. Record (or reuse) a clip longer than the max trim window so a real sub-selection exists.
2. On the Trim screen, adjust the selection window away from the very start of the clip.
3. Tap "Preview".
4. **Expected**: playback starts at the selection's start, plays through to the selection's end, then jumps back to the selection's start and keeps playing — repeating indefinitely — without ever playing content past `trimEndMs` into the rest of the raw clip.
5. While it's looping, tap "Preview" again.
6. **Expected**: playback restarts cleanly at the selection's start and continues looping correctly (confirms re-tapping mid-preview doesn't break the loop).
7. While it's looping, drag the built-in ExoPlayer seek bar (the native controls overlaid on the video, not the app's `TrimWindowScrubBar`) to a position past `trimEndMs`, while playback is still active.
8. **Expected**: within ~150ms, playback snaps back into the loop (seeks to `trimStartMs` and continues looping) rather than continuing past `trimEndMs`. Confirmed as the intended behavior (2026-08-06): a Preview-triggered session stays bounded for its whole duration, including through manual scrubbing, until playback actually stops (pause, or navigating away) — "scoped only to the Preview button" means the *session* must originate from a Preview tap, not that every individual position change within that session is exempt if it came from the built-in controls.
9. Tap the built-in controls' pause button while it's looping.
10. **Expected**: playback stays paused — it does not auto-resume/auto-loop on its own (the polling loop's `while (exoPlayer.isPlaying)` exits once paused).
11. Tap "Preview" again after the pause.
12. **Expected**: a fresh bounded session starts cleanly at `trimStartMs`.
