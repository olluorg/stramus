// The catalogue of things this tool knows how to capture. Add a scenario file under scenarios/,
// then a line here, to add a new shot — nothing else changes.

export const SHOTS = [
  // The five required by store/README.md's Chrome Web Store listing.
  { id: '01-collection', module: './scenarios/01-collection.mjs', output: '01-collection.png' },
  { id: '02-tabs', module: './scenarios/02-tabs.mjs', output: '02-tabs.png' },
  { id: '03-search', module: './scenarios/03-search.mjs', output: '03-search.png' },
  { id: '04-assistant', module: './scenarios/04-assistant.mjs', output: '04-assistant.png' },
  { id: '05-settings', module: './scenarios/05-settings.mjs', output: '05-settings.png' },

  // Bonus feature shots — onboarding / FAQ material, not part of the store listing itself.
  { id: '06-ai-settings', module: './scenarios/06-ai-settings.mjs', output: '06-ai-settings.png' },
  { id: '07-data-export', module: './scenarios/07-data-export.mjs', output: '07-data-export.png' },
  { id: '08-note-editor', module: './scenarios/08-note-editor.mjs', output: '08-note-editor.png' },
  { id: '09-rename-card', module: './scenarios/09-rename-card.mjs', output: '09-rename-card.png' },
  { id: '10-folder-view', module: './scenarios/10-folder-view.mjs', output: '10-folder-view.png' },
];

export const STORE_SHOT_IDS = ['01-collection', '02-tabs', '03-search', '04-assistant', '05-settings'];
