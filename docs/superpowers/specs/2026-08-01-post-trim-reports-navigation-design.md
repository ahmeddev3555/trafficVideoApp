# Post-Trim Submit Navigation Design

## Context

Today, tapping "Submit Report" on the Review screen navigates to a dedicated
`UploadScreen` that shows a spinner/progress bar while `UploadWorker` runs,
then a "Report Submitted!" success screen (with a "View My Reports" button)
or a "Upload Failed" screen (with "Retry"). Only after the user taps through
does the app land back on the Reports (`HistoryScreen`) list.

This is unnecessary ceremony: the underlying data layer already treats the
report as "real" the instant `startUpload()` runs — it's saved to Room with
`ReportStatus.UPLOADING` before the network call ever begins — and
`HistoryScreen`'s `ReportCard` already renders an "Uploading" status chip
and a retry action for `UPLOADING`/`UPLOAD_FAILED` reports. `UploadWorker`
itself (not any ViewModel) flips the row to `PENDING` or `UPLOAD_FAILED` on
completion, so the Reports list is already reactive to the whole upload
lifecycle via its live Room `Flow` — nothing needs to be built to make the
list "notice" a completed upload.

This change removes the intermediate screen entirely: submitting takes the
user straight back to Reports, where the new report is immediately visible
at the top of the list with an "Uploading" status.

## Scope & explicit decisions (confirmed with the user — do not re-litigate)

- **`UploadScreen.kt` and `UploadViewModel.kt` are deleted**, along with the
  `UPLOAD` route in `AppNavigation.kt`. `UploadWorker.kt` (the actual
  background worker) stays — both submit and retry use it.
- **Submission logic lives in a new `SubmitReportUseCase`**, mirroring the
  existing `RetryUploadUseCase` pattern, invoked from `ReviewViewModel`
  (previously stateless/display-only). This keeps business logic in a
  testable ViewModel/use case, consistent with the rest of the app, rather
  than embedding it in a Composable-local coroutine.
- **The cellular-data confirmation dialog appears on the Review screen**,
  before navigating away — reusing the existing `CellularConfirmDialog`
  composable (already used by both `UploadScreen` and `HistoryScreen`).
- **The Wi-Fi-only upload is enqueued immediately and unconditionally** when
  Submit is tapped, before the cellular dialog is even shown — matching the
  existing code's own safety rationale ("the report is never lost even if
  the cellular-confirmation prompt is ignored or dismissed"). The dialog is
  strictly an upgrade offer (switch to `NetworkType.CONNECTED`), never a
  gate on whether the report gets queued at all.
- **No in-flight cancel affordance.** The old UploadScreen's "Cancel" button
  is dropped, not relocated. Can be added to `ReportCard` later if it turns
  out to matter (YAGNI).
- **No progress percentage on the Reports list row.** Just the existing
  "Uploading" status chip — no new per-row WorkManager progress
  observation.
- **No toast/snackbar on submit.** The new row appearing at the top of the
  list (sorted `ORDER BY createdAt DESC`) immediately after navigating is
  the feedback.

## Architecture & data flow

```
ReviewScreen: tap "Submit Report"
  -> ReviewViewModel.submit()
       -> SubmitReportUseCase(trimmedFile, location, recordingStartedAt, durationMs):
            1. Generate reportId, build Report(status = UPLOADING), save via
               ReportRepository.saveReport(...)
            2. Enqueue UploadWorker.buildRequest(..., requireWifiOnly = true)
               unconditionally (WorkManager persists this even if the app
               is killed before the next step)
            3. Return whether the device is currently on Wi-Fi
       -> on Wi-Fi: fire the one-shot "submitted" event immediately
       -> not on Wi-Fi: set showCellularPrompt = true (dialog shown on
          Review screen)
            -> user confirms: ReviewViewModel.confirmCellularSubmit()
                 re-enqueues via UploadWorker.buildRequest(..., requireWifiOnly = false),
                 ExistingWorkPolicy.REPLACE (same pattern as
                 RetryUploadUseCase's forceCellular path), clears the
                 prompt, fires "submitted"
            -> user dismisses: ReviewViewModel.dismissCellularPrompt()
                 clears the prompt, fires "submitted" (the Wi-Fi-only
                 enqueue from step 1 already stands)
  -> ReviewScreen observes the "submitted" event via LaunchedEffect, calls
     onSubmit()
  -> AppNavigation's onSubmit callback: clear shared recording state
     (rawVideoFile, trimmedVideoFile, snapshotLocation = null, same as
     today's onUploadSuccess) and navigate:
       navController.navigate(Routes.HISTORY) {
           popUpTo(Routes.HISTORY) { inclusive = true }
       }
  -> HistoryScreen: already showing the new report at the top of the list
     (live Room Flow), status chip = "Uploading"
```

`UploadWorker` (unchanged) later flips the row to `PENDING` (success,
clearing the local video file) or `UPLOAD_FAILED` (after 3 retries) — the
Reports list picks this up automatically via its Flow, no new wiring
needed.

## Components touched

**New:**
- `app/src/main/java/com/trafficwatch/app/core/domain/usecase/SubmitReportUseCase.kt`
  — `suspend operator fun invoke(trimmedFile: File, location: LocationData?, recordingStartedAt: Long, durationMs: Long): SubmitReportResult`, where
  `SubmitReportResult` is a small data class carrying `reportId: String` and
  `onWifi: Boolean` (no failure case needed — unlike `RetryUploadUseCase`,
  there's no pre-existing file-missing risk to check, since the trimmed
  file was just produced in this same session).

**Modified:**
- `app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt`
  — gains `SubmitReportUseCase` and `NetworkMonitor` as constructor
  dependencies; new state fields `showCellularPrompt: Boolean` and a
  one-shot submitted signal (a `Channel<Unit>`/`SharedFlow<Unit>` exposed as
  a `Flow`, consistent with how one-shot navigation events are usually
  modeled — avoids the "event re-fires on rotation" bug a plain boolean
  would have); new function `submit()` (no parameters — reads
  `trimmedFilePath`, `location`, `recordingStartedAt`, and `durationMs`
  directly from the existing `uiState`, which `init()` already populates
  before the user can tap Submit), `confirmCellularSubmit()`,
  `dismissCellularPrompt()`.
- `app/src/main/java/com/trafficwatch/app/feature/review/ReviewScreen.kt`
  — "Submit Report" button's `onClick` calls `viewModel::submit` instead of
  the `onSubmit` callback directly; renders `CellularConfirmDialog` when
  `uiState.showCellularPrompt`; a `LaunchedEffect` collects the submitted
  signal and invokes `onSubmit()` when it fires.
- `app/src/main/java/com/trafficwatch/app/navigation/AppNavigation.kt`
  — the `REVIEW` composable's `onSubmit` lambda changes from
  `{ navController.navigate(Routes.UPLOAD) }` to clearing shared recording
  state and navigating to `Routes.HISTORY` with `popUpTo` as shown above.
  The `UPLOAD` composable block and `Routes.UPLOAD` constant are removed.

**Deleted:**
- `app/src/main/java/com/trafficwatch/app/feature/upload/UploadScreen.kt`
- `app/src/main/java/com/trafficwatch/app/feature/upload/UploadViewModel.kt`

**Unchanged:**
- `app/src/main/java/com/trafficwatch/app/feature/upload/UploadWorker.kt`
- `app/src/main/java/com/trafficwatch/app/feature/history/HistoryScreen.kt`
  and `HistoryViewModel.kt` — the existing "Uploading" chip, retry action,
  and cellular-retry dialog already handle everything this change needs
  from the list side.
- `app/src/main/java/com/trafficwatch/app/core/domain/usecase/RetryUploadUseCase.kt`

## Error handling & edge cases

- **Video file missing at submit time:** not checked in `SubmitReportUseCase`
  (unlike `RetryUploadUseCase`) — the trimmed file was just produced
  moments earlier in this same session, so this isn't a realistic failure
  mode here. If `UploadWorker` later can't find it for some other reason,
  the existing `Result.failure("Video file not found")` handling in
  `UploadWorker` applies unchanged.
- **Report row save fails:** no new handling added — same fire-and-forget
  risk tolerance as today's `UploadViewModel.startUpload`.
- **App killed between submit and cellular-dialog resolution:** safe, since
  the Wi-Fi-only work is already enqueued via WorkManager (which persists
  across process death) before the dialog is even shown.
- **Back button on Reports screen after submit:** Camera/Trim/Review are
  popped off the back stack at submit time (via `popUpTo(Routes.HISTORY)`),
  so there's no stale screen to return to.

## Testing

This app's existing test coverage is unit-level only (ViewModels, use
cases) — there's no existing UI/navigation test for `UploadScreen` or
`ReviewScreen` to update, and none is being added here, consistent with
existing coverage patterns (see `RegisterViewModelTest.kt` for the
MockK + `StandardTestDispatcher` pattern this project already uses; note
`RetryUploadUseCase`, the closest existing analog, currently has no test
file at all, so `SubmitReportUseCaseTest` will be new coverage, not a
modification).

- **`SubmitReportUseCaseTest`** (new): report saved with `UPLOADING` status
  and correct fields; work enqueued Wi-Fi-only (`requireWifiOnly = true`)
  unconditionally; return value's `onWifi` reflects `NetworkMonitor` state.
- **`ReviewViewModelTest`** (new): `submit()` on Wi-Fi fires the submitted
  event without showing the prompt; `submit()` off Wi-Fi sets
  `showCellularPrompt = true` and does not fire submitted yet;
  `confirmCellularSubmit()` re-enqueues with `requireWifiOnly = false` and
  fires submitted; `dismissCellularPrompt()` clears the prompt and fires
  submitted without re-enqueuing.
- **Manual verification:** record → trim → review → submit, confirm landing
  directly on Reports with the new row showing "Uploading", confirm it
  flips to "Pending" shortly after (real upload completing over Wi-Fi).
