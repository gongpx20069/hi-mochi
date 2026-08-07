# Native Android Wake Word

## 1. Target

The wake-word pipeline runs entirely in native Android:

```text
Foreground service
  -> AudioRecord worker
  -> sherpa-onnx KeywordSpotter
  -> VoiceSessionController
  -> selected speech-to-text path
       -> Android SpeechRecognizer
       -> temporary PCM capture -> iFlytek / Azure Speech
```

The native app now implements this pipeline with the pinned sherpa-onnx AAR,
int8 KWS model files, Silero VAD, `WakeCaptureService`, and typed wake state.
The build downloads the AAR from the upstream release and verifies its pinned
SHA-256 before use. The configured local keyword is `HI MOCHI`.

## 2. Dependency

Gradle downloads the pinned sherpa-onnx Android AAR into:

```text
android/app/libs/sherpa-onnx-1.13.2.aar
```

The generated file is ignored by Git. Do not introduce a second ONNX runtime.
Dependency upgrades require updating the release asset ID and SHA-256, release
notes, ABI/package inspection, and real-device regression.

## 3. Responsibilities

| Component | Responsibility |
| --- | --- |
| `WakeForegroundService` | foreground lifecycle and notification |
| `WakeAudioController` | AudioRecord ownership and worker lifecycle |
| `KeywordSpotterEngine` | model loading, stream processing, debounce |
| `VoiceSessionController` | wake acceptance, cancellation, STT transition |
| `AudioFocusCoordinator` | exclusive ownership across wake, STT, and TTS |

`WakeCaptureService` also owns the active MediaSession and persistent
notification. Notification actions and headset media keys enter the same voice
path. A background keyword detection posts a high-importance lock-screen
notification that the user taps to begin STT; no silent keep-alive playback or
restricted background Activity launch is used.

The wake service emits a typed wake event only. UI and agent work remain
outside the audio thread.

## 4. Runtime constraints

- 16 kHz mono PCM16 input.
- Background thread; never block the main thread.
- Bounded chunk cadence and two-second duplicate suppression.
- Stop wake capture before STT obtains the microphone.
- Android SpeechRecognizer remains the default and requires no cloud setup.
- Settings may optionally select iFlytek or Azure Speech for STT only; Android
  TextToSpeech remains the TTS implementation.
- Cloud STT captures each utterance once as temporary 16 kHz mono PCM16 and
  uses the local sherpa-onnx Silero VAD to detect its endpoint before upload.
  It retries that same audio up to three times for transient failures.
- iFlytek streams each 40 ms PCM frame while recording and uses its server
  `vad_eos` result as the primary endpoint. Local Silero VAD remains a bounded
  fallback, and the temporary PCM is replayed only after a transient failure.
- An iFlytek listening window with no detected speech closes after three
  seconds; provider `vad_eos` applies only after speech has started.
- Delete temporary speech audio after success, cancellation, or final failure;
  never add it to memory or backup.
- Pause wake only while full-sentence STT owns the microphone, then resume it
  during Agent work, tool execution, summarization, and TTS.
- A wake detection during those phases cancels the active interaction, stops
  TTS, and enters a fresh STT session; completed tool side effects remain.
- Enable platform acoustic echo cancellation on wake AudioRecord when
  available to reduce self-triggering from Mochi's speaker output.
- Release AudioRecord, streams, and native objects on every stop path.
- Native initialization errors are visible; no silent slow fallback.

## 5. Observability

Debug logs may include lifecycle, selected STT path, chunk count, latency,
retry attempt, and error codes. They must not include credentials, captured
audio, or recognized conversation text. Temporary recorded utterances remain
in app-private cache only and are not retained as conversation memory.

Track:

- service start/stop;
- model load duration;
- chunks per interval;
- wake detections and debounce drops;
- microphone ownership transitions;
- initialization and native linkage failures.

## 6. Verification

Automated tests cover lifecycle reducers and stale-event rejection. Real-device
tests must cover:

- 10-30 minute foreground idle responsiveness;
- repeated wake/STT/TTS cycles;
- "Hi Mochi" interruption during Agent work and TTS without speaker
  self-trigger loops;
- interruption by calls and other audio apps;
- screen rotation and process recreation;
- microphone denial/revocation;
- Bluetooth media-button trigger;
- supported Android API levels and release ABIs.
