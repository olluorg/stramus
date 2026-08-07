import React from 'react';
import {Img, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {theme} from './theme';

export const Logo: React.FC<{size: number; delay?: number}> = ({size, delay = 0}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const local = Math.max(0, frame - delay);

  const scale = spring({fps, frame: local, config: {damping: 12, mass: 0.6}});
  const rotate = interpolate(scale, [0, 1], [-25, 0]);
  const opacity = interpolate(local, [0, 12], [0, 1], {extrapolateRight: 'clamp'});

  const pulse = 1 + Math.sin(frame / 14) * 0.035;

  return (
    <div
      style={{
        position: 'relative',
        width: size,
        height: size,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        opacity,
        transform: `scale(${scale * pulse}) rotate(${rotate}deg)`,
      }}
    >
      <div
        style={{
          position: 'absolute',
          width: size * 1.8,
          height: size * 1.8,
          borderRadius: '50%',
          background: `radial-gradient(circle, ${theme.gradientVia}55 0%, ${theme.gradientVia}00 70%)`,
          filter: 'blur(2px)',
        }}
      />
      <Img src={staticFile('logo.png')} style={{width: size, height: size}} />
    </div>
  );
};
