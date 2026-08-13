import React from 'react';
import {Img, interpolate, useCurrentFrame} from 'remotion';
import {WindowChrome} from './WindowChrome';

// A slow Ken Burns zoom/pan so a static screenshot doesn't sit dead on screen for four seconds.
export const BrowserFrame: React.FC<{
  src: string;
  width: number;
  height: number;
  durationInFrames: number;
  pan?: 'left' | 'right';
}> = ({src, width, height, durationInFrames, pan = 'left'}) => {
  const frame = useCurrentFrame();

  const zoom = interpolate(frame, [0, durationInFrames], [1, 1.07], {
    extrapolateRight: 'clamp',
  });
  const panPx = interpolate(frame, [0, durationInFrames], [0, pan === 'left' ? -18 : 18], {
    extrapolateRight: 'clamp',
  });

  return (
    <WindowChrome width={width} height={height}>
      <Img
        src={src}
        style={{
          width: '100%',
          height: '100%',
          objectFit: 'cover',
          objectPosition: 'top center',
          transform: `scale(${zoom}) translateX(${panPx}px)`,
          transformOrigin: 'top center',
        }}
      />
    </WindowChrome>
  );
};
