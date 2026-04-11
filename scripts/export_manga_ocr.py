#!/usr/bin/env python3
"""Minimal manga-ocr ONNX export."""
import json, os, zipfile
import torch
from transformers import VisionEncoderDecoderModel, AutoTokenizer

MODEL = "kha-white/manga-ocr-base"
OUT = "output/manga-ocr-base"
IMAGE_MEAN = [0.5, 0.5, 0.5]
IMAGE_STD = [0.5, 0.5, 0.5]

os.makedirs(OUT, exist_ok=True)

print("1/5  Loading model…")
model = VisionEncoderDecoderModel.from_pretrained(MODEL)
tokenizer = AutoTokenizer.from_pretrained(MODEL)
model.eval()

# Get actual image size from encoder config
IMG_SIZE = model.encoder.config.image_size
print(f"     Image size: {IMG_SIZE}")

print("2/5  Exporting encoder…")
dummy_px = torch.randn(1, 3, IMG_SIZE, IMG_SIZE)

class Enc(torch.nn.Module):
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, x): return self.m(pixel_values=x).last_hidden_state

with torch.no_grad():
    torch.onnx.export(Enc(model.encoder), (dummy_px,),
        f"{OUT}/encoder_model.onnx",
        input_names=["pixel_values"], output_names=["encoder_hidden_states"],
        dynamic_axes={"pixel_values":{0:"b"}, "encoder_hidden_states":{0:"b"}},
        opset_version=14, dynamo=False)

print("3/5  Exporting decoder…")
with torch.no_grad():
    enc_out = Enc(model.encoder)(dummy_px)
BOS_ID = tokenizer.cls_token_id if tokenizer.bos_token_id is None else tokenizer.bos_token_id
EOS_ID = tokenizer.sep_token_id if tokenizer.eos_token_id is None else tokenizer.eos_token_id
PAD_ID = tokenizer.pad_token_id or 0
dummy_ids = torch.tensor([[BOS_ID]], dtype=torch.long)

class Dec(torch.nn.Module):
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, input_ids, encoder_hidden_states):
        return self.m.decoder(input_ids=input_ids,
            encoder_hidden_states=encoder_hidden_states).logits

with torch.no_grad():
    torch.onnx.export(Dec(model), (dummy_ids, enc_out),
        f"{OUT}/decoder_model.onnx",
        input_names=["input_ids","encoder_hidden_states"],
        output_names=["logits"],
        dynamic_axes={"input_ids":{0:"b",1:"s"}, "encoder_hidden_states":{0:"b"},
                      "logits":{0:"b",1:"s"}},
        opset_version=14, dynamo=False)

print("4/5  Exporting vocab + config…")
vocab = tokenizer.get_vocab()
id2tok = {v:k for k,v in vocab.items()}
max_id = max(id2tok) if id2tok else 0
vocab_list = [id2tok.get(i, f"<unk_{i}>") for i in range(max_id+1)]
with open(f"{OUT}/vocab.json","w",encoding="utf-8") as f:
    json.dump(vocab_list, f, ensure_ascii=False)

cfg = {"encoder_file":"encoder_model_quantized.onnx",
       "decoder_file":"decoder_model_quantized.onnx",
       "vocab_file":"vocab.json","image_size":IMG_SIZE,
       "image_mean":IMAGE_MEAN,"image_std":IMAGE_STD,
       "bos_token_id":BOS_ID,
       "eos_token_id":EOS_ID,
       "pad_token_id":PAD_ID,
       "vocab_size":len(vocab_list),"max_length":300}
with open(f"{OUT}/config.json","w") as f:
    json.dump(cfg, f, indent=2)

print("5/5  Quantizing + packaging…")
from onnxruntime.quantization import quantize_dynamic, QuantType
import onnx
# Find Conv node names in encoder to exclude (ConvInteger unsupported on mobile ORT)
enc_model = onnx.load(f"{OUT}/encoder_model.onnx")
conv_nodes = [n.name for n in enc_model.graph.node if n.op_type == "Conv"]
print(f"     Excluding {len(conv_nodes)} Conv nodes from encoder quantization")
del enc_model
quantize_dynamic(f"{OUT}/encoder_model.onnx", f"{OUT}/encoder_model_quantized.onnx",
                 weight_type=QuantType.QInt8, nodes_to_exclude=conv_nodes)
quantize_dynamic(f"{OUT}/decoder_model.onnx", f"{OUT}/decoder_model_quantized.onnx",
                 weight_type=QuantType.QInt8)
os.remove(f"{OUT}/encoder_model.onnx")
os.remove(f"{OUT}/decoder_model.onnx")

zpath = "output/manga-ocr-base-int8.zip"
with zipfile.ZipFile(zpath,"w",zipfile.ZIP_DEFLATED) as zf:
    for fn in ["encoder_model_quantized.onnx","decoder_model_quantized.onnx",
               "vocab.json","config.json"]:
        zf.write(f"{OUT}/{fn}", fn)

sz = os.path.getsize(zpath)/1024/1024
print(f"\nDone → {zpath} ({sz:.1f} MB)")
