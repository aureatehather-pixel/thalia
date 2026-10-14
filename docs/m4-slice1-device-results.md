# M4 slice 1 device results: encrypted planning storage, export and import, and M2 preservation

Date: 2026-10-03, about 16:29 to 17:10 phone time (Asia/Kuala_Lumpur, UTC+8)
Milestone: the minimal part of M4, personal information foundation, slices 1A and 1B
Branch: `spike/m3a-gemini-live`, checkpoint `6675e0d`
Design: [ADR 0009](adr/0009-minimal-planning-loop.md)

## The answer

ADR 0009's slice 1 device acceptance asked whether the encrypted planning store, the encrypted export and import, and recovery without the original Keystore work on the Pixel, and whether they leave M2 alone.

**Yes, with the limitations below.** Slices 1A and 1B are accepted on the Pixel 10.

- On real SQLCipher and the Android Keystore, nine storage checks passed in an isolated app, `com.thalia.app.recoverycheck`.
- A synthetic export was restored after that app's data was cleared, which removed its Keystore key. The row fingerprint matched exactly.
- The real `com.thalia.app.debug` was updated in place. Every M2 row and setting was preserved, its `planning.db` is encrypted, and a real exact alarm then rang and completed a scripted call 35 ms after its due time.

This accepts slices 1A and 1B only. **M4 and M5 as a whole are not complete.** Slice 2, the daily proposal and approval, is next.
This is one device, one Android build, one tester and one run of each test.

## What was tested

| Item | Value |
|---|---|
| Device | Pixel 10 (`frankel`), Android 17, API level 37, build `CP3A.260905.009`, security patch 2026-09-05 |
| Mac | Apple M3, macOS 14.5, `adb` 37.0.1 over wireless debugging |
| Repository | `6675e0d7369ccba41413e0a306952df07457474f`, clean tree, local and `origin` identical, throughout |
| Isolated app | `com.thalia.app.recoverycheck`, built on the Mac and not rebuilt: SHA-256 `9cea719312948cfa7b064b937007b727c95dd2ae8c0b9eec4e3046947f193c22`, 39,537,390 bytes, version `0.1-m1-recoverycheck`, no `INTERNET` permission |
| App under update | `com.thalia.app.debug`, previously the M3A build: SHA-256 `6ffc01e5d911bf01a56ca989d744d6d905d8f30d3bb7be43fd21273088d20f4c`, 33,431,959 bytes |
| New debug build | `:app:assembleDebug` on the Mac: SHA-256 `6381ae1efb7ea8a0d122fc09f2729c209f0a657c2957f92f39dda95ebe1fc164`, 41,504,034 bytes, built in 25 s, then Gradle daemons stopped |
| Signing | All three APKs and the installed apps: `fb3348fe12dac4e025cc4a768e63ed19db4a65c261e56ca2126da020706d31dd` |

Four kinds of evidence are kept apart below.

- **System** is read from the phone or the Mac: file sizes, modification times and checksums, package state, the alarm service, the system log and the database copies.
- **App-reported** is what the isolated app's own check harness printed, including its durations. It is the app's own measurement of itself.
- **Tester** is what the user saw and reported on the screen. Most screens were also captured.
- **Approximate** is a figure worked out from log lines and file times, with no instrumentation in the app. It is an observation, not a measurement.

## Results

| # | Criterion | Result | Basis |
|---|---|---|---|
| 1 | Checkpoint | **Pass** | System: branch, HEAD, upstream and remote HEAD agree, tree clean before and after the build |
| 2 | Isolated APK identity | **Pass** | System: hash, package ID, signer and the hash of the installed copy all match. Not rebuilt |
| 3 | `com.thalia.app.debug` untouched during the isolated stage | **Pass** | System: its `checkins.db`, WAL and SHM were byte-identical to the first baseline before the isolated stage, after the storage checks, and after the clear. Its install times and APK hash did not change |
| 4 | M2 not due during the tests | **Pass** | System: no pending alarm for the app's uid, no service, and all 32 occurrences terminal |
| 5 | Storage checks on real SQLCipher and Keystore | **Pass, 9 of 9** | App-reported. See below |
| 6 | Seed, export and read-back | **Pass** | Tester: exported to a new name in Downloads through the document picker, "Exported and read back". System: a 1,758-byte file starting with the export magic, no clear text of the synthetic strings, SHA-256 `69003b71b1a65f69d4dab6b5b0617eca05ce363b9c48bbc4fa862b05c18bc235` |
| 7 | Recovery after clearing only the isolated app | **Pass** | System: after the clear, the Keystore key was absent and no planning file existed, read through "Key and file status" before anything opened planning data. Restored counts and fingerprint matched, below |
| 8 | Wrong-password import | **Pass** | Tester and screenshot: "Not imported: wrong password or damaged file. Nothing was changed." System: counts stayed 0 and the export file's checksum was unchanged |
| 9 | Restore | **Pass** | Tester and screenshot: the preview showed the export's time and counts beside an empty phone, then Restore. System: the fingerprint matched |
| 10 | Replacement | **Pass** | Tester and screenshot: ordinary import refused ("this phone already has planning data, and import never merges") and offered only "Replace current planning data". Tester: typed REPLACE. System: the fingerprint returned to the export's, and the previous database and its sealed key sat in a dated set-aside folder |
| 11 | Export into a non-empty document | **Pass** | Tester and screenshot: refused, nothing written. System: the file kept its size, modification time and checksum, and no other file appeared |
| 12 | In-place update of `com.thalia.app.debug` | **Pass** | System: `install -r` returned Success, `firstInstallTime` is unchanged (2026-09-19 17:08:50), the installed hash is the new build's |
| 13 | M2 rows and settings preserved | **Pass** | System: after the update, the main database and WAL were byte-identical. Only the SHM, SQLite's shared-memory index, changed, when the database was opened. All 32 occurrence rows, both schedules, the quiet period and the Room schema hash are identical, and `integrity_check` is `ok`. This ran on `androidx.sqlite` 2.7.0 |
| 14 | `planning.db` is not plain SQLite | **Pass** | System: the real file's first page has no SQLite header and no synthetic text, and plain SQLite on a copy says "file is not a database" |
| 15 | Planning screen: save and reopen | **Pass** | Tester and screenshots: a synthetic direction and the 12:00 to 22:00 window saved and were shown again after leaving the screen and reopening it. No error was shown |
| 16 | A real scheduled call on SQLite 2.7.0 | **Pass** | System: below. Tester: answered, and the scripted call ran |
| 17 | Existing rows unchanged after the call | **Pass** | System: the only difference from the baseline is the one new occurrence |
| 18 | APK size change | **Recorded** | +8,072,075 bytes, below |
| 19 | Database open time | **Observed approximately; not precisely measured** | Approximate, below |

## The isolated storage checks

One press of "Run storage checks", on scratch files and a scratch Keystore alias, with the same production storage code.
All nine passed. The durations are App-reported.

| Check | Time |
|---|---|
| PBKDF2 against independent reference vectors, 600,000 iterations | 1,308 ms in all, 1,299 ms for the 600,000 iterations |
| The database is encrypted at rest and is not plain SQLite: no SQLite header, no clear text, plain open refused | 884 ms, of which the reopen was 378 ms |
| The sealed key is not the passphrase and the Keystore opens it: 32 bytes sealed into 62 | 13 ms |
| A wrongly keyed database is reported `DATABASE_UNREADABLE` and its files are byte-identical; the right key still opens it | 1,189 ms |
| A corrupt database is reported `DATABASE_UNREADABLE` and its files are byte-identical | 748 ms |
| "Set aside and start fresh" keeps the unreadable files byte-identical and opens an empty database | 878 ms |
| Replacement stopped at each of 9 steps recovers to the old or the new data | 14,179 ms |
| Set-aside stopped at each of 6 steps recovers | 7,427 ms |
| Export and import round trip in memory, 1,141 bytes, and a wrong password refused | 6,833 ms |

The wrong-key and corrupt-file checks answer the ADR's open question: on a real device, SQLCipher's keeping handler keeps the file.
The round trip is in memory, with no file or picker, and is bundled in the same button; the user approved running it at this stage.

## Recovery without the original Keystore

The seeded synthetic data was one direction, one settings row, one goal, two projects and two actions.
Its fingerprint, a SHA-256 prefix over a canonical payload with fixed metadata, was `0f7c9446eff3a8dcb8326bf2eff5927c`.

| State | Counts | Fingerprint |
|---|---|---|
| Seeded | 1, 1, 1, 2, 2 | `0f7c9446…927c` |
| After clearing only the isolated app's data, and opening planning | 0, 0, 0, 0, 0 | `cec2fc2d…8e60`, the empty fingerprint |
| After a wrong-password import | 0, 0, 0, 0, 0 | `cec2fc2d…8e60` |
| After the correct-password restore | 1, 1, 1, 2, 2 | `0f7c9446…927c` |
| After editing the direction | 1, 1, 1, 2, 2 | `9c199d77…7329` |
| After Replace with the same export | 1, 1, 1, 2, 2 | `0f7c9446…927c` |

Before the clear, the sealed key file existed (62 bytes) and seeding had worked, so the key existed; the status screen was not read at that point.
After the clear, "Key and file status" said the key was absent and no planning file existed. That screen does not open planning data, so it cannot create what it reports on.
Opening planning afterwards created a new key and an empty database, as the code intends.
The restore therefore worked with a different Keystore key from the one that sealed the original data.

After Replace, the set-aside folder `no_backup/recoverycheck-planning-set-aside/20261003-165111-replaced/`, in the isolated app's own storage, held the edited database (49,152 bytes) and its own sealed key (62 bytes).
That the set-aside data opens on the phone was not checked on the device. The JVM tests cover it.

## The in-place update and M2

| Item | Value |
|---|---|
| Update | `adb install -r`, at 16:56:56 |
| `firstInstallTime` | 2026-09-19 17:08:50, unchanged |
| M2 occurrences before and after the update | 32 and 32, identical field by field; hash of the rows `af410634…8fcd` |
| Schedules | OPENING 10:00 and CLOSING 15:00, both enabled, zone Asia/Kuala_Lumpur, unchanged |
| Quiet period | 22:41 to 22:56, disabled, unchanged |
| Pending alarm after the update | none, which is correct: no occurrence was waiting |

**The scheduled call.** The user tapped "Schedule in 2 minutes", with Thalia open and the phone unlocked.

| Item | Value (phone clock) |
|---|---|
| Armed by the tap | 17:01:20 |
| Occurrence due | 17:03:20.732 |
| Fired | 17:03:20.767, **35 ms after the due time** |
| Foreground service started | 17:03:20.814, allowed, app `TOP` |
| Call screen started | 17:03:23.288 |
| Resolved | 17:03:53.389, **COMPLETED**, 32.6 s after firing |

Status counts after the call: CANCELLED 16, COMPLETED 1, DECLINED 7, MISSED 7, SUPPRESSED 2. Before it there were no COMPLETED rows, so the new row is the only change.
The call screen starting is system evidence. That the user answered it, and that the scripted call ran, is the tester's report.
The adb link dropped just after the tap, so the armed alarm was confirmed afterwards from the database and the log, not at the time.

## Planning screen on the real app

The screen opened with an empty database, as expected for a first open.
The user-facing result is the Tester and screenshot evidence in the results table. The rest is timing.

## Size and timing

| Item | Value | Basis |
|---|---|---|
| APK size change | 33,431,959 to 41,504,034 bytes, **+8,072,075 bytes, +24.1%, about +7.7 MiB** | System; the ADR's estimate was +7.8 MB |
| First open of planning, which creates the Keystore-sealed key and the database | about **0.44 s** from the start of the screen to the last database write | Approximate |
| Reopen | first frame **+41 ms**, which is the system's time to the first drawn frame; the saved data was on screen at the first screenshot, 3 s later. **When the reopen finished was not precisely measured** | Approximate |
| Isolated app, reopen of an encrypted database | 378 ms | App-reported |

**How these were observed, and why they are not measurements.** The app has no timing instrumentation, and no application code was changed for this test.
For the first open, the start of the screen is Android's `START` log line at 16:58:12.025. The sealed key file was written at .084, the native SQLCipher library was loaded at .097, and the database's last write was at .464, taken from file modification times.
The 0.44 s is the span from the first to the last of those, and includes work Thalia does not control, such as creating the Keystore key.
The "Displayed" figures are the system's time to the first drawn frame, which comes before the database finishes opening: the library loaded after the first frame.
So +41 ms is not the database open time. The data was on screen within 3 s, and nothing finer was measured.
These observations do not support a stated bound on the open time, and are not for comparing builds.

## Anomalies and deviations

- **A wrong address was sent for pairing,** and pairing worked once the address found by discovery was used.
- **Several blind taps landed on the wrong place** when the layout shifted, hitting text or empty space, and one hit the keyboard's own theme page (`com.google.android.inputmethod.latin`). Nothing was selected there, and the back key returned to Thalia. The rest of the run checked the foreground screen first.
- **The wireless debugging link dropped** just after the scheduled alarm was armed, and came back on a new port. Nothing was lost.
- **The isolated stage ran an in-memory export and import** inside "Run storage checks" before the real export. The user approved that.
- **One evidence file was produced wrongly** by a shell quoting error, was deleted and made again. The final copies were verified by checksum.
- **Synthetic planning data was left in the real app:** a direction, "Synthetic acceptance direction test", and the 12:00 to 22:00 window. The user chose to leave them for editing later in the screen. Editing the direction through the normal screen is housekeeping, not an acceptance requirement, and the window may remain.
- **The M3A state was not touched** and was not re-read: the `m3a` folder's modification time is unchanged.

## Limitations

- **Actual power loss.** The device checks stop the replacement and set-aside in process at each step. A real power cut during replacement, and the durability of the directory syncs, were not tested.
- **Other document providers.** Export and import were tried once, through the system picker's Downloads location. A cloud provider, an SD card, another app's provider, and one that refuses the truncating mode were not tried.
- **Backup and device transfer.** The `dataExtractionRules` exclusions were checked only against where the files are put. A real cloud backup or a device-to-device transfer was not run.
- **The alarm path covers one case.** The call came with Thalia open and the phone unlocked. A locked phone, a swiped-away app, a reboot on this build, and natural deep idle were not tested. **The user's waiver of natural deep idle and consecutive days for M2 is unchanged.**
- **Keystore-loss recovery was shown in the isolated app,** with the same production code and different file names and alias. It was not run against the real app, by design.
- **The set-aside data was not opened on the device,** only checked to be present with its sealed key.
- **Only small data was used:** seven rows, and an archive of about 1.7 KB. An archive near the 8 MiB limit or with very many rows was not run on the phone.
- **No password was seen or logged,** so password strength and entry rules rest on the JVM tests and the on-screen limit.
- **No JVM suite was rerun in this session.** The Mac has still not run the full `:app` test suites or `ktlintCheck`; the last full runs were on Windows on 2026-10-02.
- **Evidence for what the user saw** rests on the user's reports, backed by screenshots.

## Evidence handling

- Only synthetic data was used. No personal planning data, voice transcript, audio, key or password was entered, read or saved.
- The user entered every password privately on the phone. None was requested, typed by the tooling, or logged.
- The pairing code was used once and is recorded as redacted.
- No logs were cleared.
- The screenshots are of Thalia and a few system screens, and show the status bar and its notification icons. The alarm-service dump and some log lines name other apps on the phone, so the evidence is not for publishing as it is.
- The export file is encrypted and synthetic. A read-only copy is kept with the evidence. The copy on the phone was deleted after the Mac copy's checksum was verified.

## Cleanup

- `com.thalia.app.recoverycheck` was uninstalled with `adb uninstall`, only that package. Its data and Keystore keys went with it.
- `slice1-accept-A.thaliaexport` was removed from the phone's Downloads, only that file, after its checksum matched the Mac copy. No other Thalia file was in Downloads.
- `com.thalia.app.debug` was not touched by the cleanup. Its `checkins.db` set is identical to the copy taken after the call, its `planning.db` is as the user left it, and no alarm is pending.

## Evidence

The evidence is kept off the repository, on the Mac, under `the author's private evidence folder (not published)`.
It holds the checkpoint and APK checks, device identity, the three database baselines and their checksums, the inspection output, the build log, the install records, the storage-check log, every screenshot, the export copy, the alarm and log extracts, the cleanup records, and checksum manifests.
