# M4 and M5 slice 2 device results: daily proposal and approval, migration, and recovery

Date: 2026-10-03 21:10 to 2026-10-04 10:37 phone time (Asia/Kuala_Lumpur, UTC+8)
Milestone: the minimal part of M4 and M5, ADR 0009 slice 2
Branch: `spike/m3a-gemini-live`. Base implementation commit: `98dd3b4`. Two day-switch fixes made after it were uncommitted when this document was written (see [Day-switch defects](#day-switch-defects-and-their-fixes)).
Design: [ADR 0009](adr/0009-minimal-planning-loop.md), slice 2

## The answer

ADR 0009's slice 2 asked whether the daily proposal, Adjust, Approve and Keep original work on the Pixel 10 against a real SQLCipher database, whether the schema 1 to 2 migration and the schema 2 export recover, and whether M2 is left alone.

**Yes, with the limitations below.** Slice 2 is accepted on the Pixel 10.

- `com.thalia.app.debug` was updated in place with data kept. Planning opened without error, and M2's `checkins.db`, `-wal` and `-shm` stayed byte-identical to their baselines after every install and every approval.
- Five plan revisions were approved across four test days. Both day-switch defects found on the way were fixed and passed a focused retest.
- Recovery worked for the original schema 1 export, the pre-upgrade backup and the final schema 2 data, in the isolated `com.thalia.app.recoverycheck` app.

This accepts slice 2 only. **Slice 3 is not started. The minimal loop, and full M4 and M5, are not complete.**
This is one device, one Android build, one tester and one run of each test.

## What was tested

| Item | Value |
|---|---|
| Device | Pixel 10 (`frankel`), serial redacted, Android 17, build `CP3A.260905.009`, over USB or wireless adb |
| Base commit | `98dd3b43a528e8fcd1e4ff692b2e97a17a3e5d14` (2026-10-03 18:12), checked at Stage 0 and Stage 3 |
| First slice 2 build | `0af0257a8c418cc7a0899693446dff825f8a624f4b3b85191a566b974a4a192f`, installed 2026-10-03 21:55:05 |
| **Final debug build** | **`82854ba42e819fb732ce04a4c890f6fd40f9d6393714e7847d173a684498e5cd`**, installed 2026-10-04 07:26:29, verified on the phone again on 2026-10-04 at 10:29 and 10:36 |
| Signing certificate | `FB:33:48:FE:12:DA:C4:E0:25:CC:4A:76:8E:63:ED:19:DB:4A:65:C2:61:E5:6C:A2:12:6D:A0:20:70:6D:31:DD` (`CN=Android Debug`), `apksigner` verified; the same on every build |
| Isolated app | `com.thalia.app.recoverycheck`, `d3746cb373da47d916ed05e622a4d4bc33e94f630ab7a83c5c78877636fbbbd7`, version `0.1-m1-recoverycheck`, no `INTERNET` permission, uninstalled at the end |
| Real app install | `firstInstallTime` 2026-09-19 17:08:50, unchanged throughout. No clear, uninstall, force-stop or downgrade of `com.thalia.app.debug`. |

## Results

| Stage | Result |
|---|---|
| 0 and 1. Baselines and pre-upgrade backup | `checkins.db` copied consistently as baselines B0 and B1. The real app's schema 1 planning data was exported to `debug-pre-slice2-schema1-2.thaliaexport` (614 bytes, `e58035f5…fb25`), phone and Mac copies equal; the app's read-back reported direction, settings, 0 goals, 0 projects, 0 actions. |
| 2A. Original schema 1 export | The Slice 1 acceptance export (`69003b71…c235`, unchanged) restored into a fresh recoveryCheck: the preview and the "Restored" message reported direction, settings, 1 goal, 2 projects, 2 actions, and the visible rows matched the Slice 1 seed. Fingerprint `0bab16eba0d68abb5eebeccf50cf2489`, plans 0, items 0. It differs from Slice 1's fingerprint because slice 2 encodes rows under schema 2, which is not a comparison criterion. |
| 2B. Pre-upgrade backup recovery | recoveryCheck cleared, then the pre-upgrade backup imported: "Restored: direction, settings, 0 goals, 0 projects, 0 actions", with the direction text, 12:00 to 22:00 window and 04:00 rollover as in Stage 1. Fingerprint `7c77328d836e6260f340fd6f414cca65`. |
| 3. In-place migration | The first slice 2 build was installed over the Slice 1 build with `pm install -r` after an `adb install` attempt failed when adb dropped. The failed attempt left all six database files and the sealed key identical to B3 (size, mtime, hash). After the successful install planning opened, direction, window and rollover were as before, and Plan a day queried the new tables. M2 was identical to B3. |
| 4. Planning acceptance | Synthetic data, all prefixed "S2 test": 4 projects, 8 actions. Four revisions were approved on three days (2026-10-05 revisions 1 and 2, 10-06 revision 1, 10-07 revision 1), covering the preview, Adjust, Keep original, an overload preview, a stale-proposal refusal, "Approve with N minutes unresolved", a rename after a snapshot (the older revision keeps the old title) and a rest day. M2 was identical after each approval, with 0 live occurrences and 0 pending Thalia alarms. |
| Day-switch retest | Passed on the final build. See below. A fifth revision, a rest day for 2026-10-08, was approved during it. |
| 5. Final recovery | Passed. See below. |

### Migration, as evidence allows

Confirmed by observation: planning opened with no error on the real SQLCipher database and the Plan a day section read the plan tables. Confirmed from file structure only: when planning first opened, one transaction of 9 frames was committed to `planning.db-wal` and the database grew from 12 to 19 pages, with the main file and sealed key unchanged and no set-aside folder or journal.
**Inferred, not observed:** that this transaction is `MIGRATION_1_2` and the stored version is now 2. `planning.db` is encrypted, so `user_version` and the schema were not read. The basis is that the production builder registers the migrations with no destructive fallback and Room refuses to open a schema 1 file at version 2 without one.

## Day-switch defects and their fixes

Both were found during Stage 4 and recorded as observations, with no data harmed.

1. **A day's window carried into the next day's editor.** After a day had a window of its own, the editor on the next day started from that window instead of the default.
2. **A success message persisted across day navigation.** "Plan approved" stayed visible after Earlier day or Later day.

The fixes are in `PlanDaySection.kt`: the form reset now reloads the default window texts, through a small `defaultWindowTexts` function, and the day buttons clear the message. They were **uncommitted** when this document was written, together with `PlanDayWindowFieldsTest` (2 tests). That test covers the default texts only; **the message clearing has no unit test** and rests on the device retest.

**Checks and build after the fix, 2026-10-04, on the Mac, one Gradle task at a time with the daemon stopped after each.** Evidenced by files on disk and by the command output in the session transcript of the time, which was not copied into an evidence folder:
- The two source files were last modified at 06:59:09 and have not changed since; the saved patch (`backups/daysfix.patch`) equals the current diff of `app/src/main`.
- Focused run: `PlanDayWindowFieldsTest` 2 tests and `PlanPreviewTextTest` 5 tests, all passed, at about 06:59.
- `:app:ktlintCheck` at about 06:59 reported success, but only the test source set was re-linted (the main source set task was UP-TO-DATE). That gap was closed later: see the next bullet.
- **Lint rerun, 2026-10-04 at 10:47,** `./gradlew :app:ktlintCheck --rerun-tasks`, then `./gradlew --stop`: BUILD SUCCESSFUL, **15 actionable tasks, all executed**, including `runKtlintCheckOverMainSourceSet` and the test, debug-test and release-test source set checks, with no violations reported; the rest were skipped or had no source. The sources were byte-identical before and after (`PlanDaySection.kt` `a5f4c6a1…38f5`, `PlanDayWindowFieldsTest.kt` `e7b8a524…f5f9`) and `HEAD` was `98dd3b4`. Output and hashes are in `the author's private evidence folder (not published)`. This lints the current sources of the `:app` module, which are the sources of the tested build; it is not a lint of the other modules.
- Full `:app:testDebugUnitTest` finished at 07:00:20: **538 tests, 0 failures, 0 skipped**, 39 suites, read from the result files.
- `:app:assembleDebug` wrote `app-debug.apk` at 07:00:29: 42,074,666 bytes, SHA-256 `82854ba4…e5cd`, `apksigner` verified, signer certificate as above. The same hash is in `daysfix-prep-20261004/SHA256SUMS.txt` and was read on the phone.
- **Not rerun after the fix:** `:core:test` (module untouched), `:app:testReleaseUnitTest`, `ktlintCheck` of the other modules, and `:app:assembleRecoveryCheck`.
- **Provenance, not reproducibility:** the tested artifact is identified by its hash. It was built from the working tree at `98dd3b4` plus that patch, with only the patch, the new test and two documentation files changed at the time. An identical rebuild is not claimed and was not checked.

Retest on `82854ba4…` (evidence `daysfix-retest-20261004-0717/90-results.txt`), all passed: a fresh editor shows 12:00 and 22:00 (weak, since it was also true before the fix); a day with an approved 12:00 to 14:00 window shows 12:00 and 22:00 in the editor; typing 15:00 and 17:00 on 2026-10-08 and switching days both ways gives 12:00 and 22:00 (the real regression check); Adjust loads 13:00 and 21:00 and Keep original returns the editor to the default with revision 2 still in force; the 10-08 rest day was approved at 12:00 to 22:00, and its message was gone after a day switch; earlier days were identical to their pre-fix reads. Not retested: returning to 10-08 after the message cleared.

## Recovery of the final schema 2 data

The real app was exported on the final build to `thalia-stage5-final-20261004.thaliaexport` (11,010 bytes, SHA-256 `bcf1e9f5a4fda3e06d05182b4f207c8287a5d62fe0ed6128da43dbf859ff1808`), phone and Mac copies equal and re-verified later. The user typed the password privately.

| Check | Result |
|---|---|
| Fresh recoveryCheck import, fingerprint **A** | `5bafc5146ef00c3a0626e8b1e369115b`, counts **direction 1, settings 1, goals 0, projects 4, actions 8, plans 5, items 17** (1/1/0/4/8/5/17), as expected from the recorded approvals |
| Latest plan per day, compared by screen text | 2026-10-05 revision 2 (13:00 to 21:00, one pin, four queued, three left out), 10-06 revision 1 (does not fit), 10-07 and 10-08 revision 1 (rest days) all match the expected values |
| Export B from the restored data, clear, reimport, fingerprint | `5bafc5146ef00c3a0626e8b1e369115b`, same counts, **equal to A** |
| Seeded dataset **S**, before export | `b37ddecaee15fd48392d443e3630baaa`, counts 1/1/1/2/2/2/3, read twice |
| Export of S, `pm clear` of recoveryCheck only, import, fingerprint | `b37ddecaee15fd48392d443e3630baaa`, same counts, **equal to S** |

**The three equalities prove different things.**
- **A equal to B** shows recovery stability: a restore followed by an export and a second restore gives the same database.
- **S equal to restored S** shows fidelity: the seeded dataset, with two plan revisions, was fingerprinted before export and again after restore, so it compares with the original rows. This holds for the seeded data only.
- **The real app's own rows were never fingerprinted,** because the real app has no fingerprint. A is a fingerprint of data already restored once.

**The first import's "Restored:" message was not captured** (a stray screenshot was removed because it was taken with the real app in front). The restoration is established by the preview taken before it (4 projects, 8 actions, an empty phone), the plan screens read afterwards, and the fingerprint with counts 1/1/0/4/8/5/17, taken from the database itself.

## Preserved synthetic data

The real app keeps its synthetic test data: the direction left from Slice 1, the 12:00 to 22:00 window, 4 "S2 test" projects, 8 actions and five approved revisions across four days, 2026-10-05 to 2026-10-08. It was not removed. Its approved plans are immutable and were left as they are, including **2026-10-07's original carried window of 12:00 to 14:00**, which came from the first defect and is a valid record of what was approved. The M3A reservation ledger (three attempts) is untouched.

## M2 isolation

Every M2 snapshot compared against the baselines was logically and byte-identical: `checkins.db`, `-wal` and `-shm` hashes `ee15b77d…e18f`, `964259ed…478e` and `2808dbbc…32d4`, integrity ok, user_version 4, 33 occurrences with every column identical, 0 live, 0 pending Thalia alarms, no Thalia service. The final snapshot was taken on 2026-10-04 at 10:36. The few "thalia" lines in `dumpsys alarm` are delivery history (allow-while-idle history, removal history and per-app statistics), not pending alarms. No schedule was changed and no call was made or touched.

## Limitations

- **Earlier revisions and full snapshots of the real app are unverified.** Only the latest revision of each day is shown on screen. The earlier revision (2026-10-05 revision 1) and every item's snapshot fields were not viewed after recovery. The plan and item counts (5 and 17) match what the approvals imply, but the content of earlier revisions is unverified.
- **No direct inspection of the encrypted schema 2 payload.** The password was never given to tooling, and `planning.db` and the export are encrypted, so the plan rows and the stored schema version were read only through the app's own screens, the fingerprint and file structure.
- **Real power loss** during replacement or approval was not tested.
- **Other document providers** were not tried; the system picker's Downloads location was used throughout.
- **Cloud backup and device transfer** were not run, so the backup exclusions remain unverified.
- **Checks not run after the fix:** the release unit suite, the other modules' tests and lint, and the recoveryCheck build. The 07:00 results are not in an evidence folder; they rest on files on disk and the session transcript.
- **M2's natural deep idle and consecutive-day coverage stay waived and unverified,** unchanged from the 2026-10-02 waiver.
- **The password steps** rest on the user's reports; no tooling saw a password.
- Slice 2 stores no call status, so nothing in it exercises the call link; that is slice 3.

## Evidence handling

- Only synthetic data was used. No personal planning data, voice transcript, audio, key or password was entered, read or saved.
- The user entered every password privately on the phone. None was requested, typed by tooling, captured or logged. Screenshots were not taken while a password field was visible, except one Stage 1 screenshot in which an empty password field had focus; both fields were empty after the export.
- One adb-driven tap landed on an Instagram heads-up notification during Stage 4; no Thalia data changed, and notifications were not tapped afterwards.
- Screenshots and dumps may show other apps' status-bar icons and names, so the evidence is not for publishing as it is.
- Gradle was not run for this closeout. No Gemini call was made, no schedule changed and no connected test run.

## Cleanup

- `com.thalia.app.recoverycheck` was uninstalled with `pm uninstall`, only that package.
- Removed from the phone's Downloads, by exact name and only after the phone hash equalled a Mac copy: `recoverycheck-B-20261004.thaliaexport` and `recoverycheck-seeded-S-20261004.thaliaexport`.
- Kept on the phone: `thalia-stage5-final-20261004.thaliaexport`, `thalia-daysfix-pre-20261004.thaliaexport` (`55ac4e84…adcf`, 10,747 bytes) and `debug-pre-slice2-schema1-2.thaliaexport`. All Mac exports and evidence are kept.

## Evidence

On the Mac, outside the repository, under `the author's private evidence folder (not published)`:

- `slice1-pixel-acceptance-20261003-162905/`: the original schema 1 export `slice1-accept-A.thaliaexport`.
- `slice2-stage0-20261003-211044/` to `slice2-stage4-20261003-220750/`: baselines, the pre-upgrade backup, the schema 1 imports, the in-place install and migration evidence, and the approvals (`90-stage4-complete.txt`, `99-paused.txt`).
- `daysfix-prep-20261004/` and `backups/`: the fixed APK, a pre-fix copy of `PlanDaySection.kt` and the fix patch.
- `daysfix-retest-20261004-0717/`: the retest (`90-results.txt`) and the pre-fix export.
- `stage5-20261004-0744/`: the final export, fingerprints A and B, and the restored plan reads.
- `stage5-recon-20261004-1030/`: the read-only reconstruction and the M2 snapshot.
- `stage5-seeded-20261004-1029/`: the seeded round trip, the final M2 snapshot, the cleanup, and `90-stage5-results.txt`.

Each Stage 5 folder has a `SHA256SUMS.txt` that verifies with no failures.
