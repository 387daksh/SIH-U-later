"""Download AI4Bharat Indic-TTS v1 checkpoints from GitHub releases and export to ONNX.

Source: https://github.com/AI4Bharat/Indic-TTS/releases/tag/v1-checkpoints-release
License: MIT (repo root LICENSE.txt, Copyright (c) 2022 AI4Bharat) - same release used for en+hi

Usage:
    # Export all languages (requires ~20GB disk, torch, TTS package):
    python scripts/download_indic_tts.py

    # Quick dry-run: only write meta.json files (no download):
    python scripts/download_indic_tts.py --dry-run

    # Export only Kannada:
    python scripts/download_indic_tts.py --langs kn
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# GitHub release base URL for AI4Bharat Indic-TTS v1 checkpoints
RELEASE_BASE = "https://github.com/AI4Bharat/Indic-TTS/releases/download/v1-checkpoints-release"

# All per-language configs.
# speaker_id: index in the shared speaker embedding of the HiFiGAN vocoder.
# The Indic-TTS v1 release has two groups: indo-aryan and dravidian.
# Within each group the vocoder is shared; speaker_id selects the right embedding row.
LANG_CONFIGS = {
    "en-hi": {"zip": "en.zip",   "subdir": "en",  "group": "indo_aryan", "speaker_id": 0, "length_scale_default": 0.9},
    "gu":    {"zip": "gu.zip",   "subdir": "gu",  "group": "indo_aryan", "speaker_id": 1, "length_scale_default": 0.9},
    "mr":    {"zip": "mr.zip",   "subdir": "mr",  "group": "indo_aryan", "speaker_id": 2, "length_scale_default": 0.9},
    "bn":    {"zip": "bn.zip",   "subdir": "bn",  "group": "indo_aryan", "speaker_id": 3, "length_scale_default": 0.9},
    "or":    {"zip": "or.zip",   "subdir": "or",  "group": "indo_aryan", "speaker_id": 4, "length_scale_default": 0.9},
    "kn":    {"zip": "kn.zip",   "subdir": "kn",  "group": "dravidian",  "speaker_id": 0, "length_scale_default": 0.95},
    "ml":    {"zip": "ml.zip",   "subdir": "ml",  "group": "dravidian",  "speaker_id": 1, "length_scale_default": 0.95},
    "ta":    {"zip": "ta.zip",   "subdir": "ta",  "group": "dravidian",  "speaker_id": 2, "length_scale_default": 0.95},
    "te":    {"zip": "te.zip",   "subdir": "te",  "group": "dravidian",  "speaker_id": 3, "length_scale_default": 0.95},
}

# Shared HiFiGAN vocoder — one per group (indo-aryan and dravidian have the same architecture).
# In v1-checkpoints-release the vocoder is inside each language zip but is identical across group.
# We download from the first language in each group and reuse.
GROUP_VOCODER_LANG = {
    "indo_aryan": "en-hi",
    "dravidian": "kn",
}

SAMPLE_RATE = 22050
CHUNK_GAP_MS = 60
MAX_CHUNK_CHARS = 70

VOCAB_INDO_ARYAN = list(dict.fromkeys(
    " !,.:;?।" +
    "aabcdeefghijklmnoopqrstuvwxyz" +
    "अआइईउऊएऐओऔकखगघङचछजझञटठडढणतथदधनपफबभमयरलवशषसहृं:ँ़ा ि ी ु ू े ैो ौ ः".replace(" ", "") +
    "অআইঈউঊএঐওঔকখগঘঙচছজঝঞটঠডঢণতথদধনপফবভমযরলশষসহ" +
    "ািীুূেৈোৌংঃ"
))

VOCAB_DRAVIDIAN = list(dict.fromkeys(
    " !,.:;?।" +
    "ಅಆಇಈಉಊಎಏಐಒಓಔಕಖಗಘಙಚಛಜಝಞಟಠಡಢಣತಥದಧನಪಫಬಭಮಯರಲವಶಷಸಹಳ" +
    "ಾಿೀುೂೆೇೈೊೋೌಂಃ" +
    "അആഇഈഉഊഎഏഐഒഓഔകഖഗഘങചഛജഝഞടഠഡഢണതഥദധനപഫബഭമയരലവശഷസഹളഴറ" +
    "ാിീുൂെേൈൊോൌംഃ" +
    "அஆஇஈඋஊඑஏஐஒஓஔகங்சஞட்ணதந்னபமயரல்வழளஷசஹஜ" +
    "ாிீுூெேைொோௌஂ" +
    "అఆఇఈఉఊఎఏఐఒఓఔకఖగఘఙచఛజఝఞటఠడఢణతథదధనపఫబభమయరలవశషసహళ" +
    "ాిీుూెేైొోౌంఃఁ"
))

VOCAB_ODIA = list(dict.fromkeys(
    " !,.:;?।" +
    "ଅଆଇଈଉଊଏଐଓଔକଖଗଘଙଚଛଜଝଞଟଠଡଢଣତଥଦଧନପଫବଭମଯରଲଳଵଶଷସହ" +
    "ାିୀୁୂେୈୋୌଂଃ"
))

LANG_VOCAB = {
    "en-hi": VOCAB_INDO_ARYAN, "gu": VOCAB_INDO_ARYAN, "mr": VOCAB_INDO_ARYAN,
    "bn": VOCAB_INDO_ARYAN, "or": VOCAB_ODIA,
    "kn": VOCAB_DRAVIDIAN, "ml": VOCAB_DRAVIDIAN, "ta": VOCAB_DRAVIDIAN, "te": VOCAB_DRAVIDIAN,
}


def download_with_progress(url: str, dest: Path):
    """Download url to dest, showing a simple progress indicator."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"  Downloading {url.split('/')[-1]}...", end="", flush=True)
    def reporthook(count, block, total):
        if total > 0:
            pct = min(100, int(count * block * 100 / total))
            print(f"\r  Downloading {url.split('/')[-1]}... {pct}%", end="", flush=True)
    urllib.request.urlretrieve(url, str(dest), reporthook=reporthook)
    print(f"\r  Downloaded  {dest.name} ({dest.stat().st_size/1024/1024:.1f} MB)   ")


def install_deps():
    packages = ["TTS", "torch", "onnx"]
    for pkg in packages:
        try:
            __import__(pkg.lower().replace("-", "_"))
        except ImportError:
            import subprocess
            print(f"Installing {pkg}...")
            subprocess.run([sys.executable, "-m", "pip", "install", pkg], check=True)


def export_hifigan_onnx(vocoder_path: Path, output_path: Path):
    """Export HiFi-GAN from .pth checkpoint to ONNX."""
    import torch
    from TTS.vocoder.models.hifigan_generator import HifiganGenerator
    from TTS.vocoder.configs.hifigan_config import HifiGANConfig

    ckpt = torch.load(str(vocoder_path), map_location="cpu", weights_only=False)
    cfg = HifiGANConfig()
    cfg.from_dict(ckpt.get("config", {}))
    model = HifiganGenerator(
        in_channels=cfg.audio.num_mels, out_channels=1,
        resblock_type=cfg.model_params.resblock_type,
        resblock_dilation_sizes=cfg.model_params.resblock_dilation_sizes,
        resblock_kernel_sizes=cfg.model_params.resblock_kernel_sizes,
        upsample_kernel_sizes=cfg.model_params.upsample_kernel_sizes,
        upsample_initial_channel=cfg.model_params.upsample_initial_channel,
        upsample_factors=cfg.model_params.upsample_rates,
        inference_padding=0,
    )
    model.load_state_dict(ckpt["model"])
    model.eval()
    model.remove_weight_norm()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with torch.no_grad():
        torch.onnx.export(
            model, torch.zeros(1, cfg.audio.num_mels, 200), str(output_path),
            input_names=["mel"], output_names=["audio"],
            dynamic_axes={"mel": {0: "batch", 2: "mel_len"}, "audio": {0: "batch", 2: "audio_len"}},
            opset_version=17, do_constant_folding=True,
        )
    print(f"  HiFi-GAN ONNX: {output_path.stat().st_size/1024/1024:.1f} MB")


def export_fastpitch_onnx(checkpoint_path: Path, config_path: Path, output_path: Path, speaker_id: int):
    """Export FastPitch TTS model to ONNX."""
    import torch
    from TTS.tts.models.forward_tts import ForwardTTS
    from TTS.tts.configs.fast_pitch_config import FastPitchConfig

    model_cfg = FastPitchConfig()
    model_cfg.load_json(str(config_path))
    model = ForwardTTS(model_cfg)
    ckpt = torch.load(str(checkpoint_path), map_location="cpu", weights_only=False)
    model.load_state_dict(ckpt["model"], strict=False)
    model.eval()

    class Wrapper(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m
        def forward(self, tokens, speaker_id, length_scale):
            return self.m.inference(
                tokens,
                aux_input={"x_lengths": torch.tensor([tokens.shape[1]]),
                           "speaker_ids": speaker_id, "d_vectors": None},
            )["model_outputs"]

    wrapper = Wrapper(model)
    wrapper.eval()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    dummy = (torch.zeros(1, 50, dtype=torch.long),
             torch.tensor([speaker_id], dtype=torch.long),
             torch.tensor([1.0]))
    with torch.no_grad():
        torch.onnx.export(
            wrapper, dummy, str(output_path),
            input_names=["tokens", "speaker_id", "length_scale"],
            output_names=["mel"],
            dynamic_axes={"tokens": {0: "batch", 1: "text_len"}, "mel": {0: "batch", 2: "mel_len"}},
            opset_version=17, do_constant_folding=True,
        )
    print(f"  FastPitch ONNX: {output_path.stat().st_size/1024/1024:.1f} MB")


def write_meta(output_dir: Path, lang: str, speaker_id: int, length_scale: float):
    output_dir.mkdir(parents=True, exist_ok=True)
    vocab = LANG_VOCAB.get(lang, VOCAB_INDO_ARYAN)
    meta = {
        "language": lang, "speaker_id": speaker_id, "sample_rate": SAMPLE_RATE,
        "length_scale_default": length_scale, "chunk_gap_ms": CHUNK_GAP_MS,
        "max_chunk_chars": MAX_CHUNK_CHARS, "vocab": vocab,
    }
    (output_dir / "meta.json").write_text(json.dumps(meta, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"  meta.json: {len(vocab)} vocab tokens")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--output", type=Path, default=ROOT / "models/tts")
    p.add_argument("--cache", type=Path, default=ROOT / "models/.zip_cache",
                   help="Directory to cache downloaded zip files")
    p.add_argument("--langs", nargs="+", default=list(LANG_CONFIGS.keys()))
    p.add_argument("--skip-hifigan", action="store_true")
    p.add_argument("--dry-run", action="store_true",
                   help="Only write meta.json files, do not download or export models")
    args = p.parse_args()

    if not args.dry_run:
        install_deps()

    args.output.mkdir(parents=True, exist_ok=True)
    args.cache.mkdir(parents=True, exist_ok=True)

    # Track which vocoders we've already exported (one per group)
    exported_vocoders = set()

    for lang in args.langs:
        cfg = LANG_CONFIGS[lang]
        lang_dir = args.output / lang
        fp_out = lang_dir / "fastpitch.onnx"
        group = cfg["group"]
        hifigan_out = args.output / "shared/hifigan.onnx"

        print(f"\n--- {lang} ({group}) ---")

        if args.dry_run:
            print(f"[DRY RUN] {RELEASE_BASE}/{cfg['zip']} -> {fp_out}")
            write_meta(lang_dir, lang, cfg["speaker_id"], cfg["length_scale_default"])
            continue

        # Download + unzip if not already
        zip_cache = args.cache / cfg["zip"]
        extract_dir = args.cache / cfg["subdir"]

        if not extract_dir.is_dir():
            if not zip_cache.is_file():
                download_with_progress(f"{RELEASE_BASE}/{cfg['zip']}", zip_cache)
            print(f"  Extracting {zip_cache.name}...")
            with zipfile.ZipFile(str(zip_cache), "r") as zf:
                zf.extractall(str(args.cache))

        # Find checkpoint and config inside extracted dir
        # v1-checkpoints-release structure: {lang}/fastpitch/ and {lang}/hifigan/
        fp_dir = extract_dir / "fastpitch"
        hg_dir = extract_dir / "hifigan"
        ckpt_files = sorted(fp_dir.glob("*.pth")) if fp_dir.is_dir() else []
        cfg_files  = sorted(fp_dir.glob("*.json")) if fp_dir.is_dir() else []
        hg_files   = sorted(hg_dir.glob("*.pth")) if hg_dir.is_dir() else []

        if not ckpt_files:
            # Try alternate flat structure
            ckpt_files = sorted(extract_dir.rglob("*fastpitch*.pth"))
            cfg_files  = sorted(extract_dir.rglob("*fastpitch*config*.json"))
            hg_files   = sorted(extract_dir.rglob("*hifigan*.pth")) or sorted(extract_dir.rglob("*vocoder*.pth"))

        if not ckpt_files:
            print(f"  ERROR: No FastPitch checkpoint found in {extract_dir}. Inspect manually.")
            continue

        ckpt_path = ckpt_files[0]
        config_path = cfg_files[0] if cfg_files else None
        hg_path = hg_files[0] if hg_files else None

        print(f"  Checkpoint: {ckpt_path.name}")
        if config_path:
            print(f"  Config: {config_path.name}")
        if hg_path:
            print(f"  Vocoder: {hg_path.name}")

        # Export shared HiFiGAN (once per group or overall shared)
        if not args.skip_hifigan and hg_path and not hifigan_out.is_file() and group not in exported_vocoders:
            print("  Exporting shared HiFi-GAN...")
            try:
                export_hifigan_onnx(hg_path, hifigan_out)
                exported_vocoders.add(group)
            except Exception as e:
                print(f"  HiFi-GAN export failed: {e}")
                import traceback; traceback.print_exc()

        # Export FastPitch
        if fp_out.is_file():
            print(f"  FastPitch already exported.")
        elif config_path:
            try:
                export_fastpitch_onnx(ckpt_path, config_path, fp_out, cfg["speaker_id"])
            except Exception as e:
                print(f"  FastPitch export failed: {e}")
                import traceback; traceback.print_exc()
        else:
            print(f"  ERROR: Config JSON not found — cannot export FastPitch without architecture config.")

        write_meta(lang_dir, lang, cfg["speaker_id"], cfg["length_scale_default"])

    print(f"\nDone. Models in: {args.output}")
    print(f"Next: python scripts/stage_android_models.py --tts-dir {args.output}")


if __name__ == "__main__":
    main()
