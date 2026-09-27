# Indic TTS INT8 phone analysis

The supplied `indic_tts_int8_bundle.zip` is compatible with the existing Android
`IndicOnnxTts` loader. It contains:

- 9 FastPitch ONNX acoustic models: `en-hi`, `bn`, `gu`, `kn`, `ml`, `mr`, `or`, `ta`, `te`.
- 1 shared HiFi-GAN ONNX vocoder.
- One `meta.json` vocabulary/configuration file per language.
- 22,050 Hz audio output and a default speaker id of `0` for every language.

The uncompressed ONNX payload is about 591.4 MiB. Each FastPitch model is about
62.7 MB and the shared vocoder is about 55.8 MB. Installing one language plus
the vocoder therefore uses about 119 MB of model storage; installing all nine
uses about 591 MB before the rest of the speech models.

Compared with the repository's current roughly 171 MB per-language TTS model,
the new FastPitch file is about 63% smaller. If the 171 MB figure includes a
complete per-language package, the new language-plus-shared-vocoder layout is
about 31% smaller for one installed language. If 171 MB is only the acoustic
model, the comparison is 63% for that model and the vocoder must be added
separately.

The FastPitch graphs contain `ConvInteger`, `MatMulInteger`, and dynamic
quantization operators. HiFi-GAN contains regular convolution operators and
should be treated as float-heavy. `INT8` does not mean that the complete TTS
pipeline is 8-bit or that its runtime RAM equals its ZIP size.

## Low-memory deployment

Keep the models outside the APK and install only the language(s) required by
the phone. The Kotlin loader already keeps one FastPitch `OrtSession` resident
and closes the previous language session when a new language is selected. The
shared vocoder remains resident because every synthesis call needs it.

From the repository root, stage one language from the supplied ZIP:

```powershell
.\tools\stage_tts_int8_bundle.ps1 `
  -ZipPath "C:\Users\Sarthak\Downloads\indic_tts_int8_bundle.zip" `
  -Languages en-hi
```

Add more languages only when needed, for example `-Languages en-hi,hi` is not
valid because Hindi is represented by the `en-hi` voice. Valid bundle codes are
`en-hi`, `bn`, `gu`, `kn`, `ml`, `mr`, `or`, `ta`, and `te`.

The app expects the resulting layout under its model root:

```text
tts/
  shared/hifigan.onnx
  en-hi/fastpitch.onnx
  en-hi/meta.json
```

For a phone that uses the existing external model directory, copy the staged
`tts` directory to the app's model location and restart the app. Do not embed
the complete ZIP in the APK. Measure peak RSS on the target phone while
synthesizing a 70-character chunk; the model file sizes alone are not a RAM
measurement because ONNX Runtime also allocates graph, activation, audio, and
thread buffers.

The ZIP SHA-256 observed during analysis was:

```text
DEE8FE4B97D22B765EEC3FEFB94AF6652A246BB32150AEF72CD99D43142721DD
```
