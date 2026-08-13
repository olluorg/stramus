import React from 'react';
import {AbsoluteFill} from 'remotion';
import {theme} from './theme';

// A light chrome-less "window" around app footage: the store screenshots are already chrome-free
// (Web Store rule) and the recorded clips are the app alone too, so this only needs to suggest
// "this is a browser tab", not reproduce a real one.
export const WindowChrome: React.FC<{
  width: number;
  height: number;
  children: React.ReactNode;
}> = ({width, height, children}) => {
  return (
    <div
      style={{
        width,
        height,
        borderRadius: 16,
        overflow: 'hidden',
        background: theme.panel,
        boxShadow:
          '0 30px 60px -20px rgba(28, 32, 36, 0.35), 0 10px 24px -12px rgba(28, 32, 36, 0.25), 0 0 0 1px rgba(28, 32, 36, 0.06)',
      }}
    >
      <div
        style={{
          height: 34,
          display: 'flex',
          alignItems: 'center',
          gap: 7,
          padding: '0 14px',
          background: '#eceef1',
          borderBottom: `1px solid ${theme.border}`,
        }}
      >
        <div style={{width: 11, height: 11, borderRadius: 999, background: '#ff5f57'}} />
        <div style={{width: 11, height: 11, borderRadius: 999, background: '#febc2e'}} />
        <div style={{width: 11, height: 11, borderRadius: 999, background: '#28c840'}} />
      </div>
      <AbsoluteFill style={{top: 34, overflow: 'hidden'}}>{children}</AbsoluteFill>
    </div>
  );
};
