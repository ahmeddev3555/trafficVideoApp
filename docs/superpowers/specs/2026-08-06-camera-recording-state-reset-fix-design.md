# Camera Recording-State Reset Fix Design

## Context

Backlog item (`docs/improvements-backlog.md`, "Navigation / UI flow", added
2026-08-04): tapping "+" on the Reports screen after a submission
incorrectly opens the Trim screen instead of the Camera screen. Root cause
was not identified when the item was filed; this design closes that gap.

## Root cause (confirmed via code inspection, not guessed)

`CameraController` (`app/src/main/java/com/trafficwatch/app/feature/camera/CameraController.kt`)
is `@Singleton`-scoped. Its `_recordingState: MutableStateFlow<RecordingState>`
(line 37) lives at the application level, not per screen-visit.
`CameraViewModel.recordingState` (`CameraViewModel.kt:49`) is a direct
pass-through of this same singleton flow - it is not the ViewModel's own
state, even though a fresh `CameraViewModel` is created per navigation to
`Routes.CAMERA`.

When a recording finishes, `VideoRecordEvent.Finalize` sets
`_recordingState.value = RecordingState.Finalizing(outputFile)`
(`CameraController.kt:133`). **Nothing resets this back to `Idle` after a
successful recording is consumed.** The only reset call,
`CameraViewModel.resetRecordingState()` -> `cameraController.resetState()`,
is invoked solely from `CameraScreen`'s `LaunchedEffect(Unit)` (`CameraScreen.kt:74-76`),
which runs *when entering* the Camera screen - racing against the *other*
effect, `LaunchedEffect(recordingState)` (`CameraScreen.kt:90-100`), which
fires `onVideoRecorded(...)` whenever it observes `Finalizing`.

Sequence that produces the bug:
1. User records a clip. `_recordingState` becomes `Finalizing(fileA)`.
   `CameraScreen` correctly navigates to Trim -> Review -> Submit.
2. The singleton's `_recordingState` is still `Finalizing(fileA)` - nothing
   ever cleared it after step 1's `onVideoRecorded` consumed it.
3. User taps "+" again. A fresh `CameraViewModel` is created, but it reads
   the *same* singleton `CameraController`'s state.
4. `CameraScreen` composes. Its very first read of `recordingState` is
   already `Finalizing(fileA)` - stale from the previous session.
5. `LaunchedEffect(recordingState)` immediately fires `onVideoRecorded(fileA, ...)`
   - before the user ever sees the camera preview, and using the **old**
   file, not a new recording. `AppNavigation.kt` sets `rawVideoFile` to
   the stale path and navigates straight to Trim.

This matches the reported symptom exactly: "+" skips Camera and lands on
Trim.

## Fix

Reset the singleton's `_recordingState` to `Idle` immediately after
`onVideoRecorded` consumes a `Finalizing` value - in the same effect that
consumes it, not in a separate, racy "reset on entry" effect. This closes
the gap at its source: the state can never be observed as stale `Finalizing`
by a later screen visit, because it's cleared at the exact moment it's used.

`CameraScreen.kt`'s `LaunchedEffect(recordingState)` block becomes:

```kotlin
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

The existing `LaunchedEffect(Unit) { viewModel.resetRecordingState() }`
(on-entry reset) stays as-is - a defensive backstop, matching this
codebase's existing pattern of defensive resets elsewhere (e.g.
`CameraViewModel.onStartRecording()`'s own "defensive cancellation"
comment). With the real fix in place it becomes a no-op in the normal
case (state is already `Idle` by the time the next entry happens), but
costs nothing to keep as a safety net for any path that doesn't go through
`LaunchedEffect(recordingState)` (e.g. a process-death/restore edge case).

No other files change. `CameraViewModel.resetRecordingState()` and
`CameraController.resetState()` already exist and need no modification -
only one new call site.

## Testing

`CameraController`/`CameraViewModel` have no existing unit test coverage
for `RecordingState` transitions (confirmed - no test file exists for
either). Given this is a Compose-effect-timing bug rather than a pure
function, and the codebase has no existing pattern for testing
`LaunchedEffect` ordering, verification is manual: build and install the
app, record and submit a full report end-to-end, then tap "+" again and
confirm the Camera preview appears (not Trim), with the record button
functional for a genuinely new recording. Repeat once more to confirm the
fix holds across multiple consecutive report submissions, not just the
first "+" after app install.

## Non-goals

- Items 2 (Trim Preview button) and 3 (upload progress indicator) from the
  same backlog section - separate, independent fixes per the confirmed
  decomposition, each getting its own spec/plan.
- Any broader refactor of `CameraController`'s singleton scoping - the
  singleton lifetime itself is appropriate (camera hardware binding is
  expensive and correctly shared), only the state-clearing gap is the bug.
