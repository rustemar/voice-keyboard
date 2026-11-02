"""Host checks for archive, provenance and deterministic packaging boundaries."""
import copy
import importlib.util
import json
import shutil
import subprocess
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("runtime_build", Path(__file__).with_name("build.py"))
build = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(build)


def elf(alignment=16384, abi="x86_64"):
    kind, machine = build.ABI_ELF[abi]
    header, entry = (64, 56) if kind == 2 else (52, 32)
    data = bytearray(header + entry)
    data[:6] = b"\x7fELF" + bytes((kind, 1))
    struct.pack_into("<HH", data, 16, 3, machine)
    if kind == 2:
        struct.pack_into("<Q", data, 32, header)
        struct.pack_into("<HH", data, 54, entry, 1)
        struct.pack_into("<IIQQQQQQ", data, header, 1, 5, 0, 0, 0, len(data), len(data), alignment)
    else:
        struct.pack_into("<I", data, 28, header)
        struct.pack_into("<HH", data, 42, entry, 1)
        struct.pack_into("<IIIIIIII", data, header, 1, 0, 0, 0, len(data), len(data), 5, alignment)
    return bytes(data)


def ndk(root: Path, revision: str) -> Path:
    target = root / "ndk"
    target.mkdir(exist_ok=True)
    (target / "source.properties").write_text(f"Pkg.Revision = {revision}\n")
    binaries = target / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    binaries.mkdir(parents=True, exist_ok=True)
    for name in build.NDK_NATIVE_TOOLS:
        (binaries / name).write_bytes(("locked tool " + name).encode())
    return target


def native_cache(work: Path, lock: dict, abi: str, fingerprint: dict) -> dict:
    target = work / "native" / abi
    target.mkdir(parents=True, exist_ok=True)
    libraries = {}
    for name in ("libonnxruntime.so", "libonnxruntime4j_jni.so"):
        path = target / name
        path.write_bytes(elf(abi=abi))
        libraries[name] = {"sha256": build.digest(path)}
    receipt = {**build.native_inputs(lock, abi, fingerprint), "libraries": libraries}
    (target / "receipt.json").write_text(json.dumps(receipt))
    return receipt


def model_asset(root: Path, lock: dict) -> Path:
    model = root / "runtime.ort"
    model.write_bytes(b"\x08\x00\x00\x00ORTMtest asset")
    lock["runtime_asset"]["sha256"] = build.digest(model)
    return model


class RuntimeBuildTests(unittest.TestCase):
    def test_archive_traversal_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = root / "input.zip"
            with zipfile.ZipFile(archive, "w") as z:
                z.writestr("upstream/../../outside", b"unsafe")
            with self.assertRaisesRegex(RuntimeError, "Unsafe archive"):
                build.extract(archive, root / "source")
            self.assertFalse((root / "outside").exists())

    def test_corrupted_cached_archive_never_falls_back_to_network(self):
        with tempfile.TemporaryDirectory() as temp:
            tools = Path(temp)
            spec = {"url": "https://example.invalid/input.zip", "sha256": "0" * 64}
            path = build.cache_path(tools, spec)
            path.parent.mkdir(parents=True)
            path.write_bytes(b"corrupted cache")
            with patch.object(build.urllib.request, "urlopen", side_effect=AssertionError("unexpected network")):
                with self.assertRaisesRegex(RuntimeError, "mismatched SHA-256"):
                    build.download(tools, spec, offline=False)

    def test_elf_rejects_four_kilobyte_alignment(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "library.so"
            path.write_bytes(elf(4096))
            with self.assertRaisesRegex(RuntimeError, "16 KB pages"):
                build.elf_alignment(path)
            path.write_bytes(elf())
            self.assertEqual(build.elf_alignment(path), [16384])

    def test_elf_requires_the_expected_architecture(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "library.so"
            for abi in build.ABI_ELF:
                path.write_bytes(elf(abi=abi))
                self.assertEqual(build.elf_alignment(path, abi), [16384])
            path.write_bytes(elf(abi="arm64-v8a"))
            for wrong in ("armeabi-v7a", "x86", "x86_64"):
                with self.assertRaisesRegex(RuntimeError, "architecture"):
                    build.elf_alignment(path, wrong)

    def test_ndk_revision_is_exact_and_compiler_replacement_changes_fingerprint(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            revision = build.read_lock()["ndk_version"]
            target = ndk(root, revision)
            first = build.ndk_fingerprint(target, revision)
            compiler = target / "toolchains/llvm/prebuilt/linux-x86_64/bin/clang++"
            compiler.write_bytes(b"compiler replaced in place")
            self.assertNotEqual(first, build.ndk_fingerprint(target, revision))
            (target / "source.properties").write_text(f"Pkg.Revision = {revision}-preview\n")
            with self.assertRaisesRegex(RuntimeError, "Expected Android NDK"):
                build.ndk_fingerprint(target, revision)

    def test_verified_native_cache_skips_cmake_and_generated_file_updates(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            lock = build.read_lock()
            target = ndk(root, lock["ndk_version"])
            fingerprint = build.ndk_fingerprint(target, lock["ndk_version"])
            receipt = native_cache(root, lock, "x86_64", fingerprint)
            args = SimpleNamespace(work_dir=root, android_ndk=target)
            with patch.object(build, "run", side_effect=AssertionError("unexpected native rebuild")):
                actual = build.build_native(args, lock, "x86_64", Path("cmake"), {"VOICE_RUNTIME_PYTHON": "python3"}, Path("protoc"), Path("headers"), fingerprint)
            self.assertEqual(actual, receipt["libraries"])

    def test_stale_native_cache_enters_normal_build_with_clean_objects(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            lock = build.read_lock()
            target = ndk(root, lock["ndk_version"])
            fingerprint = build.ndk_fingerprint(target, lock["ndk_version"])
            receipt = native_cache(root, lock, "x86_64", fingerprint)
            receipt["identity"] = "another recipe"
            (root / "native/x86_64/receipt.json").write_text(json.dumps(receipt))
            out = root / "build/x86_64"
            out.mkdir(parents=True)
            (out / "old-object.o").write_bytes(b"stale object")
            args = SimpleNamespace(work_dir=root, android_ndk=target, android_sdk=root / "sdk", jobs=2)
            with patch.object(build, "run", side_effect=RuntimeError("normal build reached")) as run:
                with self.assertRaisesRegex(RuntimeError, "normal build reached"):
                    build.build_native(args, lock, "x86_64", Path("cmake"), {"VOICE_RUNTIME_PYTHON": "python3"}, Path("protoc"), Path("headers"), fingerprint)
            self.assertEqual(run.call_count, 1)
            command = run.call_args.args[0]
            for flag in ("--minimal_build", "--disable_ml_ops", "--disable_contrib_ops"):
                self.assertIn(flag, command)
            for flag in ("--disable_exceptions", "--enable_reduced_operator_type_support", "--minimal_build extended"):
                self.assertNotIn(flag, command)
            self.assertFalse((out / "old-object.o").exists())
            current = json.loads((out / "voice-runtime-input.json").read_text())
            self.assertEqual(current, build.native_inputs(lock, "x86_64", fingerprint))

    def test_changed_compiler_cannot_reuse_native_cache_or_old_objects(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            lock = build.read_lock()
            target = ndk(root, lock["ndk_version"])
            before = build.ndk_fingerprint(target, lock["ndk_version"])
            native_cache(root, lock, "x86_64", before)
            out = root / "build/x86_64"
            build.prepare_native_build_tree(out, build.native_inputs(lock, "x86_64", before))
            (out / "old-object.o").write_bytes(b"old compiler output")
            (target / "toolchains/llvm/prebuilt/linux-x86_64/bin/clang").write_bytes(b"new compiler")
            after = build.ndk_fingerprint(target, lock["ndk_version"])
            self.assertIsNone(build.read_native_cache(root, build.native_inputs(lock, "x86_64", after)))
            args = SimpleNamespace(work_dir=root, android_ndk=target, android_sdk=root / "sdk", jobs=2)
            with patch.object(build, "run", side_effect=RuntimeError("normal build reached")):
                with self.assertRaisesRegex(RuntimeError, "normal build reached"):
                    build.build_native(args, lock, "x86_64", Path("cmake"), {"VOICE_RUNTIME_PYTHON": "python3"}, Path("protoc"), Path("headers"), after)
            self.assertFalse((out / "old-object.o").exists())

    def test_matching_cache_with_tampered_native_code_fails_before_build(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            lock = build.read_lock()
            target = ndk(root, lock["ndk_version"])
            fingerprint = build.ndk_fingerprint(target, lock["ndk_version"])
            native_cache(root, lock, "x86_64", fingerprint)
            (root / "native/x86_64/libonnxruntime.so").write_bytes(b"tampered")
            with patch.object(build, "run", side_effect=AssertionError("must fail closed")):
                with self.assertRaisesRegex(RuntimeError, "mismatched SHA-256"):
                    build.build_native(SimpleNamespace(work_dir=root, android_ndk=target), lock, "x86_64", Path("cmake"), {"VOICE_RUNTIME_PYTHON": "python3"}, Path("protoc"), Path("headers"), fingerprint)

    def test_partial_build_resumes_only_with_identical_inputs(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            expected = {"identity": "recipe", "abi": "x86_64", "ndk": {"revision": "locked"}}
            out = root / "build/x86_64"
            build.prepare_native_build_tree(out, expected)
            marker = out / "partial-object.o"
            marker.write_bytes(b"safe partial")
            build.prepare_native_build_tree(out, expected)
            self.assertTrue(marker.exists())
            build.prepare_native_build_tree(out, {**expected, "identity": "new recipe"})
            self.assertFalse(marker.exists())

    def test_packaging_is_deterministic_and_excludes_template_native_code(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            template = root / "upstream.aar"
            preserved = {"classes.jar": b"unchanged Java bytecode", "AndroidManifest.xml": b"manifest", "headers/header.h": b"upstream header"}
            with zipfile.ZipFile(template, "w") as z:
                for name, data in preserved.items():
                    z.writestr(name, data)
                z.writestr("jni/arm64-v8a/libobsolete.so", b"must not be shipped")
            lock = copy.deepcopy(build.read_lock())
            lock["template"]["sha256"] = build.digest(template)
            model = model_asset(root, lock)
            target = ndk(root, lock["ndk_version"])
            fingerprint = build.ndk_fingerprint(target, lock["ndk_version"])
            source = root / "source/onnxruntime"
            source.mkdir(parents=True)
            for name in ("LICENSE", "ThirdPartyNotices.txt"):
                (source / name).write_text(name)
            for abi in lock["abis"]:
                native_cache(root, lock, abi, fingerprint)
            first, second = root / "first.aar", root / "second.aar"
            build.package_aar(template, first, root, lock, model)
            build.package_aar(template, second, root, lock, model)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with zipfile.ZipFile(first) as z:
                self.assertNotIn("jni/arm64-v8a/libobsolete.so", z.namelist())
                self.assertEqual(len([name for name in z.namelist() if name.startswith("jni/")]), 8)
                for name, data in preserved.items():
                    self.assertEqual(z.read(name), data)
            (root / "native/arm64-v8a/libonnxruntime.so").write_bytes(b"modified native input")
            with self.assertRaisesRegex(RuntimeError, "mismatched SHA-256"):
                build.package_aar(template, root / "corrupt.aar", root, lock, model)

    def test_packaging_rejects_wrong_receipt_abi_and_wrong_elf_machine(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            template = root / "upstream.aar"
            with zipfile.ZipFile(template, "w") as z:
                z.writestr("classes.jar", b"Java")
                z.writestr("AndroidManifest.xml", b"manifest")
            lock = copy.deepcopy(build.read_lock())
            lock["template"]["sha256"] = build.digest(template)
            model = model_asset(root, lock)
            fingerprint = build.ndk_fingerprint(ndk(root, lock["ndk_version"]), lock["ndk_version"])
            for abi in lock["abis"]:
                native_cache(root, lock, abi, fingerprint)
            path = root / "native/armeabi-v7a/receipt.json"
            receipt = json.loads(path.read_text())
            receipt["abi"] = "arm64-v8a"
            path.write_text(json.dumps(receipt))
            with self.assertRaisesRegex(RuntimeError, "Stale native inputs"):
                build.package_aar(template, root / "wrong-receipt.aar", root, lock, model)
            receipt["abi"] = "armeabi-v7a"
            library = root / "native/armeabi-v7a/libonnxruntime.so"
            library.write_bytes(elf(abi="arm64-v8a"))
            receipt["libraries"][library.name]["sha256"] = build.digest(library)
            path.write_text(json.dumps(receipt))
            with self.assertRaisesRegex(RuntimeError, "architecture"):
                build.package_aar(template, root / "wrong-architecture.aar", root, lock, model)


    def test_runtime_asset_selection_fails_closed_until_pinned(self):
        lock = build.read_lock()
        lock["runtime_asset"]["sha256"] = None
        with self.assertRaisesRegex(RuntimeError, "selection is pending"):
            build.validate_runtime_asset(None, lock)

    def test_runtime_asset_hash_and_config_binding_are_checked(self):
        with tempfile.TemporaryDirectory() as temp:
            lock = copy.deepcopy(build.read_lock())
            model = model_asset(Path(temp), lock)
            self.assertEqual(build.validate_runtime_asset(model, lock), build.digest(model))
            lock["runtime_asset"]["operators_sha256"] = "0" * 64
            with self.assertRaisesRegex(RuntimeError, "operator config"):
                build.validate_runtime_asset(model, lock)
            lock["runtime_asset"]["operators_sha256"] = lock["operators_sha256"]
            model.write_bytes(b"another runtime asset")
            with self.assertRaisesRegex(RuntimeError, "mismatched SHA-256"):
                build.validate_runtime_asset(model, lock)

    def test_native_identity_can_reuse_only_asset_data_changes(self):
        lock = copy.deepcopy(build.read_lock())
        first = build.identity(lock)
        lock["runtime_asset"]["sha256"] = "f" * 64
        lock["runtime_asset"]["selection"] = "same config, approved 16 kHz asset"
        self.assertEqual(build.identity(lock), first)
        lock["operators_sha256"] = "0" * 64
        self.assertNotEqual(build.identity(lock), first)

    def test_profile_refuses_exception_or_type_reduction_changes(self):
        lock = copy.deepcopy(build.read_lock())
        for name, value in (("exceptions", False), ("type_reduction", True), ("contrib_ops", True), ("minimal", False)):
            changed = copy.deepcopy(lock)
            changed["build_profile"][name] = value
            with self.assertRaisesRegex(RuntimeError, "Unsupported runtime build profile"):
                build.profile_flags(changed)



    @unittest.skipUnless(shutil.which("cmake"), "CMake is required for the source-selection probe")
    def test_cpu_activation_helper_is_selected_without_enabling_contrib_kernels(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for disabled, expected in (("ON", 1), ("OFF", 0)):
                probe = root / "selection.cmake"
                probe.write_text(f'''set(ANDROID TRUE)
set(REPO_ROOT "{root.as_posix()}")
set(ONNXRUNTIME_ROOT "${{REPO_ROOT}}/onnxruntime")
set(VOICE_JNI_HEADERS "{root.as_posix()}")
set(onnxruntime_DISABLE_CONTRIB_OPS {disabled})
function(target_sources target scope)
  if(NOT target STREQUAL "onnxruntime_providers" OR NOT scope STREQUAL "PRIVATE")
    message(FATAL_ERROR "Unexpected source target or scope")
  endif()
  set(selected_sources ${{ARGN}} PARENT_SCOPE)
endfunction()
function(onnxruntime_add_shared_library_module)
endfunction()
function(set_property)
endfunction()
function(onnxruntime_add_include_to_target)
endfunction()
function(target_include_directories)
endfunction()
function(target_link_libraries)
endfunction()
function(target_link_options target scope)
  if(NOT target STREQUAL "onnxruntime4j_jni" OR NOT scope STREQUAL "PRIVATE")
    message(FATAL_ERROR "Unexpected linker target or scope")
  endif()
  set(selected_link_options ${{ARGN}} PARENT_SCOPE)
endfunction()
include("{build.HERE.as_posix()}/onnxruntime_java.cmake")
if(NOT selected_link_options STREQUAL "-Wl,-s")
  message(FATAL_ERROR "JNI must strip at link time without dropping the build ID")
endif()
list(LENGTH selected_sources source_count)
if(NOT source_count EQUAL {expected})
  message(FATAL_ERROR "Wrong helper selection count")
endif()
if(source_count GREATER 0 AND NOT selected_sources STREQUAL "${{ONNXRUNTIME_ROOT}}/contrib_ops/cpu/fused_activation.cc")
  message(FATAL_ERROR "Only the upstream CPU activation helper may be selected")
endif()
''')
                subprocess.run([shutil.which("cmake"), "-P", str(probe)], check=True, capture_output=True, text=True)


if __name__ == "__main__":
    unittest.main()
