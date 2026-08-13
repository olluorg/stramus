import React from 'react';
import {linearTiming, TransitionSeries} from '@remotion/transitions';
import {fade} from '@remotion/transitions/fade';
import {slide} from '@remotion/transitions/slide';
import {Audio, staticFile} from 'remotion';
import {
  COLD_OPEN_DURATION,
  FEATURES,
  INTRO_DURATION,
  OUTRO_DURATION,
  TOTAL_DURATION,
  TRANSITIONS,
} from './constants';
import {ColdOpen} from './scenes/ColdOpen';
import {FeatureScene} from './scenes/FeatureScene';
import {Intro} from './scenes/Intro';
import {Outro} from './scenes/Outro';

const presentationFor = (kind: (typeof TRANSITIONS)[number]['kind']) => {
  if (kind === 'slide-left') return slide({direction: 'from-right'});
  if (kind === 'slide-right') return slide({direction: 'from-left'});
  return fade();
};

// A steady procedural pulse (public/audio/README.md has how bed.wav was rendered — generated
// with ffmpeg's lavfi sources, not sourced from anywhere, so there's nothing to license or clear).
// Faded here rather than baked into the wav so retiming the cut never means re-rendering audio.
const AudioBed: React.FC = () => {
  const fadeInFrames = 20;
  const fadeOutFrames = 45;
  return (
    <Audio
      src={staticFile('audio/bed.wav')}
      volume={(f) => {
        const fadeIn = Math.min(1, f / fadeInFrames);
        const fadeOut = Math.min(1, (TOTAL_DURATION - f) / fadeOutFrames);
        return Math.max(0, Math.min(fadeIn, fadeOut)) * 0.75;
      }}
    />
  );
};

export const Promo: React.FC = () => {
  const [t0, t1, t2, t3, t4, t5, t6] = TRANSITIONS;

  return (
    <>
      <AudioBed />
      <TransitionSeries>
        <TransitionSeries.Sequence durationInFrames={COLD_OPEN_DURATION}>
          <ColdOpen />
        </TransitionSeries.Sequence>

        <TransitionSeries.Transition
          presentation={presentationFor(t0.kind)}
          timing={linearTiming({durationInFrames: t0.duration})}
        />
        <TransitionSeries.Sequence durationInFrames={INTRO_DURATION}>
          <Intro />
        </TransitionSeries.Sequence>

        <TransitionSeries.Transition
          presentation={presentationFor(t1.kind)}
          timing={linearTiming({durationInFrames: t1.duration})}
        />
        <TransitionSeries.Sequence durationInFrames={FEATURES[0].durationInFrames}>
          <FeatureScene {...FEATURES[0]} />
        </TransitionSeries.Sequence>

        <TransitionSeries.Transition
          presentation={presentationFor(t2.kind)}
          timing={linearTiming({durationInFrames: t2.duration})}
        />
        <TransitionSeries.Sequence durationInFrames={FEATURES[1].durationInFrames}>
          <FeatureScene {...FEATURES[1]} />
        </TransitionSeries.Sequence>

        <TransitionSeries.Transition
          presentation={presentationFor(t3.kind)}
          timing={linearTiming({durationInFrames: t3.duration})}
        />
        <TransitionSeries.Sequence durationInFrames={FEATURES[2].durationInFrames}>
          <FeatureScene {...FEATURES[2]} />
        </TransitionSeries.Sequence>

        <TransitionSeries.Transition
          presentation={presentationFor(t4.kind)}
          timing={linearTiming({durationInFrames: t4.duration})}
        />
        <TransitionSeries.Sequence durationInFrames={FEATURES[3].durationInFrames}>
          <FeatureScene {...FEATURES[3]} />
        </TransitionSeries.Sequence>

        <TransitionSeries.Transition
          presentation={presentationFor(t5.kind)}
          timing={linearTiming({durationInFrames: t5.duration})}
        />
        <TransitionSeries.Sequence durationInFrames={FEATURES[4].durationInFrames}>
          <FeatureScene {...FEATURES[4]} />
        </TransitionSeries.Sequence>

        <TransitionSeries.Transition
          presentation={presentationFor(t6.kind)}
          timing={linearTiming({durationInFrames: t6.duration})}
        />
        <TransitionSeries.Sequence durationInFrames={OUTRO_DURATION}>
          <Outro />
        </TransitionSeries.Sequence>
      </TransitionSeries>
    </>
  );
};
