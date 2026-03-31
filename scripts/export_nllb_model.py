#!/usr/bin/env python3
"""Export NLLB-200-distilled-600M to ONNX, properly quantized for mobile."""
import json, os, zipfile
import torch
import onnx
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

MODEL = "facebook/nllb-200-distilled-600M"
OUT = "output/nllb-600m"
os.makedirs(OUT, exist_ok=True)

print("1/6  Loading model…")
model = AutoModelForSeq2SeqLM.from_pretrained(MODEL)
tokenizer = AutoTokenizer.from_pretrained(MODEL)
model.eval()

# ── Encoder ──
print("2/6  Exporting encoder…")
dummy_ids = torch.tensor([[1, 2, 3, 4, 5]], dtype=torch.long)
dummy_mask = torch.ones_like(dummy_ids)

class Enc(torch.nn.Module):
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, input_ids, attention_mask):
        return self.m.get_encoder()(input_ids=input_ids, attention_mask=attention_mask).last_hidden_state

enc_path = f"{OUT}/encoder_model.onnx"
with torch.no_grad():
    torch.onnx.export(Enc(model), (dummy_ids, dummy_mask),
        enc_path,
        input_names=["input_ids", "attention_mask"],
        output_names=["encoder_hidden_states"],
        dynamic_axes={"input_ids":{0:"b",1:"s"}, "attention_mask":{0:"b",1:"s"},
                      "encoder_hidden_states":{0:"b",1:"s"}},
        opset_version=14, dynamo=False)

# Merge external data into single file
print("   Merging encoder external data…")
enc_model = onnx.load(enc_path, load_external_data=True)
onnx.save_model(enc_model, enc_path, save_as_external_data=False,
                size_threshold=1024*1024*1024)  # 1GB threshold = everything internal
del enc_model

# ── Decoder ──
print("3/6  Exporting decoder…")
with torch.no_grad():
    enc_out = Enc(model)(dummy_ids, dummy_mask)

tgt_lang_id = tokenizer.convert_tokens_to_ids("zho_Hans")
dec_dummy_ids = torch.tensor([[tgt_lang_id]], dtype=torch.long)

class Dec(torch.nn.Module):
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, input_ids, encoder_hidden_states, encoder_attention_mask):
        out = self.m(decoder_input_ids=input_ids,
                     encoder_outputs=(encoder_hidden_states,),
                     attention_mask=encoder_attention_mask)
        return out.logits

dec_path = f"{OUT}/decoder_model.onnx"
with torch.no_grad():
    torch.onnx.export(Dec(model), (dec_dummy_ids, enc_out, dummy_mask),
        dec_path,
        input_names=["input_ids", "encoder_hidden_states", "encoder_attention_mask"],
        output_names=["logits"],
        dynamic_axes={"input_ids":{0:"b",1:"s"},
                      "encoder_hidden_states":{0:"b",1:"es"},
                      "encoder_attention_mask":{0:"b",1:"es"},
                      "logits":{0:"b",1:"s"}},
        opset_version=14, dynamo=False)

print("   Merging decoder external data…")
dec_model = onnx.load(dec_path, load_external_data=True)
# Decoder too large for single protobuf, keep external data
onnx.save_model(dec_model, dec_path, save_as_external_data=True,
                all_tensors_to_one_file=True,
                location="decoder_model.onnx.data")
del dec_model

# Clean up stray external data files (keep .onnx and .onnx.data and .json)
for f in os.listdir(OUT):
    fp = os.path.join(OUT, f)
    if f.endswith(".onnx") or f.endswith(".onnx.data") or f.endswith(".json"):
        continue
    try: os.remove(fp)
    except: pass

# ── Tokenizer ──
print("4/6  Exporting tokenizer…")
vocab = tokenizer.get_vocab()
id2tok = {v: k for k, v in vocab.items()}
max_id = max(id2tok) if id2tok else 0
vocab_list = [id2tok.get(i, f"<unk_{i}>") for i in range(max_id + 1)]
with open(f"{OUT}/vocab.json", "w", encoding="utf-8") as f:
    json.dump(vocab_list, f, ensure_ascii=False)

# Export SPM vocab with scores for Viterbi tokenization
import sentencepiece as spm
from huggingface_hub import hf_hub_download
sp_path = hf_hub_download(MODEL, "sentencepiece.bpe.model")
sp = spm.SentencePieceProcessor()
sp.Load(sp_path)
pieces_scores = []
for i in range(sp.GetPieceSize()):
    pieces_scores.append({"piece": sp.IdToPiece(i), "score": float(sp.GetScore(i))})
with open(f"{OUT}/spm_vocab.json", "w", encoding="utf-8") as f:
    json.dump(pieces_scores, f, ensure_ascii=False)

# ── Config ──
print("5/6  Exporting config…")
src_lang_id = tokenizer.convert_tokens_to_ids("jpn_Jpan")
tgt_lang_id = tokenizer.convert_tokens_to_ids("zho_Hans")

cfg = {
    "encoder_file": "encoder_model_quantized.onnx",
    "decoder_file": "decoder_model_quantized.onnx",
    "vocab_file": "vocab.json",
    "spm_vocab_file": "spm_vocab.json",
    "source_lang_id": src_lang_id,
    "target_lang_id": tgt_lang_id,
    "eos_token_id": tokenizer.eos_token_id,
    "pad_token_id": tokenizer.pad_token_id,
    "bos_token_id": tokenizer.bos_token_id or 0,
    "vocab_size": len(vocab_list),
    "max_length": 256,
}
with open(f"{OUT}/config.json", "w") as f:
    json.dump(cfg, f, indent=2)

# ── int8 quantize (skip Conv to avoid ConvInteger) ──
print("6/6  Quantizing to int8…")
from onnxruntime.quantization import quantize_dynamic, QuantType

for name in ["encoder_model", "decoder_model"]:
    src = f"{OUT}/{name}.onnx"
    dst = f"{OUT}/{name}_quantized.onnx"
    # Load model with external data resolved
    m = onnx.load(src, load_external_data=True)
    conv_nodes = [n.name for n in m.graph.node if n.op_type == "Conv"]
    # Pass loaded model object directly to avoid external data path issues
    quantize_dynamic(m, dst, weight_type=QuantType.QInt8,
                     nodes_to_exclude=conv_nodes if conv_nodes else None)
    del m
    # Clean up original
    os.remove(src)
    data_file = f"{src}.data"
    if os.path.exists(data_file):
        os.remove(data_file)
    sz = os.path.getsize(dst) / 1024 / 1024
    print(f"   {name}: {sz:.1f} MB")

# Package
zpath = "output/nllb-600m-int8.zip"
files = ["encoder_model_quantized.onnx", "decoder_model_quantized.onnx",
         "vocab.json", "spm_vocab.json", "config.json"]
# Include any external data files
for ext_data in ["encoder_model_quantized.onnx.data", "decoder_model_quantized.onnx.data"]:
    if os.path.exists(f"{OUT}/{ext_data}"):
        files.append(ext_data)
with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as zf:
    for fn in files:
        zf.write(f"{OUT}/{fn}", fn)

sz = os.path.getsize(zpath) / 1024 / 1024
print(f"\nDone → {zpath} ({sz:.1f} MB)")
print(f"  src_lang_id (jpn_Jpan) = {src_lang_id}")
print(f"  tgt_lang_id (zho_Hans) = {tgt_lang_id}")
