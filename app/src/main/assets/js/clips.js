// Home screen list: each clip's name, length and size, and the ⋮ menu. Tapping a clip plays it in
// the player docked at the bottom (player.js).
// Holding a clip starts selecting: taps then add or remove clips, and the bar at the top shares
// or deletes them all.
(function (R) {
  const { $, el, icon } = R.ui;
  const UNDO_MS = 5000;

  let clips = [];
  let pendingDelete = null;   // { clips, timer } while Undo is still possible
  const gone = new Set();      // deleted here, until the next list from the phone confirms it
  let markNext = false;        // highlight the newest clip in the next list, after a take
  let selected = null;         // a Set of ids while selecting, otherwise null

  function parts(name) {
    const dot = name.lastIndexOf('.');
    return dot > 0 ? { base: name.slice(0, dot), ext: name.slice(dot + 1).toUpperCase() } : { base: name, ext: '' };
  }

  function pendingIds() {
    return new Set(pendingDelete ? pendingDelete.clips.map((c) => c.id) : []);
  }

  function visible() {
    const pending = pendingIds();
    return clips.filter((c) => !gone.has(c.id) && !pending.has(c.id));
  }

  function render() {
    const list = $('clips');
    list.textContent = '';
    const shown = visible();
    $('empty').hidden = shown.length > 0;
    shown.forEach((c, i) => list.appendChild(row(c, markNext && i === 0)));
    markNext = false;
    renderBar();
    if (R.player) R.player.refresh();
  }

  function row(c, fresh) {
    const { base } = parts(c.name);
    const on = !!selected && selected.has(c.id);
    const current = !!R.player && R.player.id() === c.id;
    const li = el('li', 'clip' + (fresh ? ' fresh' : '') + (on ? ' selected' : '') + (current ? ' current' : ''));
    li.dataset.id = c.id;
    const top = el('div', 'clip-row');
    const main = el('button', 'clip-main');
    if (selected) {
      main.setAttribute('aria-pressed', String(on));
      main.setAttribute('aria-label', (on ? 'Deselect ' : 'Select ') + base);
    } else {
      main.setAttribute('aria-label', 'Play ' + base);
      if (current) main.setAttribute('aria-current', 'true');
    }
    main.appendChild(el('span', 'clip-name', base));
    main.appendChild(el('span', 'clip-dur', R.format.duration(c.dur)));
    main.addEventListener('click', () => (selected ? toggle(c.id) : Android.play(c.id)));
    R.ui.onHold(main, () => (selected ? toggle(c.id) : startSelecting(c.id)));
    top.appendChild(main);
    // The tick takes the ⋮ button's place while selecting, so the row doesn't shift.
    const side = el('button', 'icon more');
    if (selected) {
      side.setAttribute('aria-hidden', 'true');
      side.tabIndex = -1;
      const tick = el('span', 'tick');
      tick.appendChild(icon('check'));
      side.appendChild(tick);
      side.addEventListener('click', () => toggle(c.id));
    } else {
      side.setAttribute('aria-label', 'Options for ' + base);
      side.appendChild(icon('more'));
      side.addEventListener('click', () => menu(c));
    }
    top.appendChild(side);
    li.appendChild(top);
    return li;
  }

  // Selecting

  function startSelecting(id) {
    if (window.Android && Android.haptic) Android.haptic();
    selected = new Set([id]);
    document.body.classList.add('selecting');
    render();
  }

  function stopSelecting() {
    if (!selected) return false;
    selected = null;
    document.body.classList.remove('selecting');
    render();
    return true;
  }

  function toggle(id) {
    if (selected.has(id)) selected.delete(id); else selected.add(id);
    if (selected.size === 0) { stopSelecting(); return; }
    render();
  }

  function chosen() {
    return visible().filter((c) => selected && selected.has(c.id));
  }

  function renderBar() {
    if (!selected) return;
    const n = selected.size;
    $('select-count').textContent = n + ' selected';
    const all = visible().every((c) => selected.has(c.id));
    $('select-all').textContent = all ? 'None' : 'All';
    $('select-all').setAttribute('aria-label', all ? 'Select none' : 'Select all');
  }

  $('select-close').addEventListener('click', stopSelecting);
  $('select-all').addEventListener('click', () => {
    const shown = visible();
    if (shown.every((c) => selected.has(c.id))) { stopSelecting(); return; }
    shown.forEach((c) => selected.add(c.id));
    render();
  });
  $('select-share').addEventListener('click', () => {
    const list = chosen();
    if (list.length === 1) share(list[0]);
    else if (list.length > 1) Android.shareMany(JSON.stringify(list.map((c) => c.id)));
  });
  $('select-delete').addEventListener('click', () => {
    const list = chosen();
    if (!list.length) return;
    const one = list.length === 1;
    R.ui.confirm(one ? 'Delete “' + parts(list[0].name).base + '”?' : 'Delete ' + list.length + ' recordings?',
      (one ? 'It' : 'They') + ' will be removed from the phone, not just from this list.', 'Delete', () => {
        stopSelecting();
        remove(list);
      });
  });
  R.app.onBack(stopSelecting);

  // From the phone

  window.onClips = (list) => {
    clips = Array.isArray(list) ? list : [];
    gone.clear();
    if (selected) {
      // A selected clip that's gone (deleted in another app) stops being selected.
      const ids = new Set(clips.map((c) => c.id));
      selected.forEach((id) => { if (!ids.has(id)) selected.delete(id); });
      if (selected.size === 0) { stopSelecting(); return; }
    }
    render();
  };

  // Long jobs (Make louder): the message stays until the result replaces it.
  window.onBusy = (msg) => R.ui.snack(msg, null, 120000);

  window.onShare = (s) => {
    if (s === 'preparing') R.ui.snack('Making a voice note…', null, 30000);
    else if (s === 'ready') R.ui.hideSnack();
    else R.ui.snack('Couldn’t make a voice note. Try sharing the original file.');
  };

  // One clip's actions

  function menu(c) {
    const { base, ext } = parts(c.name);
    const details = [R.format.duration(c.dur), R.format.size(c.size), ext].filter(Boolean).join(' · ');
    R.ui.sheet(base, [
      { label: 'Delete', icon: 'trash', danger: true, run: () => remove([c]) },
      { label: 'Rename', icon: 'edit', run: () => rename(c) },
      { label: 'Share', icon: 'share', run: () => share(c) },
      { label: 'Make louder', hint: 'Saves a louder copy', icon: 'louder', run: () => Android.louder(c.id) }
    ], null, details);
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

  // The rows go at once; the files go when Undo is no longer offered.
  function remove(list) {
    commitDelete();
    pendingDelete = { clips: list, timer: setTimeout(commitDelete, UNDO_MS) };
    render();
    const what = list.length === 1 ? '“' + parts(list[0].name).base + '”' : list.length + ' recordings';
    R.ui.snack('Deleted ' + what, {
      label: 'Undo',
      run: () => { if (!pendingDelete) return; clearTimeout(pendingDelete.timer); pendingDelete = null; render(); }
    }, UNDO_MS);
  }

  function commitDelete() {
    if (!pendingDelete) return;
    const list = pendingDelete.clips;
    clearTimeout(pendingDelete.timer);
    pendingDelete = null;
    list.forEach((c) => gone.add(c.id));
    Android.delete(JSON.stringify(list.map((c) => c.id)));
  }

  // Leaving the app ends the chance to Undo: delete now, so the files don't linger in other apps
  // while this page sits paused in the background.
  function flushDelete() {
    if (!pendingDelete) return;
    commitDelete();
    R.ui.hideSnack();
  }
  window.onHidden = flushDelete;
  document.addEventListener('visibilitychange', () => { if (document.hidden) flushDelete(); });

  R.clips = {
    markNewest: () => { markNext = true; },
    // For the player: the list as shown, in order, and a clip's name without its extension.
    visible,
    find: (id) => clips.find((c) => c.id === id) || null,
    title: (c) => parts(c.name).base,
    // The highlighted row follows the player's clip.
    refresh: render
  };
})(window.R);
