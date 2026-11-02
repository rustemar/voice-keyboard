#!/usr/bin/env python3
"""Check that the release contains the tested model and source-built native runtime."""

import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

ABIS = {"armeabi-v7a": (1, 40), "arm64-v8a": (2, 183), "x86": (1, 3), "x86_64": (2, 62)}
LIBRARIES = ("libonnxruntime.so", "libonnxruntime4j_jni.so")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def verify(apk: Path, aar: Path, model: Path) -> dict:
    expected = {f"jni/{abi}/{name}" for abi in ABIS for name in LIBRARIES}
    with zipfile.ZipFile(aar) as runtime, zipfile.ZipFile(apk) as release:
        actual = {n for n in runtime.namelist() if n.startswith("jni/") and not n.endswith("/")}
        if actual != expected:
            raise ValueError("Runtime AAR must contain exactly the four expected ABI pairs")
        apk_native = {n for n in release.namelist() if n.startswith("lib/") and n.rsplit("/", 1)[-1] in LIBRARIES}
        if apk_native != {"lib/" + n[4:] for n in expected}:
            raise ValueError("APK has an unexpected speech-runtime ABI set")
        hashes = {}
        for name in sorted(expected):
            data = runtime.read(name)
            abi = name.split("/")[1]
            if len(data) < 20 or data[:4] != b"\x7fELF" or data[5] != 1:
                raise ValueError(f"Invalid ELF: {name}")
            if (data[4], struct.unpack_from("<H", data, 18)[0]) != ABIS[abi]:
                raise ValueError(f"Wrong native architecture: {name}")
            packaged = "lib/" + name[4:]
            if release.read(packaged) != data:
                raise ValueError(f"APK changed or replaced the tested runtime: {packaged}")
            hashes[packaged] = sha256(data)
        model_hash = sha256(model.read_bytes())
        model_entry = "assets/" + model.name
        if sha256(release.read(model_entry)) != model_hash:
            raise ValueError("APK does not contain the tested model bytes")
        if model.suffix == ".ort" and "assets/" + model.with_suffix(".onnx").name in release.namelist():
            raise ValueError("APK unnecessarily includes the original ONNX source model")
    return {"apk_sha256": sha256(apk.read_bytes()), "model_sha256": model_hash, "native_sha256": hashes}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--aar", required=True, type=Path)
    parser.add_argument("--model", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.apk, args.aar, args.model), indent=2))


if __name__ == "__main__":
    main()
