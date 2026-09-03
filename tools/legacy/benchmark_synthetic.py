"""Legacy synthetic-audio ASR smoke benchmark; not real evaluation evidence."""

import asyncio
import time
import unicodedata
from pathlib import Path

import av
import edge_tts
from gtts import gTTS
import numpy as np
import soundfile as sf
import torch

from tools import check_indic_model as check_model

ARTIFACT_DIR = Path("artifacts/synthetic")

CASES = {
    "as": (None, "নমস্কাৰ, এইটো এটা পৰীক্ষা।"),
    "bn": ("bn-IN-TanishaaNeural", "নমস্কার, এটি একটি পরীক্ষা।"),
    "gu": ("gu-IN-DhwaniNeural", "નમસ્તે, આ એક પરીક્ષણ છે."),
    "hi": ("hi-IN-SwaraNeural", "नमस्ते, यह एक परीक्षण है।"),
    "kn": ("kn-IN-SapnaNeural", "ನಮಸ್ಕಾರ, ಇದು ಒಂದು ಪರೀಕ್ಷೆ."),
    "ml": ("ml-IN-SobhanaNeural", "നമസ്കാരം, ഇതൊരു പരീക്ഷണമാണ്."),
    "mr": ("mr-IN-AarohiNeural", "नमस्कार, ही एक चाचणी आहे."),
    "or": (None, "ନମସ୍କାର, ଏହା ଏକ ପରୀକ୍ଷା।"),
    "pa": ("gtts:pa", "ਸਤ ਸ੍ਰੀ ਅਕਾਲ, ਇਹ ਇੱਕ ਟੈਸਟ ਹੈ।"),
    "ta": ("ta-IN-PallaviNeural", "வணக்கம், இது ஒரு சோதனை."),
    "te": ("te-IN-ShrutiNeural", "నమస్కారం, ఇది ఒక పరీక్ష."),
    "ur": ("ur-IN-GulNeural", "السلام علیکم، یہ ایک آزمائش ہے۔"),
}


def words(text):
    text = unicodedata.normalize("NFC", text).lower()
    text = "".join(" " if unicodedata.category(char).startswith("P") else char for char in text)
    return text.split()


def wer(reference, hypothesis):
    ref, hyp = words(reference), words(hypothesis)
    row = list(range(len(hyp) + 1))
    for i, expected in enumerate(ref, 1):
        next_row = [i]
        for j, actual in enumerate(hyp, 1):
            next_row.append(
                min(
                    next_row[-1] + 1,
                    row[j] + 1,
                    row[j - 1] + (expected != actual),
                )
            )
        row = next_row
    return row[-1] / max(len(ref), 1)


async def make_wav(language, voice, text):
    ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)
    mp3 = ARTIFACT_DIR / f"_wer_{language}.mp3"
    wav = ARTIFACT_DIR / f"_wer_{language}.wav"
    if voice.startswith("gtts:"):
        gTTS(text=text, lang=voice.split(":", 1)[1]).save(str(mp3))
    else:
        await edge_tts.Communicate(text, voice).save(str(mp3))
    chunks = []
    resampler = av.audio.resampler.AudioResampler(format="flt", layout="mono", rate=16000)
    with av.open(mp3) as container:
        for frame in container.decode(audio=0):
            chunks.extend(x.to_ndarray().reshape(-1) for x in resampler.resample(frame))
    sf.write(wav, np.concatenate(chunks).astype("float32"), 16000)
    mp3.unlink()
    return wav


async def main():
    generated = {}
    for language, (voice, text) in CASES.items():
        if voice:
            generated[language] = await make_wav(language, voice, text)

    model = check_model.model_onnx.IndicASRModel(
        check_model.model_onnx.IndicASRConfig(ts_folder=str(check_model.ROOT), FRAME_DURATION_MS=0.08)
    )
    for language, (voice, reference) in CASES.items():
        if not voice:
            print(f"{language}: SKIPPED (no installed TTS voice)")
            continue
        audio, rate = sf.read(generated[language], dtype="float32")
        started = time.perf_counter()
        with torch.no_grad():
            hypothesis = model(torch.from_numpy(audio).unsqueeze(0), language, "ctc")
        elapsed = time.perf_counter() - started
        print(f"{language}: WER={wer(reference, hypothesis):.3f} latency={elapsed:.3f}s")
        print(f"  ref: {reference}")
        print(f"  hyp: {hypothesis}")


if __name__ == "__main__":
    asyncio.run(main())
