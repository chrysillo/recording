// Screen router, settings bindings and start-up. Loaded after store.js and ui.js, before the screens.
//
// A screen is <section class="screen" id="s-NAME"> plus an object registered with
// R.app.register(NAME, { enter(), leave() }), both optional. Home is the root; others push on top.
(function (R) {
  const { $ } = R.ui;
  const screens = {};
  let stack = ['home'];
  const backSteps = [];   // modes that Back leaves before changing screen, such as selecting clips

  function show() {
    document.querySelectorAll('.screen').forEach((s) => s.classList.remove('on'));
    const name = stack[stack.length - 1];
    const node = $('s-' + name);
    node.classList.add('on');
    node.querySelector('.body').scrollTop = 0;
    if (screens[name] && screens[name].enter) screens[name].enter();
  }

  function leaveCurrent() {
    const s = screens[stack[stack.length - 1]];
    if (s && s.leave) s.leave();
  }

  // Light, dark or the system's choice; theme.css swaps the colour tokens on data-theme.
  function applyTheme() {
    const t = R.store.get('theme');
    if (t === 'system') delete document.documentElement.dataset.theme;
    else document.documentElement.dataset.theme = t;
    if (window.Android) Android.setTheme(t);
  }

  // Every .seg[data-setting] shows and sets its setting; numeric values are stored as numbers.
  function bindSettings() {
    document.querySelectorAll('.seg[data-setting]').forEach((seg) => {
      const key = seg.dataset.setting;
      seg.querySelectorAll('button').forEach((b) => {
        b.addEventListener('click', () => {
          const raw = b.dataset.value;
          R.store.set(key, /^\d+$/.test(raw) ? Number(raw) : raw);
        });
      });
    });
    refreshSegs();
    R.store.onChange(refreshSegs);
  }

  function refreshSegs() {
    document.querySelectorAll('.seg[data-setting]').forEach((seg) => {
      const v = String(R.store.get(seg.dataset.setting));
      seg.querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.value === v));
    });
  }

  R.app = {
    register(name, s) { screens[name] = s; },
    // fn() returns true if it handled Back (it was in its mode and has left it).
    onBack(fn) { backSteps.push(fn); },
    go(name) {
      if (stack[stack.length - 1] === name) return;
      leaveCurrent();
      stack.push(name);
      show();
    },
    // Called by MainActivity on Back; true means the page handled it, false lets the app close.
    back() {
      if (R.ui.closeTop()) return true;
      if (backSteps.some((fn) => fn())) return true;
      if (stack.length > 1) {
        leaveCurrent();
        stack.pop();
        show();
        return true;
      }
      return false;
    },
    // What the phone can do, from the native side; fixed for the app's lifetime.
    caps: { opus: false, voiceNote: false, folder: 'Recordings/Recording' },
    start() {
      if (window.Android) {
        try { Object.assign(R.app.caps, JSON.parse(Android.caps())); } catch (e) { /* keep the defaults */ }
      }
      document.querySelectorAll('[data-go]').forEach((b) => b.addEventListener('click', () => R.app.go(b.dataset.go)));
      document.querySelectorAll('[data-back]').forEach((b) => b.addEventListener('click', () => R.app.back()));
      R.store.onChange((key) => { if (key === 'theme') applyTheme(); });
      bindSettings();
      applyTheme();
      show();
      if (window.Android) Android.listClips();
    }
  };

  window.onToast = (msg) => { if (msg) R.ui.snack(msg); };
})(window.R);
