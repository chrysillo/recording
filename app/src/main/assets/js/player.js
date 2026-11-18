// The player docked at the bottom of the home screen. The clip tapped last plays here, with a
// seek bar, previous/next (by the list's order) and a close button; it's hidden when nothing is loaded.
(function (R) {
  const { $ } = R.ui;
  const RESTART_MS = 3000;   // Previous after this far in starts the clip again, as music players do

  let now = { id: null, pos: 0, dur: 0, on: false };
  let seeking = false;
  const bar = $('player');
  const range = $('player-seek');

  function render() {
    const c = now.id ? R.clips.find(now.id) : null;
    const show = !!c;
    bar.hidden = !show;
    document.body.classList.toggle('has-player', show);
    if (!show) return;
    $('player-name').textContent = R.clips.title(c);
    $('player-icon').setAttribute('href', now.on ? '#i-pause' : '#i-play');
    $('player-toggle').setAttribute('aria-label', now.on ? 'Pause' : 'Play');
    if (!seeking) {
      range.max = String(now.dur || 1);
      range.value = String(now.pos);
      $('player-pos').textContent = R.format.duration(now.pos);
    }
    $('player-dur').textContent = R.format.duration(now.dur);
    $('player-next').disabled = !neighbour(1);
  }

  // The clip before (-1) or after (1) the current one in the list as shown.
  function neighbour(step) {
    const list = R.clips.visible();
    const i = list.findIndex((c) => c.id === now.id);
    return i < 0 ? null : list[i + step] || null;
  }

  $('player-toggle').addEventListener('click', () => (now.on ? Android.pause() : Android.play(now.id)));
  $('player-prev').addEventListener('click', () => {
    const prev = neighbour(-1);
    if (now.pos > RESTART_MS || !prev) Android.seek(0);
    else Android.play(prev.id);
  });
  $('player-next').addEventListener('click', () => {
    const next = neighbour(1);
    if (next) Android.play(next.id);
  });
  $('player-close').addEventListener('click', () => Android.stopPlayback());
  range.addEventListener('input', () => { seeking = true; $('player-pos').textContent = R.format.duration(Number(range.value)); });
  range.addEventListener('change', () => { seeking = false; Android.seek(Number(range.value)); });

  // From the phone: durMs 0 means stopped (closed, another take started, the clip deleted).
  window.onPlay = (id, pos, dur, on) => {
    const before = now.id;
    now = !on && dur === 0 ? { id: null, pos: 0, dur: 0, on: false } : { id, pos, dur, on };
    if (now.id !== before) { R.clips.refresh(); reveal(); } else render();
  };

  // A clip that starts playing low on the screen (or under the player that just appeared) is
  // scrolled up to sit just above the player; one already in view stays put.
  function reveal() {
    if (!now.id) return;
    const li = document.querySelector('#clips li[data-id="' + CSS.escape(now.id) + '"]');
    if (li) li.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }

  R.player = { id: () => now.id, refresh: render };
})(window.R);
