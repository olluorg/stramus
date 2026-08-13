import React from 'react';
import {Composition} from 'remotion';
import {FPS, HEIGHT, TOTAL_DURATION, WIDTH} from './constants';
import {Promo} from './Promo';

export const Root: React.FC = () => {
  return (
    <>
      <Composition
        id="Promo"
        component={Promo}
        durationInFrames={TOTAL_DURATION}
        fps={FPS}
        width={WIDTH}
        height={HEIGHT}
      />
    </>
  );
};
