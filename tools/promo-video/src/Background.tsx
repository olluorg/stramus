import React from 'react';
import {AbsoluteFill} from 'remotion';
import {theme} from './theme';

export const Background: React.FC<{children?: React.ReactNode}> = ({children}) => {
  return (
    <AbsoluteFill style={{background: theme.bg}}>
      <AbsoluteFill
        style={{
          background: `radial-gradient(1100px 700px at 18% -6%, ${theme.gradientFrom}22, transparent 60%),
                       radial-gradient(1000px 650px at 100% 100%, ${theme.gradientTo}22, transparent 55%)`,
        }}
      />
      {children}
    </AbsoluteFill>
  );
};
