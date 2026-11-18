const test = require('node:test');
const assert = require('node:assert');
const { load } = require('./load');

const { R } = load(['format.js']);

test('durations', () => {
  assert.strictEqual(R.format.duration(0), '0:00');
  assert.strictEqual(R.format.duration(7999), '0:07');
  assert.strictEqual(R.format.duration(754000), '12:34');
  assert.strictEqual(R.format.duration(3723000), '1:02:03');
  assert.strictEqual(R.format.duration(-5), '0:00');
  assert.strictEqual(R.format.duration('x'), '0:00');
});

test('sizes', () => {
  assert.strictEqual(R.format.size(512), '512 B');
  assert.strictEqual(R.format.size(820 * 1024), '820 KB');
  assert.strictEqual(R.format.size(1023.7 * 1024), '1.0 MB');
  assert.strictEqual(R.format.size(4.2 * 1024 * 1024), '4.2 MB');
  assert.strictEqual(R.format.size(120 * 1024 * 1024), '120 MB');
  assert.strictEqual(R.format.size(1.3 * 1024 * 1024 * 1024), '1.3 GB');
  assert.strictEqual(R.format.size(null), '0 B');
});
