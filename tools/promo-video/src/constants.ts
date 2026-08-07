export const FPS = 30;
export const WIDTH = 1920;
export const HEIGHT = 1080;

export type FeatureMedia = {type: 'image' | 'video'; src: string};

export type Feature = {
  index: string;
  eyebrow: string;
  title: string;
  subtitle: string;
  media: FeatureMedia;
  pan: 'left' | 'right';
  durationInFrames: number;
};

export const COLD_OPEN_DURATION = 75;
export const INTRO_DURATION = 70;
export const OUTRO_DURATION = 120;

// Video-backed scenes are sized to the clip they hold (public/clips/*.mp4, 25fps source resampled
// onto this composition's 30fps timeline) rather than a round number, so nothing sits on a frozen
// last frame waiting for the scene to end. Image-backed scenes stay short on purpose — a Ken Burns
// pan over a still doesn't earn four seconds the way real interaction footage does.
export const FEATURES: Feature[] = [
  {
    index: '01',
    eyebrow: 'Organize',
    title: 'Every tab has a place.',
    subtitle: 'Sections, collections, cards — not one giant list.',
    media: {type: 'image', src: 'screenshots/01-collection.png'},
    pan: 'left',
    durationInFrames: 100,
  },
  {
    index: '02',
    eyebrow: 'Save',
    title: 'Save it without losing your place.',
    subtitle: 'Drag a tab in — or grab the whole window at once.',
    media: {type: 'video', src: 'clips/drag-clip.mp4'},
    pan: 'left',
    durationInFrames: 104,
  },
  {
    index: '03',
    eyebrow: 'Find',
    title: 'Find anything in one keystroke.',
    subtitle: 'Cards, open tabs, and history — one search box.',
    media: {type: 'video', src: 'clips/search-clip.mp4'},
    pan: 'right',
    durationInFrames: 105,
  },
  {
    index: '04',
    eyebrow: 'Ask',
    title: 'Get the answer, not another tab.',
    subtitle: 'An AI assistant that already knows what you saved.',
    media: {type: 'image', src: 'screenshots/04-assistant.png'},
    pan: 'right',
    durationInFrames: 100,
  },
  {
    index: '05',
    eyebrow: 'Yours',
    title: 'Make it yours.',
    subtitle: 'Light or dark. Everything stays on your device.',
    media: {type: 'video', src: 'clips/settings-clip.mp4'},
    pan: 'left',
    durationInFrames: 134,
  },
];

// One entry per cut between the 8 scenes (cold open, intro, 5 features, outro) — mixing fade and
// slide keeps a video built from held four-second shots from settling into one metronomic rhythm.
export const TRANSITIONS: Array<{kind: 'fade' | 'slide-left' | 'slide-right'; duration: number}> = [
  {kind: 'fade', duration: 10}, // cold open -> intro: a hard-ish cut, not a lingering dissolve
  {kind: 'fade', duration: 18}, // intro -> feature 1
  {kind: 'slide-left', duration: 16}, // feature 1 -> 2
  {kind: 'slide-right', duration: 16}, // feature 2 -> 3
  {kind: 'fade', duration: 18}, // feature 3 -> 4
  {kind: 'slide-left', duration: 16}, // feature 4 -> 5
  {kind: 'fade', duration: 20}, // feature 5 -> outro
];

export const TOTAL_DURATION =
  COLD_OPEN_DURATION +
  INTRO_DURATION +
  FEATURES.reduce((sum, f) => sum + f.durationInFrames, 0) +
  OUTRO_DURATION -
  TRANSITIONS.reduce((sum, t) => sum + t.duration, 0);
