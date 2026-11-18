// Settings screen: format, quality, channels, theme. The segments bind themselves (app.js); this adds the notes.
(function (R) {
  const { $ } = R.ui;

  const FORMAT_NOTES = {
    m4a: 'AAC in an M4A file. Small (about 1 MB a minute) and plays everywhere. Right for almost everything.',
    wav: 'Uncompressed and lossless: 16-bit, 48 kHz. Large (about 5.5 MB a minute in mono). Best for editing in a DAW.',
    opus: 'Opus in an Ogg file. The smallest (about 0.35 MB a minute); excellent for voice, good for music on High. Some players and DAWs can’t open it.'
  };
  const QUALITY_NOTES = {
    m4a: { standard: '128 kbps: transparent for voice and most music.', high: '256 kbps: for music with a lot of detail. Twice the size.' },
    opus: { standard: '48 kbps: clear voice, decent music.', high: '96 kbps: for music. Twice the size.' }
  };

  const PROCESSING_NOTES = {
    music: 'No auto-gain or noise suppression: music and rehearsals keep their dynamics.',
    voice: 'The phone evens out the level and cuts background noise. For talking; it squashes music.'
  };

  function render() {
    const caps = R.app.caps;
    $('processing-note').textContent = PROCESSING_NOTES[R.store.get('processing')];
    const opusBtn = document.querySelector('.seg[data-setting="format"] [data-value="opus"]');
    opusBtn.disabled = !caps.opus;
    const f = R.store.get('format');
    if (f === 'opus' && !caps.opus) { R.store.set('format', 'm4a'); return; }   // re-renders through onChange
    $('format-note').textContent = FORMAT_NOTES[f] + (caps.opus ? '' : ' Opus recording needs Android 10 or later.');
    $('quality-row').hidden = f === 'wav';
    if (f !== 'wav') $('quality-note').textContent = QUALITY_NOTES[f][R.store.get('quality')];
    $('folder-note').textContent = 'Internal storage › ' + caps.folder.split('/').join(' › ') +
      '. Any file manager can open them, and they stay there if the app is uninstalled.';
  }

  R.app.register('settings', { enter: render });
  R.store.onChange(() => { if (document.getElementById('s-settings').classList.contains('on')) render(); });
})(window.R);
