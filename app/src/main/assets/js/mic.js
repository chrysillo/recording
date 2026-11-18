// The mic chip on the home screen and the picker it opens, which says what each mic is best for.
(function (R) {
  const { $ } = R.ui;

  const MICS = {
    main: { label: 'Main mic', title: 'Main (bottom)', use: 'One voice or instrument up close: talking, singing, practising' },
    back: { label: 'Back mic', title: 'Back', use: 'A rehearsal or band in front of the phone; stand it up facing them' },
    top: { label: 'Top mic', title: 'Top', use: 'Loud sources from a distance (drums, amps), or a room' }
  };

  // From the bridge: which positions the phone has, and a plugged-in mic ("USB" or "Headset") or null.
  let data = { positions: ['main'], external: null };

  function load() {
    if (!window.Android) return;
    try { data = JSON.parse(Android.mics()); } catch (e) { /* keep the last list */ }
    render();
  }

  // The saved choice, or Main when that mic isn't there now.
  function current() {
    const m = R.store.get('mic');
    if (m === 'external') return data.external ? m : 'main';
    return data.positions.indexOf(m) >= 0 ? m : 'main';
  }

  function render() {
    const c = current();
    $('mic-chip-text').textContent = c === 'external' ? data.external + ' mic' : MICS[c].label;
  }

  function pick() {
    const on = current();
    const items = data.positions.map((p) => ({
      label: MICS[p].title, hint: MICS[p].use, on: on === p, run: () => R.store.set('mic', p)
    }));
    if (data.external) {
      items.unshift({ label: data.external, hint: 'Your plugged-in mic; usually the best', on: on === 'external', run: () => R.store.set('mic', 'external') });
    }
    R.ui.sheet('Microphone', items, items.length > 1 ? 'Not sure? Back for a group, Main for one person.' : '');
  }

  $('mic-chip').addEventListener('click', pick);
  R.store.onChange((key) => { if (key === 'mic') render(); });

  // Sent when a mic is plugged in or removed, and on resume.
  window.onMics = (d) => {
    if (d && Array.isArray(d.positions)) data = d;
    render();
  };

  R.mic = { load };
})(window.R);
