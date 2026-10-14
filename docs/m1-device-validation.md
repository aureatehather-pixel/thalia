# M1: validating the incoming-call experience on the Pixel 10

M1 exists to answer one question, and it cannot be answered from a laptop:

> Will a locked Pixel 10 raise a full-screen incoming-call screen for Thalia, and does that screen read as a call?

Everything in the `:app` module is there to make that question askable.
Nothing is scheduled, no microphone is opened, and no AI provider is contacted.
The conversation is the deterministic fake from M0, paced so that a person can watch it.

The one sound the app makes is the ringtone.
It is a ringing alert, not conversation audio.

## What to expect from the build

These numbers are the build's, so that a result can be compared with them.

- An unanswered call rings for **45 seconds**, then is recorded as missed.
- An answered call is a scripted conversation of five lines, one every **6 seconds**, so about **30 seconds** in total.
- The ringtone **loops** and the phone **vibrates** in a repeating pattern, until you answer, decline, or the 45 seconds run out.
- Every way a call can end stops the ringtone and vibration, removes the notification, and stops the service.
- **Ring in 10 seconds** arms one test call that starts ten seconds later, so the call can begin while the phone is already locked. It is the only way to test that case, and it is used in steps 2 to 7.

## Build the APK

From the repository root:

```bash
export JAVA_HOME="$HOME/.local/toolchains/jdk21"
export ANDROID_HOME="$HOME/.local/toolchains/android-sdk"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew :app:assembleDebug
```

The APK lands at:

```
app/build/outputs/apk/debug/app-debug.apk
```

It is signed with the automatically generated debug keystore, which is all a sideloaded personal build needs.
No release signing config exists yet, and none should until there is a reason for one.

`local.properties` tells Gradle where the SDK is.
It is machine-specific and gitignored, and is recreated with:

```bash
echo "sdk.dir=$HOME/.local/toolchains/android-sdk" > local.properties
```

## Install on the Pixel 10

One-time setup on the phone:

1. **Settings > About phone**, tap **Build number** seven times to enable Developer options.
2. **Settings > System > Developer options**, turn on **USB debugging**.
3. Connect the phone to the computer by USB.

Then, from WSL:

```bash
export ANDROID_HOME="$HOME/.local/toolchains/android-sdk"
"$ANDROID_HOME/platform-tools/adb" devices
```

The phone shows an **Allow USB debugging?** prompt the first time.
Accept it, tick *Always allow*, then re-run `adb devices`.
The device must be listed as `device`, not `unauthorized`.

Install:

```bash
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
```

The app appears as **Thalia**.
Its application id is `com.thalia.app.debug`, so a future release build can sit alongside it without conflict.

If a build of Thalia was installed before this one, uninstall it first.
Android keeps a notification channel's settings from when it was first created, and this build's channel is deliberately silent.

### If `adb devices` shows nothing under WSL

WSL2 does not see USB devices by default.
Two ways round it, in order of preference:

1. **Wireless debugging**, which avoids USB passthrough entirely. On the phone: **Developer options > Wireless debugging > Pair device with pairing code**. Then:
   ```bash
   "$ANDROID_HOME/platform-tools/adb" pair <phone-ip>:<pairing-port>   # enter the 6-digit code
   "$ANDROID_HOME/platform-tools/adb" connect <phone-ip>:<debug-port>
   ```
   The pairing port and the debugging port are different numbers; both are shown on the phone's screen.
2. **Copy the APK to Windows and install from there**, using the Windows copy of `adb`, or by moving the file to the phone and opening it with a file manager (Android will ask permission to install unknown apps).

## How to start a call while the phone is locked

The **Ring morning** and **Ring afternoon** buttons ring the moment you tap them, with the screen on.
Locking the phone afterwards does not test the important case, because the full-screen intent has already fired while the phone was unlocked.

To start a call while the phone is genuinely locked and dark, use **Ring in 10 seconds**.
It is available only in debug builds on the Thalia screen, under **Locked-screen test**.
Release builds hide the action and reject the arm command.

Before each repeat, decline or end any previous call and return to Thalia.
An armed test is refused while another call is ringing or active.

The procedure, exactly:

1. Open Thalia. Check the top card says **Full-screen call expected**.
2. Tap **Ring in 10 seconds**. The card turns to **Test call armed: ringing in N s**, with a **Cancel test call** button. A quiet **Test call armed** notification appears in the shade with a countdown.
3. Ensure the phone is configured to lock immediately with the power button (disable Extend Unlock for this test).
   Press the power button **once**. The screen goes off and the phone locks.
4. Put the phone down and do not touch it.
5. About ten seconds after you tapped, the call starts on its own. Everything after that is the real result: does the screen turn on, does a full-screen call screen appear over the lock screen, do the ringtone and vibration start.

Notes:

- Do not unlock the phone, and do not press the power button again, during the ten seconds. Waking the screen and looking at it changes what is being tested.
- To abandon a test, tap **Cancel test call** on the screen, or **Cancel** on the notification. Nothing rings.
- Only one test can be armed at a time. Tapping again while armed does nothing, and the Ring buttons are disabled until the test rings or is cancelled.
- If the phone is plugged into the computer, unplug it or use wireless debugging for the real result. A cable can change how the phone behaves when locked. The wake lock below means the timer does not depend on it, but the screen and power behaviour might.

### Why this works with the screen off

A plain delay in the app would not be reliable here.
A phone with a dark screen and nothing holding it awake goes to sleep, and a sleeping phone's timers stop counting.
Android may also freeze an app that has nothing visible.
So while a test is armed, the app does two things, and only for those ten seconds:

- it runs its existing foreground service, with a visible **Test call armed** notification, so the app is not frozen; and
- it holds a partial wake lock, with a timeout, so the CPU stays awake for the timer to fire.

The wake lock is released the moment the call starts ringing, or the test is cancelled.
It has a timeout as a backstop, so it can never be held for long.

This is not scheduling, and it says nothing about M2.
It does not use AlarmManager, WorkManager or any stored state.
Backgrounding the activity does not cancel it; process death, force-stop, or service destruction does.
The armed notification uses a separate ID and is removed after the incoming call notification is posted, so the call is a new notification rather than an update to the quiet countdown.
It tests one thing only: what Android does when a call *starts* while the phone is locked.
Whether a real scheduled alarm wakes the phone on time is an M2 question.

### To confirm the wake lock was held, then released

Optional, and only with the phone connected by wireless debugging.
While the test is armed, the phone's live wake-lock list should show a wake lock named `thalia:test-ring-armed`:

```bash
"$ANDROID_HOME/platform-tools/adb" shell dumpsys power | sed -n '/^Wake Locks:/,/^$/p' | grep -i thalia
```

After the call starts ringing, or after **Cancel**, it should print nothing.

Do not use a plain `dumpsys power | grep -i thalia`.
On Android 17 that also matches the phone's history of wake locks that were held and released earlier, so it prints many lines even when nothing is held.

## The test procedure

Work through these in order.
Each one answers something different, and the later ones depend on the earlier ones having passed.

Before starting, open Thalia once.
The top card states what Android will let it do.
If it does not say **Full-screen call expected**, fix that first using the buttons on that screen; the rest of the procedure tests nothing useful until it does.

Turn the phone's ringer on, not silent, and Do Not Disturb off.
Step 8 tests those two on purpose, and everything before it assumes they are not in the way.

### 0. Permissions and diagnostics

1. Open Thalia. Grant notification permission when asked, or with **Grant notification permission**.
2. Confirm every diagnostic line reads as expected:
   - Notification permission: **yes**
   - Notifications enabled: **yes**
   - Channel importance is High: **yes**
   - Full-screen intent allowed: **yes**
3. The card at the top should read **Full-screen call expected**.

**Expected:** all four yes, card green.
**If full-screen intent says no:** tap **Open full-screen intent settings** and enable Thalia.
Note that this happened, because it tells us the permission is not being granted by default on this device, which matters for the real product.

### 1. Unlocked, screen on

1. With Thalia open, tap **Ring morning**.
2. Expect: the phone rings with your ringtone, vibrates in a repeating pattern, and a heads-up banner appears at the top of the screen with **Decline** and **Answer**.
   It is a banner, not a full-screen call screen, because Android shows an incoming call as a banner while the phone is in use.
   The full-screen call screen is what step 2 tests.
3. Check the banner names the caller **Thalia** and the **morning check-in**.
4. Leave it ringing for about ten seconds.
5. Check the ringtone **repeats** and the vibration **repeats**, rather than sounding once.
6. Tap **Decline**.

**Expected:** ringing stops immediately, vibration stops, the banner closes, the notification disappears.

### 2. Locked screen, screen off (the real case)

1. Follow **How to start a call while the phone is locked**, above: tap **Ring in 10 seconds**, press the power button once, and put the phone down.
2. Expect: about ten seconds later the screen turns itself on, the phone rings and vibrates, and the Thalia call screen appears **over the lock screen** without unlocking the phone.

**This is the result M1 exists to get.**
Note exactly what happens, including:

- Did the screen turn on by itself?
- Was it a full screen, or a notification banner at the top?
- Did it appear immediately, or after a delay?
- Was the rest of the phone still locked behind it?
- Did the ringtone and vibration start together with the screen?
- Did it start on time, about ten seconds after you tapped?
- Does the call screen say **Incoming internet call** and name the caller **Thalia**?

If the call starts but only a notification appears, with no full-screen takeover, that is a real answer to the M1 question.
Record it and go on to step 7.

### 3. Declined from the lock screen

1. Repeat step 2, starting the call with **Ring in 10 seconds**.
2. Tap **Decline** on the lock-screen call surface.

**Expected:** the call ends, the phone returns to the lock screen, still locked, and no notification is left behind.
The ringtone and vibration stop at once.

### 4. Answered

1. Repeat step 2, starting the call with **Ring in 10 seconds**, and tap **Answer**.
2. Expect: on a secured device, Android asks you to unlock. After unlocking, the active-call screen appears.
3. Watch the transcript fill in **one line every 6 seconds**.
4. Check the screen says plainly that the conversation is simulated.
5. Pull down the notification shade. It should show an ongoing call with a **running timer**.
6. Check the timer keeps counting up and does **not** jump back to zero each time a line arrives.

**Expected:** ringing and vibration stop the moment you answer.
After about 30 seconds the call ends by itself with **Check-in complete**.

### 5. Ended by hand

1. Ring and answer again.
2. Wait until at least two lines are on screen, then tap **End Call**.

**Expected:** the call ends **immediately**, not when the next line was due.
The ended screen says **You ended the call**, and the transcript keeps the lines that had already been said.
The notification disappears and the service stops.

Also try **End Call** from the ongoing notification in the shade, on a second run.
It should end the call the same way.

### 6. Nobody answers

1. Tap **Ring morning** and do not touch the phone.
2. Watch it for the full 45 seconds.

**Expected:** the ringtone and vibration stay on for the whole 45 seconds, then stop together.
The call screen changes to **Missed call**, and the notification disappears.
Tap **Done** to close the screen.

Do this once with the phone unlocked, using **Ring morning**, and once locked, using **Ring in 10 seconds** and leaving the phone alone.

### 7. The honest fallback

This deliberately breaks the good case, to prove the degraded one is truthful.

1. Go to **Settings > Apps > Special app access > Manage full screen intents** (or use the button in Thalia) and turn Thalia **off**.
2. Return to Thalia. The card should now read **Degraded**, and say a heads-up notification will appear instead of a call screen.
3. Tap **Ring in 10 seconds**, lock the phone, and put it down.

**Expected:** the phone rings and vibrates, and the screen stays dark.
There is no full-screen takeover, and the screen does not turn itself on.
Waking the phone shows a notification with **Answer** and **Decline** on the lock screen, and no pretence that a call screen appeared.
Answer still works from that notification.

4. Turn the permission back on when finished.

### 8. The ringer switch and Do Not Disturb

The ringtone plays with the ringtone audio usage, so the phone's own settings should govern it.
This step checks that they do.

1. Put the phone on **vibrate only**, then ring a check-in.

**Expected:** no ringtone, but the vibration and the call screen still appear.

2. Turn on **Do Not Disturb**, then ring a check-in.

**Record what happens.**
Whether a Do Not Disturb setting lets Thalia through or holds it back is a device decision, and it matters for M2, because a 10:00 check-in that stays silent is a missed check-in.

3. Return both to normal.

### 9. Foreground service behaviour

1. Ring a check-in, then answer.
2. While the call is active, check the notification shade.
3. End the call.
4. Ring a check-in and decline it.
5. Ring a check-in and let it time out.
6. Tap **Ring in 10 seconds**, then **Cancel test call** before it rings.
7. Tap **Ring in 10 seconds** and let it ring, then decline it.

**Expected:** the ongoing-call notification is present during the call and gone immediately afterwards.
After every one of those endings, nothing from Thalia remains in the shade, and no persistent service notification lingers between check-ins.

To confirm the service itself has stopped, not just its notification, run this from the computer after each ending.
It should print nothing:

```bash
"$ANDROID_HOME/platform-tools/adb" shell dumpsys activity services com.thalia.app.debug | grep -i CheckInCallService
```

## What to record

For each step: what happened, and whether it matched.
For step 2 especially, a photo or a screen recording is worth more than a description.

If anything differs from the expectation, that is a result rather than a failure.
The purpose of M1 is to find out what Android actually does, and a negative answer changes the product honestly rather than being worked around.

## Known limits of this build

These are deliberate, and are not bugs to report:

- The call can only be triggered by the button. Nothing is scheduled; that is M2.
- The conversation is a fixed script. There is no microphone, no conversation audio and no AI; that is M3.
- Nothing is saved. Closing the app forgets every check-in; persistence is ~~M4~~ **M2 slice 2** (corrected 2026-09-29: the occurrence record arrived there, see [ADR 0006](adr/0006-m2-slice-2-durable-scheduling-and-recovery.md)).
- The 45 second ring timeout, the 6 second line delay and the 10 second test delay are fixed for this build. They are not settings.
- **Ring in 10 seconds** always rings the morning check-in, and it is lost if the process is killed or the service is destroyed during the ten seconds. It is a test aid, not a schedule.
- The screen design is deliberately plain. It is a probe, not a design.
- The ringtone is the phone's default ringtone. It cannot be changed from the app.
