// Home screen top: the record button with Discard and Save beside it while paused, the timer and
// the level halo. The mic chip below it is mic.js.
(function (R) {
  const { $ } = R.ui;
  // Raw (Natural) takes are quiet: on a Sony Xperia 1 VI a room sits around -75 dB and speech
  // peaks around -25 dB, so the scale starts low enough for both to show.
  const METER_FLOOR_DB = -80;   // quieter than this reads as silence
  const RELEASE = 0.85;         // each update the halo keeps this much of its last size, so it falls back smoothly
  const HOT_DB = -6;            // the halo turns amber above this
  const HALO_GROW = 0.7;        // the halo's extra size at full scale, as a fraction of the button
  const CLIP_HOLD_MS = 1500;    // how long the halo stays red after an over

  // idle -> recording <-> paused -> idle. While a request is with the native side, `busy` blocks
  // more taps until it confirms with onRecState.
  let state = 'idle';
  let busy = false;
  let ms = 0;
  let clipTimer = null;
  let shown = 0;                // the halo's current level, 0 to 1, after the slow release
  const btn = $('rec');

  function message(text) {
    const m = $('rec-msg');
    m.textContent = text || '';
    m.hidden = !text;
  }

  function render() {
    const live = state === 'recording' || state === 'paused';
    document.body.classList.toggle('recording', state === 'recording');
    document.body.classList.toggle('paused', state === 'paused');
    $('rec-icon').setAttribute('href', state === 'recording' ? '#i-pause' : '#i-mic');
    btn.setAttribute('aria-label', state === 'recording' ? 'Pause' : state === 'paused' ? 'Resume recording' : 'Start recording');
    $('rec-state').textContent = state === 'recording' ? 'Recording' : state === 'paused' ? 'Paused' : 'Tap to record';
    [btn, $('rec-save'), $('rec-discard')].forEach((b) => { b.disabled = busy; });
    if (!live) $('rec-time').textContent = R.format.duration(0);
    if (state !== 'recording') {
      shown = 0;
      $('halo').style.transform = 'scale(1)';
      $('halo').classList.remove('hot', 'over');
    }
  }

  function request(call) {
    if (!window.Android || busy) return;
    busy = true;
    call();
    render();
  }

  btn.addEventListener('click', () => {
    if (state === 'recording') request(() => Android.pauseRecording());
    else if (state === 'paused') request(() => Android.resumeRecording());
    else request(() => { message(''); Android.startRecording(JSON.stringify(R.store.config())); });
  });

  $('rec-save').addEventListener('click', () => request(() => Android.stopRecording()));

  $('rec-discard').addEventListener('click', () => {
    R.ui.confirm('Discard this recording?', R.format.duration(ms) + ' of audio will be deleted.', 'Discard',
      () => request(() => Android.discardRecording()));
  });

  window.onRecState = (s, at, msg) => {
    busy = false;
    if (s === 'recording' || s === 'paused') {
      state = s;
      ms = at;
      $('rec-time').textContent = R.format.duration(at);
    } else {
      state = 'idle';
      ms = 0;
      if (s === 'stopped') R.clips.markNewest();
      if (s === 'discarded') R.ui.snack('Recording discarded');
      if (s === 'denied') message('Microphone access is off. Allow it in Settings › Apps › Recording › Permissions.');
      else if (msg) message(msg);
    }
    render();
  };

  window.onLevel = (db, clipped, at) => {
    if (state !== 'recording') return;
    ms = at;
    $('rec-time').textContent = R.format.duration(at);
    const halo = $('halo');
    const level = Math.max(0, Math.min(1, (db - METER_FLOOR_DB) / -METER_FLOOR_DB));
    shown = Math.max(level, shown * RELEASE);   // rises at once, falls back slowly, like a peak meter
    halo.style.transform = 'scale(' + (1 + HALO_GROW * shown) + ')';
    halo.classList.toggle('hot', db > HOT_DB);
    if (clipped) {
      halo.classList.add('over');
      clearTimeout(clipTimer);
      clipTimer = setTimeout(() => halo.classList.remove('over'), CLIP_HOLD_MS);
    }
  };

  R.app.register('home', { enter: () => { R.mic.load(); render(); } });
})(window.R);
