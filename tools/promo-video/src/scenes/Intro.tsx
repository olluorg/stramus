import React from 'react';
import {AbsoluteFill, interpolate, useCurrentFrame} from 'remotion';
import {Background} from '../Background';
import {Logo} from '../Logo';
import {fontFamily, theme} from '../theme';

export const Intro: React.FC = () => {
  const frame = useCurrentFrame();

  const wordmarkOpacity = interpolate(frame, [16, 30], [0, 1], {extrapolateRight: 'clamp'});
  const wordmarkY = interpolate(frame, [16, 30], [16, 0], {extrapolateRight: 'clamp'});
  const taglineOpacity = interpolate(frame, [32, 46], [0, 1], {extrapolateRight: 'clamp'});
  const taglineY = interpolate(frame, [32, 46], [12, 0], {extrapolateRight: 'clamp'});

  return (
    <Background>
      <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center'}}>
        <Logo size={220} />
        <div
          style={{
            marginTop: 28,
            fontFamily,
            fontSize: 88,
            fontWeight: 700,
            letterSpacing: -1,
            opacity: wordmarkOpacity,
            transform: `translateY(${wordmarkY}px)`,
            backgroundImage: `linear-gradient(90deg, ${theme.gradientFrom}, ${theme.gradientVia} 55%, ${theme.gradientTo})`,
            backgroundClip: 'text',
            WebkitBackgroundClip: 'text',
            color: 'transparent',
          }}
        >
          stramus
        </div>
        <div
          style={{
            marginTop: 14,
            fontFamily,
            fontSize: 30,
            color: theme.muted,
            opacity: taglineOpacity,
            transform: `translateY(${taglineY}px)`,
          }}
        >
          Never lose a tab again.
        </div>
      </AbsoluteFill>
    </Background>
  );
};
