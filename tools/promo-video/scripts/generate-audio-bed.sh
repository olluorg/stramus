#!/usr/bin/env bash
# Regenerates public/audio/bed.wav: a plain generated rhythm bed (low pad hum + a soft "kick" +
# a softer offbeat "tick", one bar = 1.2s), looped to comfortably cover the composition's length.
# Nothing here is sourced from anywhere — ffmpeg's lavfi sine/anullsrc generators only — so there
# is no license to track.
#
# Built via concat/constant `volume`, not the `volume` filter's per-frame expression mode: this
# repo's Remotion install bundles ffmpeg with a trimmed filter set (no `sin`, no `afade`, no
# `apulsator` — see node_modules/@remotion/compositor-linux-x64-gnu's build config), and that
# build's eval() rejects trig functions outright. The fade in/out that would normally live in the
# wav instead happens in Promo.tsx's <Audio volume={...}> — see the comment there.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$HERE/../public/audio"
FFMPEG="$HERE/../node_modules/@remotion/compositor-linux-x64-gnu/ffmpeg"
BAR="$OUT_DIR/.bar.wav"
BED="$OUT_DIR/bed.wav"
LIST="$OUT_DIR/.list.txt"

mkdir -p "$OUT_DIR"

# One 1.2s bar: pad (110Hz + a fifth at 164.81Hz, quiet) + a 90ms 90Hz kick at the top of the bar
# + a 50ms 1200Hz tick at the halfway point.
"$FFMPEG" -y \
  -f lavfi -i "sine=frequency=110:duration=1.2:sample_rate=44100" \
  -f lavfi -i "sine=frequency=164.81:duration=1.2:sample_rate=44100" \
  -f lavfi -i "sine=frequency=90:duration=0.09:sample_rate=44100" \
  -f lavfi -i "anullsrc=duration=1.11:sample_rate=44100:channel_layout=mono" \
  -f lavfi -i "anullsrc=duration=0.6:sample_rate=44100:channel_layout=mono" \
  -f lavfi -i "sine=frequency=1200:duration=0.05:sample_rate=44100" \
  -f lavfi -i "anullsrc=duration=0.55:sample_rate=44100:channel_layout=mono" \
  -filter_complex "
    [0:a]volume=0.09[pad1];
    [1:a]volume=0.06[pad2];
    [pad1][pad2]amix=inputs=2:normalize=0[pad];
    [2:a]volume=0.38[kickhit];
    [kickhit][3:a]concat=n=2:v=0:a=1[kick];
    [5:a]volume=0.12[tickhit];
    [4:a][tickhit][6:a]concat=n=3:v=0:a=1[tick];
    [pad][kick][tick]amix=inputs=3:normalize=0[bar]
  " \
  -map "[bar]" -ac 2 -ar 44100 "$BAR"

# Looped 22x (26.4s) — comfortably past TOTAL_DURATION (src/constants.ts) at 30fps; the concat
# demuxer trims cleanly with -t, unlike this build's -stream_loop (which ignored -t on a wav here).
python3 -c "
with open('$LIST', 'w') as f:
    for _ in range(22):
        f.write(\"file '$BAR'\n\")
"
"$FFMPEG" -y -f concat -safe 0 -i "$LIST" -t 23.3 -ac 2 -ar 44100 "$BED"

rm -f "$BAR" "$LIST"
echo "wrote $BED"
