// Persistent settings, kept in localStorage under "recording". Every write is validated.
(function (R) {
  const KEY = 'recording';
  const CHOICES = {
    theme: ['system', 'light', 'dark'],
    format: ['m4a', 'wav', 'opus'],
    quality: ['standard', 'high'],
    channels: [1, 2],
    processing: ['music', 'voice'],
    mic: ['main', 'back', 'top', 'external']
  };
  const DEFAULTS = { theme: 'system', format: 'm4a', quality: 'standard', channels: 1, processing: 'music', mic: 'main' };

  function valid(key, v) {
    return !!CHOICES[key] && CHOICES[key].indexOf(v) >= 0;
  }

  // Unknown keys and invalid values from an older or damaged save fall back to the defaults.
  function read() {
    let saved = {};
    try { saved = JSON.parse(localStorage.getItem(KEY)) || {}; } catch (e) { saved = {}; }
    const out = Object.assign({}, DEFAULTS);
    Object.keys(DEFAULTS).forEach((k) => { if (valid(k, saved[k])) out[k] = saved[k]; });
    return out;
  }

  let state = read();
  const listeners = [];

  R.store = {
    get: (key) => state[key],
    set(key, v) {
      if (!valid(key, v)) return false;
      state[key] = v;
      try { localStorage.setItem(KEY, JSON.stringify(state)); } catch (e) { /* storage full or blocked: keep it for this session */ }
      listeners.forEach((fn) => fn(key, v));
      return true;
    },
    onChange: (fn) => listeners.push(fn),
    // What the native recorder needs for the next take; it validates these again.
    config: () => ({ format: state.format, quality: state.quality, channels: state.channels, processing: state.processing, mic: state.mic })
  };
})(window.R = window.R || {});
