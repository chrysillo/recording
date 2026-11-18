// Duration and size text for the clip list. Pure.
(function (R) {
  function pad(n) { return (n < 10 ? '0' : '') + n; }

  // 0:07, 12:34, 1:02:03
  function duration(ms) {
    const total = Math.max(0, Math.floor((Number(ms) || 0) / 1000));
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    return h > 0 ? h + ':' + pad(m) + ':' + pad(s) : m + ':' + pad(s);
  }

  // 512 B, 820 KB, 4.2 MB, 120 MB, 1.3 GB (1 KB = 1024 bytes, as Android's file manager counts)
  function size(bytes) {
    const b = Math.max(0, Number(bytes) || 0);
    if (b < 1024) return b + ' B';
    const kb = b / 1024;
    if (Math.round(kb) < 1024) return Math.round(kb) + ' KB';
    const mb = kb / 1024;
    if (Math.round(mb) < 1024) return (mb < 99.95 ? mb.toFixed(1) : Math.round(mb)) + ' MB';
    return (mb / 1024).toFixed(1) + ' GB';
  }

  R.format = { duration, size };
})(window.R = window.R || {});
