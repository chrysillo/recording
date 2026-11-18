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
  function sheet(title, items, footer) {
    const box = el('div', 'sheet');
    if (title) box.appendChild(el('p', 'sheet-title', title));
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

  R.ui = { $, el, icon, overlay, sheet, prompt, confirm, snack, hideSnack, closeTop };
})(window.R);
