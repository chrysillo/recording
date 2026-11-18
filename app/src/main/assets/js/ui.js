// DOM helpers, and the sheet, dialog and snackbar that sit over every screen.
(function (R) {
  const $ = (id) => document.getElementById(id);
  const SVG = 'http://www.w3.org/2000/svg';

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function icon(name) {
    const s = document.createElementNS(SVG, 'svg');
    const u = document.createElementNS(SVG, 'use');
    u.setAttribute('href', '#i-' + name);
    s.appendChild(u);
    return s;
  }

  // Only one sheet or dialog is open at a time; Back closes it before anything else.
  let closer = null;
  function closeTop() {
    if (!closer) return false;
    const c = closer;
    closer = null;
    c();
    return true;
  }

  function overlay(box) {
    closeTop();
    const scrim = el('div', 'scrim');
    scrim.appendChild(box);
    scrim.addEventListener('click', (e) => { if (e.target === scrim) closeTop(); });
    document.body.appendChild(scrim);
    closer = () => scrim.remove();
    return scrim;
  }

  // A bottom sheet of actions: items are { label, hint, icon, danger, on, run }. An item with
  // `on` is the current choice and gets a check; the others keep the space so labels line up.
  // `details` is a line under the title (a clip's length, size and format); `footer` a tip below.
  function sheet(title, items, footer, details) {
    const box = el('div', 'sheet');
    if (title) box.appendChild(el('p', 'sheet-title', title));
    if (details) box.appendChild(el('p', 'sheet-details', details));
    items.forEach((it) => {
      const b = el('button', 'sheet-item' + (it.danger ? ' danger' : '') + (it.on ? ' on' : ''));
      if (it.icon) b.appendChild(icon(it.icon));
      else if ('on' in it) { const c = icon('check'); if (!it.on) c.classList.add('blank'); b.appendChild(c); }
      const text = el('span', 'sheet-text');
      text.appendChild(el('span', null, it.label));
      if (it.hint) text.appendChild(el('span', 'hint', it.hint));
      b.appendChild(text);
      b.addEventListener('click', () => { closeTop(); it.run(); });
      box.appendChild(b);
    });
    if (footer) box.appendChild(el('p', 'sheet-tip', footer));
    overlay(box);
  }

  // A one-line text dialog. done(text) runs on OK or Enter.
  function prompt(title, value, okLabel, done) {
    const box = el('form', 'dialog');
    box.appendChild(el('p', 'dialog-title', title));
    const input = el('input', 'dialog-input');
    input.type = 'text';
    input.value = value;
    input.maxLength = 80;
    input.setAttribute('autocapitalize', 'sentences');
    box.appendChild(input);
    const row = el('div', 'dialog-actions');
    const cancel = el('button', 'text-btn', 'Cancel');
    cancel.type = 'button';
    cancel.addEventListener('click', closeTop);
    const ok = el('button', 'text-btn strong', okLabel);
    ok.type = 'submit';
    row.appendChild(cancel);
    row.appendChild(ok);
    box.appendChild(row);
    box.addEventListener('submit', (e) => {
      e.preventDefault();
      const text = input.value.trim();
      closeTop();
      if (text) done(text);
    });
    overlay(box);
    input.focus();
    input.select();
  }

  // A yes/no question; done() runs only on the OK button.
  function confirm(title, text, okLabel, done) {
    const box = el('div', 'dialog');
    box.setAttribute('role', 'alertdialog');
    box.appendChild(el('p', 'dialog-title', title));
    if (text) box.appendChild(el('p', 'dialog-text', text));
    const row = el('div', 'dialog-actions');
    const cancel = el('button', 'text-btn', 'Cancel');
    cancel.addEventListener('click', closeTop);
    const ok = el('button', 'text-btn strong', okLabel);
    ok.addEventListener('click', () => { closeTop(); done(); });
    row.appendChild(cancel);
    row.appendChild(ok);
    box.appendChild(row);
    overlay(box);
  }

  // A message at the bottom, with an optional action such as Undo.
  let snackTimer = null;
  function snack(text, action, ms) {
    const s = $('snack');
    s.textContent = '';
    s.appendChild(el('span', null, text));
    if (action) {
      const b = el('button', 'snack-btn', action.label);
      b.addEventListener('click', () => { hideSnack(); action.run(); });
      s.appendChild(b);
    }
    s.hidden = false;
    clearTimeout(snackTimer);
    snackTimer = setTimeout(hideSnack, ms || 3000);
  }
  function hideSnack() { clearTimeout(snackTimer); $('snack').hidden = true; }

  // Press and hold: onHold() runs after HOLD_MS without the finger moving (a scroll cancels it).
  // The tap that ends a hold is swallowed, so it doesn't also act as a click on what's now there.
  const HOLD_MS = 500;
  const SLOP_PX = 10;
  let swallow = false;
  // A new touch is a new tap: the hold's release may never have made a click to swallow.
  document.addEventListener('pointerdown', () => { swallow = false; }, true);
  document.addEventListener('click', (e) => {
    if (!swallow) return;
    swallow = false;
    e.stopPropagation();
    e.preventDefault();
  }, true);

  function onHold(node, run) {
    let timer = null;
    let x = 0;
    let y = 0;
    const cancel = () => { clearTimeout(timer); timer = null; };
    node.addEventListener('pointerdown', (e) => {
      x = e.clientX;
      y = e.clientY;
      cancel();
      timer = setTimeout(() => {
        timer = null;
        swallow = true;
        run();
      }, HOLD_MS);
    });
    node.addEventListener('pointermove', (e) => { if (Math.hypot(e.clientX - x, e.clientY - y) > SLOP_PX) cancel(); });
    node.addEventListener('pointerup', cancel);
    node.addEventListener('pointercancel', cancel);
    node.addEventListener('contextmenu', (e) => e.preventDefault());
  }

  R.ui = { $, el, icon, overlay, sheet, prompt, confirm, snack, hideSnack, closeTop, onHold };
})(window.R);
