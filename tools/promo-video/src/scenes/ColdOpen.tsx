import React from 'react';
import {AbsoluteFill, interpolate, useCurrentFrame} from 'remotion';
import {fontFamily} from '../theme';

// Fixed pseudo-random layout for the scattered "tab" clutter behind the text — deterministic so
// every render is pixel-identical, which a real Math.random() wouldn't give across frames/renders.
const CLUTTER = Array.from({length: 22}).map((_, i) => {
  const seed = i * 9301 + 49297;
  const rand = (n: number) => ((seed * n) % 233280) / 233280;
  return {
    left: rand(1) * 100,
    top: rand(3) * 100,
    w: 60 + rand(7) * 90,
    h: 10 + rand(11) * 6,
    rot: rand(13) * 10 - 5,
    opacity: 0.05 + rand(17) * 0.09,
  };
});

const DARK_BG = '#101114';

export const ColdOpen: React.FC = () => {
  const frame = useCurrentFrame();

  const line1Opacity = interpolate(frame, [0, 8, 30, 36], [0, 1, 1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const line2Opacity = interpolate(frame, [38, 46], [0, 1], {extrapolateRight: 'clamp'});
  const line2Y = interpolate(frame, [38, 46], [10, 0], {extrapolateRight: 'clamp'});

  const clutterOpacity = interpolate(frame, [0, 12], [0, 1], {extrapolateRight: 'clamp'});

  return (
    <AbsoluteFill style={{background: DARK_BG, alignItems: 'center', justifyContent: 'center'}}>
      <AbsoluteFill style={{opacity: clutterOpacity}}>
        {CLUTTER.map((c, i) => (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: `${c.left}%`,
              top: `${c.top}%`,
              width: c.w,
              height: c.h,
              borderRadius: 4,
              background: '#ffffff',
              opacity: c.opacity,
              transform: `rotate(${c.rot}deg)`,
            }}
          />
        ))}
      </AbsoluteFill>

      <div style={{position: 'absolute', textAlign: 'center', opacity: line1Opacity}}>
        <div style={{fontFamily, fontSize: 58, fontWeight: 700, color: '#ffffff'}}>
          47 tabs open.
        </div>
      </div>

      <div
        style={{
          position: 'absolute',
          textAlign: 'center',
          opacity: line2Opacity,
          transform: `translateY(${line2Y}px)`,
          padding: '0 80px',
        }}
      >
        <div style={{fontFamily, fontSize: 50, fontWeight: 700, color: '#ffffff', lineHeight: 1.2}}>
          Somewhere in there is the one you needed.
        </div>
      </div>
    </AbsoluteFill>
  );
};
