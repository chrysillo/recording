const test = require('node:test');
const assert = require('node:assert');
const { load } = require('./load');

test('defaults when nothing is saved', () => {
  const { R } = load(['store.js']);
  assert.strictEqual(R.store.get('theme'), 'system');
});

test('damaged or invalid saves fall back to the defaults', () => {
  assert.strictEqual(load(['store.js'], { saved: '{not json' }).R.store.get('theme'), 'system');
  assert.strictEqual(load(['store.js'], { saved: '{"theme":"neon"}' }).R.store.get('theme'), 'system');
});

test('valid writes are saved, invalid ones refused', () => {
  const { R, saved } = load(['store.js']);
  assert.strictEqual(R.store.set('theme', 'dark'), true);
  assert.strictEqual(JSON.parse(saved()).theme, 'dark');
  assert.strictEqual(R.store.set('theme', 'neon'), false);
  assert.strictEqual(R.store.set('other', 1), false);
  assert.strictEqual(R.store.get('theme'), 'dark');
});

test('a saved choice is read back', () => {
  const { R } = load(['store.js'], { saved: '{"theme":"light"}' });
  assert.strictEqual(R.store.get('theme'), 'light');
});

test('recording settings are validated', () => {
  const { R } = load(['store.js']);
  assert.deepStrictEqual({ ...R.store.config() }, { format: 'm4a', quality: 'standard', channels: 1, processing: 'music', mic: 'main' });
  assert.strictEqual(R.store.set('format', 'wav'), true);
  assert.strictEqual(R.store.set('format', 'mp3'), false);
  assert.strictEqual(R.store.set('channels', 2), true);
  assert.strictEqual(R.store.set('channels', '2'), false);
  assert.strictEqual(R.store.set('mic', 'back'), true);
  assert.strictEqual(R.store.set('mic', 'auto'), false);
  assert.strictEqual(R.store.set('processing', 'room'), false);
  assert.deepStrictEqual({ ...R.store.config() }, { format: 'wav', quality: 'standard', channels: 2, processing: 'music', mic: 'back' });
});

test('a damaged recording setting falls back on its own', () => {
  const { R } = load(['store.js'], { saved: '{"format":"flac","processing":"voice","mic":"auto"}' });
  assert.strictEqual(R.store.get('format'), 'm4a');
  assert.strictEqual(R.store.get('processing'), 'voice');
  assert.strictEqual(R.store.get('mic'), 'main');
});
