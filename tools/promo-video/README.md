# Promo video

A ~23s promo video built with [Remotion](https://remotion.dev): a dark cold-open (the pain — too
many tabs), a logo reveal, five feature beats (organize, save, find, ask, yours), a CTA to
`stramus.space`. Three of the five feature beats are real screen-recorded interaction footage —
a live search, a tab held over a drop target, a light→dark theme switch — captured with
[`../screenshots/capture-promo-clips.mjs`](../screenshots/capture-promo-clips.mjs) against the
actual built extension, not staged mockups; the other two are the Chrome Web Store screenshots in
[`../../store/screenshots`](../../store/screenshots). Colors and font stack are pulled from the
app's own light palette (`webapp/src/jsMain/resources/index.html`), so the video reads as the same
product as the footage it frames.

## Setup

```sh
cd tools/promo-video
npm install
```

## Use

```sh
npm start                                 # Remotion Studio, for scrubbing/tweaking scenes live
npm run render                            # renders out/stramus-promo.mp4 (1920x1080, h264)
```

## Structure

- `src/constants.ts` — timing (fps, per-scene duration, transition kind/length) and the five
  feature captions/media, in one place so re-pacing the video doesn't mean hunting through JSX.
  Video-backed scenes are sized to the clip they hold rather than a round number, so nothing sits
  frozen on a last frame waiting for the scene to end.
- `src/Promo.tsx` — the root composition: cold open → intro → five `FeatureScene`s → outro,
  stitched with `@remotion/transitions` (mixed fade/slide, not one repeated crossfade), plus the
  `<Audio>` rhythm bed.
- `src/scenes/` — `ColdOpen` (the "47 tabs open" hook), `Intro`, `FeatureScene` (reused per
  feature, picks `BrowserFrame` or `VideoFrame` from `media.type`), `Outro` (CTA pill).
- `src/WindowChrome.tsx` — the chrome-less "window" footage sits in (shared by both media types).
  `src/BrowserFrame.tsx` adds a slow Ken Burns zoom/pan for the static shots; `src/VideoFrame.tsx`
  plays a clip with a much subtler zoom, since real UI motion doesn't need help.
- `src/Logo.tsx`, `src/Background.tsx`, `src/theme.ts` — the star-logo reveal animation, the soft
  gradient backdrop, and the color/font constants shared by every scene.
- `public/` — copies, not references, of `logo.png`, `store/screenshots/*.png`, the recorded clips,
  and the generated audio bed, so this project can be rendered (e.g. in CI) without the rest of the
  repo's build having run first.
  - `public/clips/*.mp4` — re-generate with `../screenshots/capture-promo-clips.mjs` (needs the
    extension built and Playwright's Chromium installed — see `../screenshots/README.md`), then
    trim the useful seconds with ffmpeg; the script's header comment has the exact commands used.
  - `public/audio/bed.wav` — regenerate with `scripts/generate-audio-bed.sh`. A plain procedural
    pulse (ffmpeg lavfi sine/silence sources, no samples or synths from anywhere), so there's
    nothing to license. The fade in/out lives in `Promo.tsx`'s `<Audio volume={...}>`, not in the
    wav, so retiming the cut never means re-rendering audio.

## Changing the pitch

Editing which features are shown, their order, or the caption copy is all in the `FEATURES` array
in `src/constants.ts` — each entry is `{index, eyebrow, title, subtitle, media, pan, durationInFrames}`.
`media` is `{type: 'image', src: 'screenshots/x.png'}` or `{type: 'video', src: 'clips/x.mp4'}`,
resolved against `public/`.
