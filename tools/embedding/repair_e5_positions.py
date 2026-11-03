"""Reproducibly repair the audited positional shift without changing any other tensor.

No downloads or third-party dependencies. The original GGUF is never modified.
Input hashes intentionally restrict this repair to the artifact examined in
docs/EMBEDDING_RESEARCH.md; this is not a general GGUF conversion tool.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import struct

SOURCE_HASH = "e011debc1208e31bf7b6aebee2d9fc8bd2ca11694a77ed66ac9d0c9d0a877c93"
POSITIONS_HASH = "3c87459adc43ea62d7f0dc722136e515d7d97ab60f33369d9ac305646a84ee89"
POSITION_NAME = "position_embd.weight"


def digest(data: bytes | bytearray) -> str:
    return hashlib.sha256(data).hexdigest()


class DescriptorReader:
    FORMATS = {0: "B", 1: "b", 2: "H", 3: "h", 4: "I", 5: "i", 6: "f", 7: "?", 10: "Q", 11: "q", 12: "d"}

    def __init__(self, data: bytes | bytearray):
        self.data = data
        self.cursor = 0

    def number(self, fmt: str) -> int:
        result = struct.unpack_from("<" + fmt, self.data, self.cursor)[0]
        self.cursor += struct.calcsize("<" + fmt)
        return result

    def string(self) -> str:
        size = self.number("Q")
        result = self.data[self.cursor:self.cursor + size].decode("utf-8")
        self.cursor += size
        return result

    def skip(self, kind: int) -> None:
        if kind == 8:
            size = self.number("Q")
            self.cursor += size
        elif kind == 9:
            element, count = self.number("I"), self.number("Q")
            if element in self.FORMATS:
                self.cursor += count * struct.calcsize("<" + self.FORMATS[element])
            else:
                for _ in range(count):
                    self.skip(element)
        else:
            self.cursor += struct.calcsize("<" + self.FORMATS[kind])

    def parse(self) -> dict:
        if self.data[:4] != b"GGUF":
            raise ValueError("Not a GGUF file")
        self.cursor = 4
        if self.number("I") != 3:
            raise ValueError("Only audited GGUF version 3 is supported")
        tensor_count, metadata_count = self.number("Q"), self.number("Q")
        if tensor_count != 197 or metadata_count != 33:
            raise ValueError("Unexpected metadata/tensor count")
        context_field = None
        context = None
        alignment = 32
        for _ in range(metadata_count):
            name, kind = self.string(), self.number("I")
            if name == "bert.context_length":
                if kind != 4:
                    raise ValueError("Unexpected context metadata type")
                context_field = self.cursor
                context = self.number("I")
            elif name == "general.alignment":
                if kind != 4:
                    raise ValueError("Unexpected alignment metadata type")
                alignment = self.number("I")
            else:
                self.skip(kind)
        if alignment != 32 or context_field is None:
            raise ValueError("Unexpected layout/context metadata")
        descriptors = {}
        for _ in range(tensor_count):
            name = self.string()
            rank = self.number("I")
            if not 1 <= rank <= 4:
                raise ValueError("Unexpected tensor rank")
            dimensions_field = self.cursor
            shape = [self.number("Q") for _ in range(rank)]
            kind = self.number("I")
            offset_field = self.cursor
            offset = self.number("Q")
            elements = math.prod(shape)
            if kind == 0:
                size = elements * 4
            elif kind == 8 and shape[0] % 32 == 0:
                size = elements // 32 * 34
            else:
                raise ValueError("Unexpected tensor type; refusing unaudited repair")
            if name in descriptors or offset % alignment:
                raise ValueError("Invalid tensor descriptors")
            descriptors[name] = {
                "shape": shape, "type": kind, "offset": offset, "size": size,
                "dimensions_field": dimensions_field, "offset_field": offset_field,
            }
        data_start = (self.cursor + alignment - 1) // alignment * alignment
        previous_end = 0
        for descriptor in sorted(descriptors.values(), key=lambda x: x["offset"]):
            offset, size = descriptor["offset"], descriptor["size"]
            if offset < previous_end or data_start + offset + size > len(self.data):
                raise ValueError("Overlapping/out-of-file tensor")
            previous_end = offset + size
        return {"context": context, "context_field": context_field, "data_start": data_start, "tensors": descriptors}


def repair(source: Path, positions: Path, output: Path) -> dict:
    if output.exists() or output.resolve() == source.resolve():
        raise ValueError("Output must be a new file; original and existing outputs are never overwritten")
    if not output.parent.is_dir():
        raise ValueError("Output parent directory must exist")
    original = source.read_bytes()
    official = positions.read_bytes()
    if len(original) != 132439008 or digest(original) != SOURCE_HASH:
        raise ValueError("Source hash/size is not the audited artifact")
    if len(official) != 512 * 384 * 4 or digest(official) != POSITIONS_HASH:
        raise ValueError("Official positional tensor hash/size mismatch")
    before = DescriptorReader(original).parse()
    old = before["tensors"][POSITION_NAME]
    if before["context"] != 511 or old["shape"] != [384, 511] or old["type"] != 0:
        raise ValueError("Unexpected source positional layout")
    start = before["data_start"] + old["offset"]
    end = start + old["size"]
    if original[start:end] != official[384 * 4:]:
        raise ValueError("Source does not have the audited one-row shift")
    delta = len(official) - old["size"]
    if delta != 1536 or delta % 32:
        raise ValueError("Unexpected alignment-changing size delta")

    fixed = bytearray(original[:start])
    fixed.extend(official)
    fixed.extend(original[end:])
    struct.pack_into("<I", fixed, before["context_field"], 512)
    struct.pack_into("<Q", fixed, old["dimensions_field"] + 8, 512)
    for descriptor in before["tensors"].values():
        if descriptor["offset"] > old["offset"]:
            struct.pack_into("<Q", fixed, descriptor["offset_field"], descriptor["offset"] + delta)

    after = DescriptorReader(fixed).parse()
    if after["context"] != 512 or after["data_start"] != before["data_start"]:
        raise ValueError("Invalid repaired metadata/layout")
    if set(before["tensors"]) != set(after["tensors"]):
        raise ValueError("Tensor list changed")
    hashes = {}
    unchanged = 0
    for name, descriptor in before["tensors"].items():
        other = after["tensors"][name]
        new_start = after["data_start"] + other["offset"]
        actual = fixed[new_start:new_start + other["size"]]
        if name == POSITION_NAME:
            if other["shape"] != [384, 512] or other["type"] != 0 or actual != official:
                raise ValueError("Repaired positional tensor mismatch")
        else:
            old_start = before["data_start"] + descriptor["offset"]
            if other["shape"] != descriptor["shape"] or other["type"] != descriptor["type"]:
                raise ValueError("Non-positional shape/type changed")
            if actual != original[old_start:old_start + descriptor["size"]]:
                raise ValueError("Non-positional tensor data changed")
            unchanged += 1
        hashes[name] = digest(actual)

    # Raw metadata (including tokenizer arrays) must be identical except context uint32.
    expected_metadata = bytearray(original[:before["tensors"][next(iter(before["tensors"]))]["dimensions_field"]])
    struct.pack_into("<I", expected_metadata, before["context_field"], 512)
    if fixed[:len(expected_metadata)] != expected_metadata:
        raise ValueError("Unexpected raw metadata change")
    with output.open("xb") as stream:
        stream.write(fixed)
    output_hash = digest(fixed)
    if hashlib.file_digest(output.open("rb"), "sha256").hexdigest() != output_hash:
        raise ValueError("Written artifact hash mismatch")
    if hashlib.file_digest(source.open("rb"), "sha256").hexdigest() != SOURCE_HASH:
        raise ValueError("Original changed during repair")
    report = {
        "source_sha256": SOURCE_HASH, "positions_sha256": POSITIONS_HASH,
        "output_sha256": output_hash, "output_bytes": len(fixed), "added_bytes": delta,
        "context": 512, "position_shape": [384, 512],
        "tensor_count": len(hashes), "non_position_tensors_byte_identical": unchanged,
        "metadata_unchanged_except_context": True, "tensor_sha256": hashes,
    }
    output.with_suffix(".verification.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return {key: value for key, value in report.items() if key != "tensor_sha256"}


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=root / "models/multilingual-e5-small-q8_0.gguf")
    parser.add_argument("--positions", type=Path, default=root / "models/e5-official-position-f32.bin")
    parser.add_argument("--output", type=Path, default=root / "models/multilingual-e5-small-q8_0-posfix.gguf")
    args = parser.parse_args()
    print(json.dumps(repair(args.source, args.positions, args.output), indent=2))
