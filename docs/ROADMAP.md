# Roadmap

What the app does, the research behind its choices, and what comes next.

## Formats

What Android can record and encode without any library:

| Format | How | Size (mono, 48 kHz) | Verdict |
|---|---|---|---|
| M4A / AAC-LC | MediaCodec AAC + MediaMuxer MP4 | about 1 MB a minute at 128 kbps | **Default.** Plays everywhere; transparent for voice and most music |
| WAV, 16-bit PCM | Written directly | about 5.5 MB a minute | Lossless, for editing in a DAW; repairable after a crash |
| Opus in Ogg | MediaCodec Opus + MediaMuxer Ogg (Android 10+) | about 0.35 MB a minute at 48 kbps | Smallest; excellent for voice. Also used for voice-note shares |
| FLAC | MediaCodec has an encoder, but there's no FLAC muxer | about 2.5–3 MB a minute | Skipped: the raw stream lacks a total length, so many players show no duration |
| AMR-NB/WB, 3GP | MediaRecorder | tiny | Skipped: phone-call quality |

## WhatsApp voice notes

WhatsApp's own voice messages are mono Opus in an Ogg container. **Share › Voice note** converts the clip to that (48 kHz, 32 kbps), so the file is small and WhatsApp plays it right in the chat instead of as an attachment to open. Whether it's shown as a green-mic voice message, which plays the next one automatically, depends on WhatsApp; check it on the phone. Telegram and Signal also play Ogg Opus inline. On Android 8–9 there's no Opus encoder, so Share sends the original file.

## Microphones

Android lists every input (`AudioManager.getDevices`). The built-in mics may appear as one input or as several with an address (`bottom`, `back`, `top`); most phones list only one and choose the capsules themselves based on the audio source. Ranking, best first:

1. **USB** mic or audio interface: real preamps and capsules, often stereo, no phone processing.
2. **Wired headset** mic: close to the mouth, quiet room; thin on instruments.
3. **Main built-in (bottom)**: the phone's best mic, tuned for voice.
4. **Back / top built-in**: face the scene rather than you; used for stereo and noise cancelling.
5. **Bluetooth** (SCO or LE headset): call-quality link (8–16 kHz, compressed), and it switches the headphones into call mode. Last resort.

**Sound** (processing) matters as much as the mic on most phones. The app offers Natural and Voice; Room (`CAMCORDER`) was dropped because the Back mic covers that use:
- **Music**: the `UNPROCESSED` source where the phone reports it (`PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED`), otherwise `VOICE_RECOGNITION`, which Android requires to have no auto-gain or noise suppression. Natural dynamics, quieter.
- **Voice**: `MIC`, with whatever gain and noise processing the phone applies. Good for speech, bad for music (pumping, warbling).
- **Room**: `CAMCORDER`, tuned for video; usually uses the back and top mics and records stereo where the phone can.

## Next
- Try it on a phone: each format, screen off mid-take, a USB interface and a wired headset, a WhatsApp voice note.
- Input gain or a "too quiet / too loud" hint from the meter.
- Trim the start and end of a clip.
