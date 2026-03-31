#!/usr/bin/env python3
"""
Prepare manga-ocr model for on-device ONNX Runtime inference.

Downloads kha-white/manga-ocr-base from HuggingFace, exports encoder and
decoder to ONNX, quantizes to int8, and packages everything into a zip.

Usage:
    pip install transformers torch onnx onnxruntime pillow
    python scripts/prepare_manga_ocr_model.py

Output: output/manga-ocr-base-int8.zip
"""

import json
import os
import shutil
import zipfile

import numpy as np
import torch
from transformers import (
    VisionEncoderDecoderModel,
    AutoTokenizer,
    ViTImageProcessor,
)

MODEL_NAME = "kha-white/manga-ocr-base"
OUTPUT_DIR = "output/manga-ocr-base"
IMG_SIZE = 384  # DeiT input size


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    print(f"Loading model: {MODEL_NAME}")
    model = VisionEncoderDecoderModel.from_pretrained(MODEL_NAME)
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
    processor = ViTImageProcessor.from_pretrained(MODEL_NAME)
    model.eval()

    # ── Export encoder ──
    print("Exporting encoder...")
    dummy_pixel = torch.randn(1, 3, IMG_SIZE, IMG_SIZE)

    class EncoderWrapper(torch.nn.Module):
        def __init__(self, encoder):
            super().__init__()
            self.encoder = encoder

        def forward(self, pixel_values):
            return self.encoder(pixel_values=pixel_values).last_hidden_state

    encoder_wrapper = EncoderWrapper(model.encoder)
    encoder_path = os.path.join(OUTPUT_DIR, "encoder_model.onnx")
    torch.onnx.export(
        encoder_wrapper,
        (dummy_pixel,),
        encoder_path,
        input_names=["pixel_values"],
        output_names=["encoder_hidden_states"],
        dynamic_axes={
            "pixel_values": {0: "batch"},
            "encoder_hidden_states": {0: "batch"},
        },
        opset_version=14,
    )

    # ── Export decoder ──
    print("Exporting decoder...")
    # Get encoder output shape for dummy
    with torch.no_grad():
        enc_out = encoder_wrapper(dummy_pixel)
    seq_len_enc = enc_out.shape[1]
    hidden_size = enc_out.shape[2]

    dummy_input_ids = torch.tensor([[tokenizer.bos_token_id]], dtype=torch.long)
    dummy_hidden = torch.randn(1, seq_len_enc, hidden_size)

    class DecoderWrapper(torch.nn.Module):
        def __init__(self, decoder, lm_head):
            super().__init__()
            self.decoder = decoder
            self.lm_head = lm_head

        def forward(self, input_ids, encoder_hidden_states):
            dec_out = self.decoder(
                input_ids=input_ids,
                encoder_hidden_states=encoder_hidden_states,
            )
            return self.lm_head(dec_out.last_hidden_state)

    # The lm_head may be part of the decoder or a separate linear layer
    if hasattr(model, "lm_head"):
        lm_head = model.lm_head
    elif hasattr(model.decoder, "lm_head"):
        lm_head = model.decoder.lm_head
    else:
        # For VisionEncoderDecoderModel, the output projection is usually
        # model.decoder.cls or we can use model.generate's internal lm_head
        lm_head = model.decoder.cls.predictions.decoder if hasattr(model.decoder, "cls") else model.lm_head

    decoder_wrapper = DecoderWrapper(model.decoder.model if hasattr(model.decoder, "model") else model.decoder, lm_head)

    # Try a simpler approach: use the full model's generate internals
    # Actually, let's use a cleaner wrapper
    class FullDecoderWrapper(torch.nn.Module):
        def __init__(self, model):
            super().__init__()
            self.model = model

        def forward(self, input_ids, encoder_hidden_states):
            # VisionEncoderDecoderModel stores decoder as model.decoder
            dec = self.model.decoder
            outputs = dec(
                input_ids=input_ids,
                encoder_hidden_states=encoder_hidden_states,
            )
            logits = outputs.logits
            return logits

    full_decoder = FullDecoderWrapper(model)
    decoder_path = os.path.join(OUTPUT_DIR, "decoder_model.onnx")
    torch.onnx.export(
        full_decoder,
        (dummy_input_ids, dummy_hidden),
        decoder_path,
        input_names=["input_ids", "encoder_hidden_states"],
        output_names=["logits"],
        dynamic_axes={
            "input_ids": {0: "batch", 1: "seq_len"},
            "encoder_hidden_states": {0: "batch"},
            "logits": {0: "batch", 1: "seq_len"},
        },
        opset_version=14,
    )

    # ── Export vocabulary ──
    print("Exporting vocabulary...")
    vocab = tokenizer.get_vocab()
    # Sort by id
    id_to_token = {v: k for k, v in vocab.items()}
    vocab_list = []
    for i in range(len(id_to_token)):
        token = id_to_token.get(i, f"<unk_{i}>")
        vocab_list.append(token)

    vocab_path = os.path.join(OUTPUT_DIR, "vocab.json")
    with open(vocab_path, "w", encoding="utf-8") as f:
        json.dump(vocab_list, f, ensure_ascii=False)

    # ── Export config ──
    print("Exporting config...")
    config = {
        "encoder_file": "encoder_model_quantized.onnx",
        "decoder_file": "decoder_model_quantized.onnx",
        "vocab_file": "vocab.json",
        "image_size": IMG_SIZE,
        "image_mean": processor.image_mean,
        "image_std": processor.image_std,
        "bos_token_id": tokenizer.bos_token_id,
        "eos_token_id": tokenizer.eos_token_id,
        "pad_token_id": tokenizer.pad_token_id if tokenizer.pad_token_id is not None else 0,
        "vocab_size": len(vocab),
        "max_length": 300,
    }
    config_path = os.path.join(OUTPUT_DIR, "config.json")
    with open(config_path, "w") as f:
        json.dump(config, f, indent=2)

    # ── Quantize to int8 ──
    print("Quantizing to int8...")
    from onnxruntime.quantization import quantize_dynamic, QuantType

    encoder_q_path = os.path.join(OUTPUT_DIR, "encoder_model_quantized.onnx")
    decoder_q_path = os.path.join(OUTPUT_DIR, "decoder_model_quantized.onnx")

    quantize_dynamic(encoder_path, encoder_q_path, weight_type=QuantType.QInt8)
    quantize_dynamic(decoder_path, decoder_q_path, weight_type=QuantType.QInt8)

    # Remove unquantized
    os.remove(encoder_path)
    os.remove(decoder_path)

    # ── Package as zip ──
    print("Packaging zip...")
    zip_path = "output/manga-ocr-base-int8.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for fname in [
            "encoder_model_quantized.onnx",
            "decoder_model_quantized.onnx",
            "vocab.json",
            "config.json",
        ]:
            fpath = os.path.join(OUTPUT_DIR, fname)
            zf.write(fpath, fname)

    file_size = os.path.getsize(zip_path) / 1024 / 1024
    print(f"\nDone! Output: {zip_path} ({file_size:.1f} MB)")
    print("Upload this zip to GitHub Releases, then update MangaOcrModel.kt downloadUrl.")


if __name__ == "__main__":
    main()
