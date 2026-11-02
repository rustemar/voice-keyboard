import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("verify_speech_apk", Path(__file__).with_name("verify_speech_apk.py"))
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class ReleaseRuntimeVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.aar = self.root / "runtime.aar"
        self.apk = self.root / "app.apk"
        self.model = self.root / "silero_vad.ort"
        self.model.write_bytes(b"test model")
        self.native = {}
        for abi, (elf_class, machine) in VERIFY.ABIS.items():
            data = bytearray(20)
            data[:6] = b"\x7fELF" + bytes((elf_class, 1))
            struct.pack_into("<H", data, 18, machine)
            for name in VERIFY.LIBRARIES:
                self.native[f"jni/{abi}/{name}"] = bytes(data)

    def write_archives(self, *, changed=None, missing=None, wrong_model=False):
        with zipfile.ZipFile(self.aar, "w") as z:
            for name, data in self.native.items():
                z.writestr(name, data)
        with zipfile.ZipFile(self.apk, "w") as z:
            for name, data in self.native.items():
                path = "lib/" + name[4:]
                if path != missing:
                    z.writestr(path, data + (b"changed" if path == changed else b""))
            z.writestr("assets/" + self.model.name, b"wrong" if wrong_model else self.model.read_bytes())

    def test_verified_model_and_all_four_abi_pairs_pass(self):
        self.write_archives()
        result = VERIFY.verify(self.apk, self.aar, self.model)
        self.assertEqual(8, len(result["native_sha256"]))

    def test_packaging_cannot_replace_or_strip_tested_native_bytes(self):
        self.write_archives(changed="lib/arm64-v8a/libonnxruntime.so")
        with self.assertRaisesRegex(ValueError, "changed or replaced"):
            VERIFY.verify(self.apk, self.aar, self.model)

    def test_missing_device_architecture_fails(self):
        self.write_archives(missing="lib/x86/libonnxruntime4j_jni.so")
        with self.assertRaisesRegex(ValueError, "ABI set"):
            VERIFY.verify(self.apk, self.aar, self.model)

    def test_wrong_model_fails(self):
        self.write_archives(wrong_model=True)
        with self.assertRaisesRegex(ValueError, "tested model"):
            VERIFY.verify(self.apk, self.aar, self.model)

    def test_original_source_model_is_excluded_from_apk(self):
        self.write_archives()
        with zipfile.ZipFile(self.apk, "a") as z:
            z.writestr("assets/silero_vad.onnx", b"unneeded source model")
        with self.assertRaisesRegex(ValueError, "ONNX source model"):
            VERIFY.verify(self.apk, self.aar, self.model)

    def test_mislabeled_native_architecture_fails_even_when_apk_matches(self):
        self.native["jni/arm64-v8a/libonnxruntime.so"] = self.native["jni/x86_64/libonnxruntime.so"]
        self.write_archives()
        with self.assertRaisesRegex(ValueError, "Wrong native architecture"):
            VERIFY.verify(self.apk, self.aar, self.model)


if __name__ == "__main__":
    unittest.main()
