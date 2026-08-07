// Mirrors the light palette in webapp/src/jsMain/resources/index.html (:root), so the video
// reads as the same product as the screenshots it frames.
export const theme = {
  bg: '#f6f7f9',
  panel: '#ffffff',
  border: '#e6e8eb',
  text: '#1c2024',
  muted: '#6b7280',
  accent: '#3b82f6',
  accentSoft: '#eaf1fe',
  // The logo's own gradient (star.png): violet through blue to pink.
  gradientFrom: '#7c6cf0',
  gradientVia: '#a78bfa',
  gradientTo: '#ec8fd0',
} as const;

export const fontFamily =
  '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif';
