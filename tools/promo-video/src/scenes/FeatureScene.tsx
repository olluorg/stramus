import React from 'react';
import {AbsoluteFill, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {Background} from '../Background';
import {BrowserFrame} from '../BrowserFrame';
import {VideoFrame} from '../VideoFrame';
import {fontFamily, theme} from '../theme';
import type {FeatureMedia} from '../constants';

export const FeatureScene: React.FC<{
  index: string;
  eyebrow: string;
  title: string;
  subtitle: string;
  media: FeatureMedia;
  pan?: 'left' | 'right';
  durationInFrames: number;
}> = ({index, eyebrow, title, subtitle, media, pan = 'left', durationInFrames}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();

  const enter = spring({fps, frame, config: {damping: 16, mass: 0.7}});
  const frameScale = interpolate(enter, [0, 1], [0.94, 1]);
  const frameOpacity = interpolate(frame, [0, 14], [0, 1], {extrapolateRight: 'clamp'});
  const textOpacity = interpolate(frame, [4, 18], [0, 1], {extrapolateRight: 'clamp'});
  const textY = interpolate(frame, [4, 18], [14, 0], {extrapolateRight: 'clamp'});
  const subtitleOpacity = interpolate(frame, [12, 26], [0, 1], {extrapolateRight: 'clamp'});

  // A small mid-scene punch (not tied to any specific on-screen event, just a beat) so a held
  // shot doesn't sit perfectly still for four seconds — a cheap stand-in for cutting to the exact
  // frame something happens, which the fixed-length demo footage doesn't hand us on a platter.
  const punchAt = Math.round(durationInFrames * 0.55);
  const punch = spring({fps, frame: frame - punchAt, config: {damping: 14, mass: 0.4}, durationInFrames: 14});
  const punchScale = 1 + interpolate(punch, [0, 1], [0, 0.018]);

  const frameWidth = 1180;
  const frameHeight = Math.round(frameWidth * (800 / 1280));
  const resolvedSrc = staticFile(media.src);

  return (
    <Background>
      <AbsoluteFill style={{alignItems: 'center', paddingTop: 96}}>
        <div
          style={{
            fontFamily,
            fontSize: 20,
            fontWeight: 700,
            letterSpacing: 3,
            color: theme.accent,
            opacity: textOpacity,
            transform: `translateY(${textY}px)`,
          }}
        >
          {index} · {eyebrow.toUpperCase()}
        </div>
        <div
          style={{
            marginTop: 14,
            fontFamily,
            fontSize: 46,
            fontWeight: 700,
            color: theme.text,
            textAlign: 'center',
            maxWidth: 1200,
            lineHeight: 1.15,
            opacity: textOpacity,
            transform: `translateY(${textY}px)`,
          }}
        >
          {title}
        </div>
        <div
          style={{
            marginTop: 8,
            fontFamily,
            fontSize: 22,
            color: theme.muted,
            textAlign: 'center',
            opacity: subtitleOpacity,
          }}
        >
          {subtitle}
        </div>
        <div
          style={{
            marginTop: 34,
            opacity: frameOpacity,
            transform: `scale(${frameScale * punchScale})`,
          }}
        >
          {media.type === 'video' ? (
            <VideoFrame
              src={resolvedSrc}
              width={frameWidth}
              height={frameHeight}
              durationInFrames={durationInFrames}
            />
          ) : (
            <BrowserFrame
              src={resolvedSrc}
              width={frameWidth}
              height={frameHeight}
              durationInFrames={durationInFrames}
              pan={pan}
            />
          )}
        </div>
      </AbsoluteFill>
    </Background>
  );
};
