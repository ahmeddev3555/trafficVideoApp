# Camera Recording-State Reset Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the "+" button on the Reports screen opening the Trim screen instead of the Camera screen, by resetting `CameraController`'s singleton `recordingState` back to `Idle` immediately after a `Finalizing` value is consumed, instead of relying on a separate, racy "reset on entry" effect.

**Architecture:** One additional call (`viewModel.resetRecordingState()`) inside `CameraScreen.kt`'s existing `LaunchedEffect(recordingState)` block, right after `onVideoRecorded(...)` consumes a `Finalizing` state. No other files change - `CameraViewModel.resetRecordingState()` and `CameraController.resetState()` already exist.

**Tech Stack:** Kotlin, Jetpack Compose (existing Android app - no new dependencies).

## Global Constraints

- The existing `LaunchedEffect(Unit) { viewModel.resetRecordingState() }` (on-entry reset) must NOT be removed - it stays as a defensive backstop, per the design spec.
- No other file besides `CameraScreen.kt` is modified - `CameraViewModel.resetRecordingState()` and `CameraController.resetState()` already exist and need no changes.
- No automated test infrastructure exists for `RecordingState` transitions or `LaunchedEffect` timing in this codebase (confirmed in the design spec) - verification is manual, on a real device, not a unit test.

---

### Task 1: Reset recording state immediately after consuming it

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/feature/camera/CameraScreen.kt:90-100`

**Interfaces:**
- Consumes: `CameraViewModel.resetRecordingState(): Unit` (existing, `CameraViewModel.kt:184` - already calls `cameraController.resetState()`).
- Produces: nothing new - this is the only task in this plan.

- [ ] **Step 1: Make the fix**

In `app/src/main/java/com/trafficwatch/app/feature/camera/CameraScreen.kt`, find this exact block (currently lines 90-100):

```kotlin
    // Navigate forward once recording is finalised
    LaunchedEffect(recordingState) {
        if (recordingState is RecordingState.Finalizing) {
            onVideoRecorded(
                (recordingState as RecordingState.Finalizing).outputFile,
                viewModel.getSnapshotLocation(),
                viewModel.getRecordingStartedAt(),
                viewModel.getLocationSamples(),
                viewModel.getRotationSamples()
            )
        }
    }
```

Replace it with:

```kotlin
    // Navigate forward once recording is finalised. Immediately resets the recording
    // state back to Idle after consuming it - CameraController's recordingState is
    // @Singleton-scoped (survives across separate CameraViewModel instances), so leaving
    // it at Finalizing would make the NEXT visit to this screen immediately re-fire this
    // same effect with the stale, previous recording before the user ever sees the camera
    // preview. Resetting here, at the point of consumption, closes that race at its
    // source - the separate on-entry reset below remains as a defensive backstop only.
    LaunchedEffect(recordingState) {
        if (recordingState is RecordingState.Finalizing) {
            onVideoRecorded(
                (recordingState as RecordingState.Finalizing).outputFile,
                viewModel.getSnapshotLocation(),
                viewModel.getRecordingStartedAt(),
                viewModel.getLocationSamples(),
                viewModel.getRotationSamples()
            )
            viewModel.resetRecordingState()
        }
    }
```

- [ ] **Step 2: Confirm the app still builds**

Run: `cd app/.. && ./gradlew.bat :app:assembleDebug` (from the repo root; on Windows use `.\gradlew.bat`)
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/camera/CameraScreen.kt
git commit -m "fix: reset camera recording state immediately after consuming it

CameraController's recordingState is @Singleton-scoped, so leaving it
at Finalizing after a successful recording meant the next visit to
the Camera screen immediately re-fired onVideoRecorded with the
stale, previous clip before the camera preview ever rendered - the
root cause of the \"+ button opens Trim instead of Camera\" bug."
```

- [ ] **Step 4: Install on device and manually verify**

Run: `.\gradlew.bat :app:installDebug` (device must be connected via adb - run `adb devices` first to confirm)

Manual verification steps:
1. Open the app, record a short clip, trim it, and submit the report all the way through to completion (back on the Reports/History screen).
2. Tap the "+" button.
3. **Expected**: the Camera screen appears with a live preview and a working record button - NOT the Trim screen.
4. Record a second clip, trim it, and submit it too.
5. Tap "+" again.
6. **Expected**: the Camera screen appears correctly a second time as well (confirms the fix holds across multiple consecutive submissions, not just the first one after app install/last stale state).
