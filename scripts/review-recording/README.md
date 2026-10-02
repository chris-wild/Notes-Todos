# App Review screen recording

Apple's Guideline 2.1 information request (2 October 2026) asks for a screen recording from a physical device running the latest iOS. These steps record the iPhone 11 Pro's screen from the Mac over the cable, so no Control Centre setup or file transfer is needed. The answers to Apple's written questions are in `app-review/2026-10-02-guideline-2-1-response.md`.

## Before the session

1. On the iPhone 11 Pro, sign in to the App Store with Chris's Apple ID, install TestFlight, and install HobPad from TestFlight. That is build 26, the build under review, and a credit pack bought in it is a free test purchase.
2. Connect the iPhone to the Mac by cable, unlock it, and tap Trust if asked.
3. Have a printed recipe ready to photograph, with good light on it.

## Recording

Claude runs the recorder in Terminal, because macOS treats a cabled iPhone's screen as a camera and only Terminal can show the camera permission prompt. Terminal already has camera access on Chris's Mac. When recording starts, the iPhone may ask whether a pair of headphones is being connected. Tap Other Device before the take begins, so the prompt does not appear in the recording.

```
swiftc -O scripts/review-recording/record-iphone.swift -o /tmp/record-iphone
/tmp/record-iphone list
/tmp/record-iphone app-review/hobpad-review.mov
```

Recording stops with Ctrl-C, or after a number of seconds given as a second argument.

## Shot list

1. Start on the Home Screen and open HobPad.
2. Recipes tab. Tap the camera and photograph the recipe. Wait for its name to appear.
3. Open the recipe. Tap Create ingredient list, set the quantities to x2, and create the list. The Todos tab opens on the new list. Tick one item.
4. Go back to the recipe and tap Create ingredient list again, to show that converting it again is free. Cancel.
5. Notes tab. Add a short note and open it.
6. Recipes tab, gear button. Show the credits and the Units setting.
7. Tap Buy credits and buy the 50 pack, confirming with a double-click of the side button. Show the balance rising by 50.
