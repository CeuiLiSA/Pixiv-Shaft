#!/usr/bin/env python3
"""
Prepare opus-mt-ja-zh translation model for Android deployment.

Steps:
  1. Load model from local directory (download first with curl from hf-mirror.com)
  2. Export encoder + decoder to ONNX
  3. Quantize both to int8 (dynamic)
  4. Extract SentencePiece vocabulary to JSON
  5. Write model_config.json
  6. Package everything into a ZIP for GitHub Release

Prerequisites:
    pip install optimum[onnxruntime] transformers sentencepiece onnxruntime onnx torch

    # Download model files first:
    mkdir -p output/model_local && cd output/model_local
    for f in config.json pytorch_model.bin source.spm target.spm tokenizer_config.json vocab.json generation_config.json; do
      curl -sL -o "$f" "https://hf-mirror.com/shun89/opus-mt-ja-zh/resolve/main/$f"
    done

Output:
    output/opus-mt-ja-zh-int8.zip   (~80MB, upload to GitHub Releases)
"""

import json
import os
import shutil
import zipfile

import sentencepiece as spm
from onnxruntime.quantization import quantize_dynamic, QuantType

MODEL_LOCAL = os.path.join("output", "model_local")
OUTPUT_DIR = "output"
ONNX_DIR = os.path.join(OUTPUT_DIR, "onnx_raw")
QUANT_DIR = os.path.join(OUTPUT_DIR, "onnx_quantized")
FINAL_DIR = os.path.join(OUTPUT_DIR, "opus-mt-ja-zh")
ZIP_PATH = os.path.join(OUTPUT_DIR, "opus-mt-ja-zh-int8.zip")


def step1_export_onnx():
    """Export MarianMT model to ONNX using Optimum from local files."""
    print("=" * 60)
    print("Step 1: Exporting model to ONNX...")
    print("=" * 60)

    from optimum.onnxruntime import ORTModelForSeq2SeqLM
    model = ORTModelForSeq2SeqLM.from_pretrained(MODEL_LOCAL, export=True)
    model.save_pretrained(ONNX_DIR)
    print(f"  ONNX model saved to {ONNX_DIR}")

    for f in sorted(os.listdir(ONNX_DIR)):
        filepath = os.path.join(ONNX_DIR, f)
        if os.path.isfile(filepath):
            size_mb = os.path.getsize(filepath) / 1_048_576
            print(f"  {f}: {size_mb:.1f} MB")


def step2_quantize():
    """Quantize encoder and decoder to int8 (dynamic quantization)."""
    print("\n" + "=" * 60)
    print("Step 2: Quantizing to int8...")
    print("=" * 60)

    os.makedirs(QUANT_DIR, exist_ok=True)

    for model_file in ["encoder_model.onnx", "decoder_model.onnx"]:
        src = os.path.join(ONNX_DIR, model_file)
        if not os.path.exists(src):
            print(f"  WARNING: {model_file} not found, skipping")
            continue

        dst = os.path.join(QUANT_DIR, model_file.replace(".onnx", "_quantized.onnx"))
        print(f"  Quantizing {model_file}...")
        quantize_dynamic(
            model_input=src,
            model_output=dst,
            weight_type=QuantType.QInt8,
        )
        orig_mb = os.path.getsize(src) / 1_048_576
        quant_mb = os.path.getsize(dst) / 1_048_576
        print(f"  {orig_mb:.1f} MB → {quant_mb:.1f} MB ({quant_mb/orig_mb*100:.0f}%)")


def step3_extract_vocab():
    """Extract SentencePiece vocabulary to JSON for pure-Kotlin tokenizer."""
    print("\n" + "=" * 60)
    print("Step 3: Extracting vocabulary...")
    print("=" * 60)

    for spm_name, json_name in [("source.spm", "source_vocab.json"),
                                 ("target.spm", "target_vocab.json")]:
        spm_path = os.path.join(MODEL_LOCAL, spm_name)
        if not os.path.exists(spm_path):
            raise FileNotFoundError(f"{spm_path} not found. Download model files first.")

        sp = spm.SentencePieceProcessor()
        sp.Load(spm_path)

        vocab = []
        for i in range(sp.GetPieceSize()):
            vocab.append({
                "piece": sp.IdToPiece(i),
                "score": float(sp.GetScore(i)),
            })

        out_path = os.path.join(FINAL_DIR, json_name)
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(vocab, f, ensure_ascii=False)

        print(f"  {json_name}: {len(vocab)} pieces")


def step4_write_config():
    """Write model_config.json with all settings needed by Android."""
    print("\n" + "=" * 60)
    print("Step 4: Writing model config...")
    print("=" * 60)

    config_path = os.path.join(MODEL_LOCAL, "config.json")
    with open(config_path) as f:
        config = json.load(f)

    model_config = {
        "model_name": "opus-mt-ja-zh",
        "source_lang": "ja",
        "target_lang": "zh",
        "encoder_file": "encoder_model_quantized.onnx",
        "decoder_file": "decoder_model_quantized.onnx",
        "source_vocab_file": "source_vocab.json",
        "target_vocab_file": "target_vocab.json",
        "vocab_size": config.get("vocab_size", 65001),
        "decoder_start_token_id": config.get("decoder_start_token_id", 65000),
        "eos_token_id": config.get("eos_token_id", 0),
        "pad_token_id": config.get("pad_token_id", 65000),
        "max_length": config.get("max_length", 512),
        "num_beams": 1,
    }

    out_path = os.path.join(FINAL_DIR, "model_config.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(model_config, f, indent=2)

    print(f"  Config: {json.dumps(model_config, indent=2)}")


def step5_package():
    """Copy quantized models and package everything into ZIP."""
    print("\n" + "=" * 60)
    print("Step 5: Packaging ZIP...")
    print("=" * 60)

    for f in os.listdir(QUANT_DIR):
        if f.endswith(".onnx"):
            shutil.copy2(os.path.join(QUANT_DIR, f), os.path.join(FINAL_DIR, f))

    with zipfile.ZipFile(ZIP_PATH, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in sorted(os.listdir(FINAL_DIR)):
            filepath = os.path.join(FINAL_DIR, f)
            zf.write(filepath, f)
            size_mb = os.path.getsize(filepath) / 1_048_576
            print(f"  Added: {f} ({size_mb:.1f} MB)")

    zip_mb = os.path.getsize(ZIP_PATH) / 1_048_576
    print(f"\n  Final ZIP: {ZIP_PATH} ({zip_mb:.1f} MB)")
    print(f"  Upload this to GitHub Releases!")


def main():
    os.makedirs(FINAL_DIR, exist_ok=True)

    step1_export_onnx()
    step2_quantize()
    step3_extract_vocab()
    step4_write_config()
    step5_package()

    print("\n" + "=" * 60)
    print("Done! Files in", FINAL_DIR)
    print("ZIP ready:", ZIP_PATH)
    print("=" * 60)


if __name__ == "__main__":
    main()
