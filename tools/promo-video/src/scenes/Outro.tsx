import React from 'react';
import {AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {Background} from '../Background';
import {Logo} from '../Logo';
import {fontFamily, theme} from '../theme';

export const Outro: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();

  const wordmarkOpacity = interpolate(frame, [10, 20], [0, 1], {extrapolateRight: 'clamp'});
  const ctaOpacity = interpolate(frame, [20, 30], [0, 1], {extrapolateRight: 'clamp'});
  const ctaY = interpolate(frame, [20, 30], [10, 0], {extrapolateRight: 'clamp'});

  const pillSpring = spring({fps, frame: frame - 30, config: {damping: 11, mass: 0.6}});
  const pillOpacity = interpolate(pillSpring, [0, 1], [0, 1]);
  const pillScale = interpolate(pillSpring, [0, 1], [0.85, 1]);

  const noteOpacity = interpolate(frame, [48, 60], [0, 1], {extrapolateRight: 'clamp'});

  return (
    <Background>
      <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center'}}>
        <Logo size={130} />
        <div
          style={{
            marginTop: 20,
            fontFamily,
            fontSize: 48,
            fontWeight: 700,
            color: theme.text,
            opacity: wordmarkOpacity,
          }}
        >
          stramus
        </div>
        <div
          style={{
            marginTop: 10,
            fontFamily,
            fontSize: 26,
            fontWeight: 600,
            color: theme.muted,
            opacity: ctaOpacity,
            transform: `translateY(${ctaY}px)`,
          }}
        >
          Try it free — takes ten seconds.
        </div>
        <div
          style={{
            marginTop: 28,
            fontFamily,
            fontSize: 30,
            fontWeight: 700,
            color: '#ffffff',
            background: theme.accent,
            padding: '16px 36px',
            borderRadius: 999,
            opacity: pillOpacity,
            transform: `scale(${pillScale})`,
            boxShadow: `0 16px 32px -12px ${theme.accent}88`,
          }}
        >
          stramus.space
        </div>
        <div
          style={{
            marginTop: 22,
            fontFamily,
            fontSize: 18,
            color: theme.muted,
            opacity: noteOpacity,
          }}
        >
          No account needed. No tracking.
        </div>
      </AbsoluteFill>
    </Background>
  );
};
