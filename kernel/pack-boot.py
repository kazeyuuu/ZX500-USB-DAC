#!/usr/bin/env python3
"""Replace only the kernel slot in a stock Sony boot image."""
import struct
import sys

if len(sys.argv) != 4:
    raise SystemExit("usage: pack-boot.py sony-stock-boot.img Image boot-zx500-usb-dac.img")

stock_path, image_path, output_path = sys.argv[1:]
stock = bytearray(open(stock_path, "rb").read())
image = open(image_path, "rb").read()

if stock[:8] != b"ANDROID!":
    raise SystemExit("stock input is not an Android boot image")
kernel_size = struct.unpack("<I", stock[8:12])[0]
page_size = struct.unpack("<I", stock[36:40])[0]
slot_size = ((kernel_size + page_size - 1) // page_size) * page_size
kernel_offset = page_size

if len(image) > slot_size:
    raise SystemExit(f"kernel Image is {len(image)} bytes; slot is {slot_size}")

stock[kernel_offset:kernel_offset + slot_size] = image + bytes(slot_size - len(image))
open(output_path, "wb").write(stock)
print(f"wrote {output_path}: kernel={len(image)} slot={slot_size} total={len(stock)}")
