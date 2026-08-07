import React from 'react';
import {interpolate, OffthreadVideo, useCurrentFrame} from 'remotion';
import {WindowChrome} from './WindowChrome';

// Real captured interaction footage (tools/screenshots/capture-promo-clips.mjs) instead of a
// panned still — the zoom here is subtler than BrowserFrame's since the clip already has motion
// of its own; piling a big Ken Burns move on top of real UI movement reads as shaky, not lively.
export const VideoFrame: React.FC<{
  src: string;
  width: number;
  height: number;
  durationInFrames: number;
}> = ({src, width, height, durationInFrames}) => {
  const frame = useCurrentFrame();

  const zoom = interpolate(frame, [0, durationInFrames], [1, 1.025], {
    extrapolateRight: 'clamp',
  });

  return (
    <WindowChrome width={width} height={height}>
      <OffthreadVideo
        src={src}
        muted
        style={{
          width: '100%',
          height: '100%',
          objectFit: 'cover',
          objectPosition: 'top center',
          transform: `scale(${zoom})`,
          transformOrigin: 'top center',
        }}
      />
    </WindowChrome>
  );
};
