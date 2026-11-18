// Home screen list: each clip's name, length and size, inline playback, and the ⋮ menu.
(function (R) {
  const { $, el, icon } = R.ui;
  const UNDO_MS = 5000;

  let clips = [];
  let playing = { id: null, pos: 0, dur: 0, on: false };
  let seeking = false;
  let pendingDelete = null;   // { clip, timer } while Undo is still possible
  const gone = new Set();      // deleted here, until the next list from the phone confirms it
  let markNext = false;        // highlight the newest clip in the next list, after a take

  function parts(name) {
    const dot = name.lastIndexOf('.');
    return dot > 0 ? { base: name.slice(0, dot), ext: name.slice(dot + 1).toUpperCase() } : { base: name, ext: '' };
  }

  function visible() {
    return clips.filter((c) => !gone.has(c.id) && !(pendingDelete && pendingDelete.clip.id === c.id));
  }

  function render() {
    const list = $('clips');
    list.textContent = '';
    const shown = visible();
    $('empty').hidden = shown.length > 0;
    shown.forEach((c, i) => list.appendChild(row(c, markNext && i === 0)));
    markNext = false;
  }

  function row(c, fresh) {
    const { base, ext } = parts(c.name);
    const li = el('li', 'clip' + (fresh ? ' fresh' : ''));
    li.dataset.id = c.id;
    const top = el('div', 'clip-row');
    const main = el('button', 'clip-main');
    main.setAttribute('aria-label', 'Play ' + base);
    main.appendChild(el('span', 'clip-name', base));
    const meta = el('span', 'clip-meta');
    meta.appendChild(el('span', 'clip-dur', R.format.duration(c.dur)));
    meta.appendChild(el('span', 'clip-size', R.format.size(c.size) + (ext ? ' · ' + ext : '')));
    main.appendChild(meta);
    main.addEventListener('click', () => Android.play(c.id));
    const more = el('button', 'icon more');
    more.setAttribute('aria-label', 'Options for ' + base);
    more.appendChild(icon('more'));
    more.addEventListener('click', () => menu(c));
    top.appendChild(main);
    top.appendChild(more);
    li.appendChild(top);
    if (playing.id === c.id) li.appendChild(player(c));
    return li;
  }

  function player(c) {
    const p = el('div', 'player');
    const toggle = el('button', 'icon play-btn');
    toggle.addEventListener('click', () => (playing.on ? Android.pause() : Android.play(c.id)));
    const range = el('input', 'seek');
    range.type = 'range';
    range.min = '0';
    range.step = '100';
    range.addEventListener('input', () => { seeking = true; time.textContent = clock(Number(range.value)); });
    range.addEventListener('change', () => { seeking = false; Android.seek(Number(range.value)); });
    const time = el('span', 'player-time');
    p.appendChild(toggle);
    p.appendChild(range);
    p.appendChild(time);
    update(p);
    return p;
  }

  function clock(pos) { return R.format.duration(pos) + ' / ' + R.format.duration(playing.dur); }

  // Updates the open player in place, so a drag on the seek bar isn't interrupted.
  function update(p) {
    const toggle = p.querySelector('.play-btn');
    toggle.textContent = '';
    toggle.appendChild(icon(playing.on ? 'pause' : 'play'));
    toggle.setAttribute('aria-label', playing.on ? 'Pause' : 'Play');
    if (seeking) return;
    const range = p.querySelector('.seek');
    range.max = String(playing.dur || 1);
    range.value = String(playing.pos);
    p.querySelector('.player-time').textContent = clock(playing.pos);
  }

  window.onPlay = (id, pos, dur, on) => {
    // Stopped (another clip, a delete, a new take): close the player.
    if (!on && dur === 0) {
      playing = { id: null, pos: 0, dur: 0, on: false };
      render();
      return;
    }
    const opened = playing.id !== id;
    playing = { id, pos, dur, on };
    const p = document.querySelector('.clip[data-id="' + CSS.escape(id) + '"] .player');
    if (opened || !p) render(); else update(p);
  };

  window.onClips = (list) => {
    clips = Array.isArray(list) ? list : [];
    gone.clear();
    render();
  };

  // Long jobs (Make louder): the message stays until the result replaces it.
  window.onBusy = (msg) => R.ui.snack(msg, null, 120000);

  window.onShare = (s) => {
    if (s === 'preparing') R.ui.snack('Making a voice note…', null, 30000);
    else if (s === 'ready') R.ui.hideSnack();
    else R.ui.snack('Couldn’t make a voice note. Try sharing the original file.');
  };

  function menu(c) {
    R.ui.sheet(parts(c.name).base, [
      { label: 'Rename', icon: 'edit', run: () => rename(c) },
      { label: 'Share', icon: 'share', run: () => share(c) },
      { label: 'Make louder', hint: 'Saves a louder copy', icon: 'louder', run: () => Android.louder(c.id) },
      { label: 'Delete', icon: 'trash', danger: true, run: () => remove(c) }
    ]);
  }

  function rename(c) {
    R.ui.prompt('Rename', parts(c.name).base, 'Rename', (text) => Android.rename(c.id, text));
  }

  function share(c) {
    const { ext } = parts(c.name);
    if (!R.app.caps.voiceNote) { Android.share(c.id, false); return; }
    R.ui.sheet('Share', [
      { label: 'Voice note', hint: 'Small Opus file that plays right in the chat (WhatsApp, Telegram)', icon: 'chat', run: () => Android.share(c.id, true) },
      { label: 'Original file', hint: (ext ? ext + ', ' : '') + R.format.size(c.size) + ': full quality', icon: 'file', run: () => Android.share(c.id, false) }
    ]);
  }

  // The row goes at once; the file goes when Undo is no longer offered.
  function remove(c) {
    commitDelete();
    pendingDelete = { clip: c, timer: setTimeout(commitDelete, UNDO_MS) };
    render();
    R.ui.snack('Deleted “' + parts(c.name).base + '”', {
      label: 'Undo',
      run: () => { if (!pendingDelete) return; clearTimeout(pendingDelete.timer); pendingDelete = null; render(); }
    }, UNDO_MS);
  }

  function commitDelete() {
    if (!pendingDelete) return;
    const id = pendingDelete.clip.id;
    clearTimeout(pendingDelete.timer);
    pendingDelete = null;
    gone.add(id);
    Android.delete(id);
  }

  // Leaving the app ends the chance to Undo: delete now, so the file doesn't linger in other apps
  // while this page sits paused in the background.
  function flushDelete() {
    if (!pendingDelete) return;
    commitDelete();
    R.ui.hideSnack();
  }
  window.onHidden = flushDelete;
  document.addEventListener('visibilitychange', () => { if (document.hidden) flushDelete(); });

  R.clips = { markNewest: () => { markNext = true; } };
})(window.R);
