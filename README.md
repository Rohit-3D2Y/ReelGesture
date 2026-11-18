# Reel Gesture (in-house)

Scroll Instagram Reels hands-free. Show **☝ one finger** (index up) to the front camera to go to the next reel. Lower your hand and show it again for the next one. You can optionally turn on **✌ two fingers** to go back to the previous reel.

## How it works
- `GestureCameraService` is a foreground service with the `camera` type. It reads the front camera at 640×480 and runs the MediaPipe **Hand Landmarker** on the CPU to recognise a held ☝ or ✌ pose.
- `PoseTrigger` fires once when a pose is held for 2 frames. It doesn't fire again until the pose goes away, so holding ☝ scrolls exactly one reel.
- `ReelScrollService` is an Accessibility Service. It asks Instagram's reels pager to scroll one page, the same way TalkBack does. If that isn't available, it performs a real on-screen swipe instead.

## Stability safeguards
- All camera, ML, and gesture calls are guarded. A failure shows up as a status message in the app instead of a crash.
- If another app is using the camera, the service waits and reconnects automatically.
- The camera is fully closed while Instagram is not on screen or the phone is locked.
- With no hand in view, only a cheap motion check runs (4 times a second). The hand model wakes up only on movement, at about 15 fps, on a background-priority thread.
- Only one frame is processed at a time, and stalled frames are dropped. Memory stays flat.
- The service never restarts itself in a loop. Stop it from the notification or from the app.
- `gradle.properties` limits the Gradle heap to 2 GB so builds don't starve other processes on the PC.

## Build
1. Install **Android Studio**. It includes the JDK and Android SDK.
2. Choose *File → Open* and select this `ReelGesture` folder. Let Gradle sync.
3. Connect the phone with USB debugging on, then press **Run**.
   You can also use *Build → Build APK(s)* and sideload `app/build/outputs/apk/debug/app-debug.apk`.

## First-time setup on the phone
1. Open **Reel Gesture** and tap **Grant camera permission**.
2. Tap **Enable accessibility service** and turn on *Reel Gesture scroller*.
   - On Android 13 and later with a sideloaded APK, the toggle may be greyed out as a "Restricted setting". Tap **Open App info**, open the ⋮ menu, choose **Allow restricted settings**, then try again.
3. Tap **Start gesture control**, then **Open Instagram** and go to Reels.
4. Prop the phone up and move your open palm upward about 20–30 cm in front of it. Adjust the **Sensitivity** slider if needed.
