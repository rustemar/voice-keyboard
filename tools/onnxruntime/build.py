#!/usr/bin/env python3
"""Prepare audited source inputs, then build the Silero runtime without network access.

Only prepare downloads files. Source trees stay in --work-dir for F-Droid scanning;
archives and approved PyPI tool wheels stay in the separate --tools-dir.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import stat
import struct
import subprocess
import sys
import tarfile
import time
import urllib.request
import venv
import zipfile

HERE = Path(__file__).resolve().parent
DEFAULT_TOOLS = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "voice-keyboard/onnxruntime"
PRUNE_ORT = (
    "csharp", "js", "rust", "samples", "onnxruntime/test", "java/src/test",
    "cmake/external/git.Win32.2.41.03.patch", "java/build.gradle", "java/build-android.gradle",
    "java/settings.gradle", "java/settings-android.gradle", "java/gradle", "java/gradlew", "java/gradlew.bat",
)
PRUNE_DEPS = {
    "abseil_cpp": ("absl/time/internal/cctz/testdata",),
    "protobuf": ("objectivec", "examples", "src/google/protobuf/testdata"),
    "re2": ("app",),
    "safeint": ("Archive",),
    "kleidiai": ("third_party", "test", "benchmarks"),
    "date": ("test",),
    "nlohmann_json": ("tests",),
    "flatbuffers": ("android", "tests", "samples", "dart", "go", "java", "js", "kotlin", "lobster", "lua", "net", "nim", "php", "python", "reflection", "rust", "swift", "ts"),
}
ABI_ELF = {
    "armeabi-v7a": (1, 40),
    "arm64-v8a": (2, 183),
    "x86": (1, 3),
    "x86_64": (2, 62),
}
NDK_NATIVE_TOOLS = ("clang", "clang++", "ld.lld", "llvm-strip")


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def require_hash(path: Path, expected: str) -> None:
    if not path.is_file() or digest(path) != expected:
        raise RuntimeError(f"Missing or mismatched SHA-256 input: {path}")


def read_lock() -> dict:
    lock = json.loads((HERE / "source-lock.json").read_text())
    require_hash(HERE / "required_operators.config", lock["operators_sha256"])
    profile_flags(lock)
    return lock


def identity(lock: dict) -> str:
    # Model bytes are not compiled into the native runtime. Keep their exact
    # selection separate while profile, kernel config, source and tools stay bound.
    compiler_lock = {name: value for name, value in lock.items() if name != "runtime_asset"}
    h = hashlib.sha256(json.dumps(compiler_lock, sort_keys=True).encode())
    for name in ("build.py", "onnxruntime_java.cmake", "required_operators.config"):
        h.update(name.encode())
        h.update((HERE / name).read_bytes())
    return h.hexdigest()


def profile_flags(lock: dict) -> list[str]:
    expected = {"minimal": True, "exceptions": True, "contrib_ops": False,
                "ml_ops": False, "type_reduction": False, "execution_provider": "CPU",
                "model_format": "ORT", "graph_optimization": "BASIC", "quantization": False}
    if lock.get("build_profile") != expected:
        raise RuntimeError("Unsupported runtime build profile; preserve exceptions and scalar types")
    return ["--minimal_build", "--disable_ml_ops", "--disable_contrib_ops"]


def validate_runtime_asset(model: Path | None, lock: dict) -> str:
    asset = lock.get("runtime_asset", {})
    expected = asset.get("sha256")
    if not isinstance(expected, str) or len(expected) != 64:
        raise RuntimeError("Runtime asset selection is pending; pin the approved ORT asset before preparing or packaging")
    if (asset.get("operators_sha256") != lock["operators_sha256"] or
            asset.get("source_model_sha256") != lock["source_model"]["sha256"] or
            asset.get("format") != "ORT" or asset.get("optimization") != "BASIC" or
            asset.get("quantization") is not False):
        raise RuntimeError("Runtime asset does not match the locked operator config and conversion provenance")
    if model is None:
        raise RuntimeError("Packaging requires the selected runtime asset for validation")
    require_hash(model, expected)
    with model.open("rb") as data:
        if data.read(8)[4:8] != b"ORTM":
            raise RuntimeError("Expected the selected ORT-format runtime asset")
    return expected


def cache_path(tools: Path, spec: dict) -> Path:
    return tools / "archives" / (spec["sha256"] + "-" + spec["url"].rsplit("/", 1)[-1])


def download(tools: Path, spec: dict, offline: bool) -> Path:
    target = cache_path(tools, spec)
    if target.exists():
        require_hash(target, spec["sha256"])
        return target
    if offline:
        raise RuntimeError(f"Offline preparation needs cached input: {target}")
    target.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading {spec.get('name', 'onnxruntime')}: {spec['url']}", flush=True)
    partial = target.with_name(target.name + ".partial")
    try:
        with urllib.request.urlopen(spec["url"], timeout=90) as src, partial.open("wb") as dst:
            shutil.copyfileobj(src, dst)
        require_hash(partial, spec["sha256"])
        partial.replace(target)
    finally:
        partial.unlink(missing_ok=True)
    return target


def safe_member(root: Path, name: str, strip_root: bool) -> Path | None:
    parts = PurePosixPath(name).parts
    if not parts or parts[0] == "/" or ".." in parts:
        raise RuntimeError(f"Unsafe archive member: {name}")
    if strip_root:
        parts = parts[1:]
    return root.joinpath(*parts) if parts else None


def extract(archive: Path, root: Path, strip_root: bool = True) -> None:
    root.mkdir(parents=True, exist_ok=True)
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as z:
            for entry in z.infolist():
                target = safe_member(root, entry.filename, strip_root)
                if target is None:
                    continue
                if entry.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                mode = entry.external_attr >> 16
                if stat.S_ISLNK(mode):
                    link = z.read(entry).decode()
                    if not (target.parent / link).resolve().is_relative_to(root.resolve()):
                        raise RuntimeError(f"Unsafe archive symlink: {entry.filename}")
                    target.symlink_to(link)
                else:
                    with z.open(entry) as src, target.open("wb") as dst:
                        shutil.copyfileobj(src, dst)
                    target.chmod(0o755 if mode & 0o111 else 0o644)
    else:
        with tarfile.open(archive) as tar:
            for entry in tar:
                target = safe_member(root, entry.name, strip_root)
                if target is None:
                    continue
                if entry.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                elif entry.isfile():
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with tar.extractfile(entry) as src, target.open("wb") as dst:
                        shutil.copyfileobj(src, dst)
                    target.chmod(0o755 if entry.mode & 0o111 else 0o644)
                elif entry.issym():
                    if not (target.parent / entry.linkname).resolve().is_relative_to(root.resolve()):
                        raise RuntimeError(f"Unsafe archive symlink: {entry.name}")
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.symlink_to(entry.linkname)
                else:
                    raise RuntimeError(f"Unsupported archive member: {entry.name}")


def remove(root: Path, relative: str) -> None:
    path = root / relative
    if path.is_symlink() or path.is_file():
        path.unlink()
    elif path.exists():
        shutil.rmtree(path)


def source_manifest(root: Path) -> dict:
    result = {}
    for path in sorted(root.rglob("*")):
        rel = path.relative_to(root).as_posix()
        if path.is_symlink():
            result[rel] = {"symlink": os.readlink(path)}
        elif path.is_file():
            result[rel] = {"sha256": digest(path), "executable": bool(path.stat().st_mode & 0o111)}
    return result


def run(argv: list[str | Path], *, cwd: Path | None = None, env: dict | None = None, stdin=None) -> None:
    print("Running:", " ".join(map(str, argv)), flush=True)
    subprocess.run(list(map(str, argv)), cwd=cwd, env=env, stdin=stdin, check=True)


def prepare(args, lock: dict) -> None:
    work, tools = args.work_dir, args.tools_dir
    validate_runtime_asset(args.model, lock)
    if tools.is_relative_to(work):
        raise RuntimeError("--tools-dir must be outside --work-dir (keep binary tool archives out of source scanning)")
    specs = [lock["onnxruntime"]] + lock["sources"] + lock["tools"]
    for spec in specs:
        download(tools, spec, args.offline)
    marker = work / "prepared.json"
    if marker.exists():
        check_prepared(args, lock)
        print("Prepared source inputs already match the lock", flush=True)
        return
    if work.exists() and any(work.iterdir()):
        raise RuntimeError(f"Preparation requires an empty work directory or matching prepared.json: {work}")
    work.mkdir(parents=True, exist_ok=True)
    src = work / "source"
    ort = src / "onnxruntime"
    extract(cache_path(tools, lock["onnxruntime"]), ort)
    require_hash(ort / "cmake/deps.txt", lock["onnxruntime"]["deps_file_sha256"])
    for spec in lock["sources"]:
        dep = src / "deps" / spec["name"]
        extract(cache_path(tools, spec), dep)
        for patch in spec["patches"]:
            patch_path = ort / "cmake/patches" / patch["path"]
            require_hash(patch_path, patch["sha256"])
            with patch_path.open("rb") as data:
                run(["patch", "--batch", "--binary", "--ignore-whitespace", "--no-backup-if-mismatch", "-p1"], cwd=dep, stdin=data)
        for relative in PRUNE_DEPS.get(spec["name"], ()):
            remove(dep, relative)
    for relative in PRUNE_ORT:
        remove(ort, relative)
    shutil.copyfile(HERE / "onnxruntime_java.cmake", ort / "cmake/onnxruntime_java.cmake")
    # FetchContent source overrides below make every selected dependency local. Also
    # replace all upstream URL declarations: any accidentally enabled extra EP fails
    # on a nonexistent local file instead of silently reaching the network.
    lines = []
    for line in (ort / "cmake/deps.txt").read_text().splitlines():
        if line and not line.startswith("#"):
            name, _url, sha1 = line.split(";")
            line = f"{name};{(work / 'unavailable' / name).as_posix()};{sha1}"
        lines.append(line)
    (ort / "cmake/deps.txt").write_text("\n".join(lines) + "\n")
    manifest = source_manifest(src)
    (work / "source-manifest.json").write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n")
    marker.write_text(json.dumps({"identity": identity(lock), "source_files": len(manifest)}, indent=2) + "\n")
    print(f"Prepared {len(manifest)} source files; no binaries were built", flush=True)


def check_prepared(args, lock: dict) -> None:
    validate_runtime_asset(args.model, lock)
    marker = json.loads((args.work_dir / "prepared.json").read_text())
    if marker["identity"] != identity(lock):
        raise RuntimeError("Prepared inputs belong to another tool/config lock; prepare in a fresh work directory")
    expected = json.loads((args.work_dir / "source-manifest.json").read_text())
    if source_manifest(args.work_dir / "source") != expected:
        raise RuntimeError("Prepared source tree changed; prepare in a fresh work directory")
    for spec in [lock["onnxruntime"]] + lock["sources"] + lock["tools"]:
        require_hash(cache_path(args.tools_dir, spec), spec["sha256"])


def tools_environment(args, lock: dict) -> tuple[Path, dict]:
    target = args.tools_dir / "unpacked" / identity(lock)
    # Extract wheels again into a fresh directory, never trust mutable cached tools.
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    for spec in lock["tools"]:
        extract(cache_path(args.tools_dir, spec), target, strip_root=False)
    cmake = target / "cmake/data/bin/cmake"
    ninja = target / "ninja-1.11.1.4.data/scripts/ninja"
    if not cmake.is_file() or not ninja.is_file():
        raise RuntimeError("Pinned tool wheel layout changed")
    venv.EnvBuilder(with_pip=False, symlinks=True).create(target / "python-env")
    env = os.environ.copy()
    env["VOICE_RUNTIME_PYTHON"] = str(target / "python-env/bin/python")
    env.update(SOURCE_DATE_EPOCH=str(lock["source_date_epoch"]), TZ="UTC", LC_ALL="C", PYTHONNOUSERSITE="1", PYTHONDONTWRITEBYTECODE="1")
    env["PYTHONPATH"] = str(target)
    env["PATH"] = str(cmake.parent) + os.pathsep + str(ninja.parent) + os.pathsep + env["PATH"]
    # Proxy settings must never affect the offline stage.
    for name in list(env):
        if name.lower().endswith("_proxy"):
            del env[name]
    for binary, version in [(cmake, "cmake version 3.31.6"), (ninja, "1.11.1")]:
        actual = subprocess.check_output([str(binary), "--version"], text=True, env=env)
        if not actual.startswith(version):
            raise RuntimeError(f"Pinned tool reports unexpected version: {actual}")
    return cmake, env


def dependency_defines(args, lock: dict) -> list[str]:
    result = ["FETCHCONTENT_FULLY_DISCONNECTED=ON", "FETCHCONTENT_UPDATES_DISCONNECTED=ON", "FETCHCONTENT_TRY_FIND_PACKAGE_MODE=NEVER"]
    for spec in lock["sources"]:
        result.append(f"FETCHCONTENT_SOURCE_DIR_{spec['name'].upper()}={args.work_dir / 'source/deps' / spec['name']}")
    # SafeInt uses FetchContent_Populate() rather than MakeAvailable().
    result.append(f"safeint_SOURCE_DIR={args.work_dir / 'source/deps/safeint'}")
    return result


def build_protoc(args, lock: dict, cmake: Path, env: dict) -> Path:
    out = args.work_dir / "build/host-protoc"
    executable = out / "protoc"
    stamp = out / "voice-runtime-input.json"
    expected = {"identity": identity(lock), "kind": "host-protoc"}
    cached = json.loads(stamp.read_text()) if stamp.exists() else {}
    if cached.get("inputs") != expected or not executable.is_file() or cached.get("sha256") != digest(executable):
        if out.exists():
            shutil.rmtree(out)
        run([cmake, "-S", args.work_dir / "source/deps/protobuf/cmake", "-B", out, "-G", "Ninja",
             "-DCMAKE_BUILD_TYPE=Release", "-Dprotobuf_BUILD_TESTS=OFF", "-Dprotobuf_BUILD_SHARED_LIBS=OFF",
             "-Dprotobuf_BUILD_PROTOC_BINARIES=ON", "-Dprotobuf_WITH_ZLIB=OFF", "-Dprotobuf_INSTALL=OFF"], env=env)
        run([cmake, "--build", out, "--target", "protoc", "--parallel", str(args.jobs)], env=env)
        if subprocess.check_output([str(executable), "--version"], text=True).strip() != "libprotoc 3.21.12":
            raise RuntimeError("Source-built protoc reports an unexpected version")
        stamp.write_text(json.dumps({"inputs": expected, "sha256": digest(executable)}, sort_keys=True) + "\n")
    return executable


def generate_headers(args, env: dict) -> Path:
    ort = args.work_dir / "source/onnxruntime"
    target = args.work_dir / "build/jni-headers"
    classes = args.work_dir / "build/header-classes"
    target.mkdir(parents=True, exist_ok=True)
    classes.mkdir(parents=True, exist_ok=True)
    java = sorted((ort / "java/src/main/java").rglob("*.java")) + sorted((ort / "java/src/main/jvm").rglob("*.java"))
    run(["javac", "--release", "8", "-h", target, "-d", classes, *java], env=env)
    return target


def elf_alignment(path: Path, abi: str | None = None) -> list[int]:
    data = path.read_bytes()
    if len(data) < 20 or data[:4] != b"\x7fELF" or data[5] != 1 or data[4] not in (1, 2):
        raise RuntimeError(f"Expected little-endian ELF: {path}")
    kind, machine = struct.unpack_from("<HH", data, 16)
    if kind != 3:
        raise RuntimeError(f"Expected an ELF shared library: {path}")
    if abi is not None and ABI_ELF.get(abi) != (data[4], machine):
        raise RuntimeError(f"ELF architecture does not match {abi}: {path}")
    is64 = data[4] == 2
    header_bytes, entry_bytes = (64, 56) if is64 else (52, 32)
    if len(data) < header_bytes:
        raise RuntimeError(f"Truncated ELF header: {path}")
    phoff = struct.unpack_from("<Q" if is64 else "<I", data, 32 if is64 else 28)[0]
    entsize, count = struct.unpack_from("<HH", data, 54 if is64 else 42)
    if entsize < entry_bytes or phoff + entsize * count > len(data):
        raise RuntimeError(f"Truncated ELF program headers: {path}")
    alignments = []
    for index in range(count):
        off = phoff + entsize * index
        values = struct.unpack_from("<IIQQQQQQ" if is64 else "<IIIIIIII", data, off)
        if values[0] != 1:
            continue
        file_offset, virtual, alignment = (values[2], values[3], values[7]) if is64 else (values[1], values[2], values[7])
        if alignment < 16384 or file_offset % 16384 != virtual % 16384:
            raise RuntimeError(f"ELF does not support 16 KB pages: {path}")
        alignments.append(alignment)
    if not alignments:
        raise RuntimeError(f"ELF has no LOAD segments: {path}")
    return alignments


def ndk_fingerprint(ndk: Path, expected_revision: str) -> dict:
    properties = ndk / "source.properties"
    parsed = {}
    for line in properties.read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            name, value = line.split("=", 1)
            parsed[name.strip()] = value.strip()
    if parsed.get("Pkg.Revision") != expected_revision:
        raise RuntimeError(f"Expected Android NDK {expected_revision}")
    binaries = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    return {
        "revision": expected_revision,
        "source_properties_sha256": digest(properties),
        "tools": {name: digest(binaries / name) for name in NDK_NATIVE_TOOLS},
    }


def native_inputs(lock: dict, abi: str, ndk: dict) -> dict:
    return {"identity": identity(lock), "abi": abi, "ndk": ndk}


def read_native_cache(work: Path, expected: dict) -> dict | None:
    folder = work / "native" / expected["abi"]
    receipt = folder / "receipt.json"
    if not receipt.is_file():
        return None
    try:
        saved = json.loads(receipt.read_text())
    except (ValueError, OSError) as error:
        raise RuntimeError(f"Corrupted native cache receipt; remove {folder} and rebuild") from error
    if not isinstance(saved, dict):
        raise RuntimeError(f"Corrupted native cache receipt; remove {folder} and rebuild")
    if any(saved.get(name) != value for name, value in expected.items()):
        return None
    libraries = saved.get("libraries")
    names = {"libonnxruntime.so", "libonnxruntime4j_jni.so"}
    if not isinstance(libraries, dict) or set(libraries) != names:
        raise RuntimeError(f"Corrupted native cache contents; remove {folder} and rebuild")
    for name, meta in libraries.items():
        if not isinstance(meta, dict) or not isinstance(meta.get("sha256"), str):
            raise RuntimeError(f"Corrupted native cache contents; remove {folder} and rebuild")
        path = folder / name
        require_hash(path, meta["sha256"])
        elf_alignment(path, expected["abi"])
    return libraries


def prepare_native_build_tree(out: Path, expected: dict) -> None:
    stamp = out / "voice-runtime-input.json"
    try:
        previous = json.loads(stamp.read_text()) if stamp.is_file() else None
    except (ValueError, OSError):
        previous = None
    # CMake does not reliably notice a compiler replaced in place. Only resume
    # partial objects when their source/config and exact NDK inputs still match.
    if out.exists() and previous != expected:
        shutil.rmtree(out)
    out.mkdir(parents=True, exist_ok=True)
    stamp.write_text(json.dumps(expected, sort_keys=True) + "\n")


def build_native(args, lock: dict, abi: str, cmake: Path, env: dict, protoc: Path, headers: Path,
                 ndk: dict | None = None) -> dict:
    if abi not in ABI_ELF:
        raise RuntimeError(f"Unsupported Android ABI: {abi}")
    toolchain = ndk if ndk is not None else ndk_fingerprint(args.android_ndk, lock["ndk_version"])
    expected = native_inputs(lock, abi, toolchain)
    cached = read_native_cache(args.work_dir, expected)
    if cached is not None:
        print(f"Reusing verified native runtime for {abi}", flush=True)
        return cached
    ort = args.work_dir / "source/onnxruntime"
    out = args.work_dir / "build" / abi
    prepare_native_build_tree(out, expected)
    maps = f"-ffile-prefix-map={args.work_dir}=/build/voice-runtime -ffile-prefix-map={args.android_ndk}=/opt/android-ndk-r28"
    defines = ["CMAKE_CXX_SCAN_FOR_MODULES=OFF", "onnxruntime_BUILD_UNIT_TESTS=OFF", "CMAKE_DISABLE_FIND_PACKAGE_Git=ON",
               "CMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384", "CMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
               f"CMAKE_C_FLAGS={maps}", f"CMAKE_CXX_FLAGS={maps}", f"VOICE_JNI_HEADERS={headers}",
               *dependency_defines(args, lock)]
    command = [env["VOICE_RUNTIME_PYTHON"], ort / "tools/ci_build/build.py", "--config", "Release", "--build_dir", out,
               "--update", "--build", "--parallel", str(args.jobs), "--skip_tests", "--skip_submodule_sync",
               "--cmake_generator", "Ninja", "--cmake_path", cmake,
               "--android", "--android_sdk_path", args.android_sdk, "--android_ndk_path", args.android_ndk,
               "--android_abi", abi, "--android_api", str(lock["android_api"]), "--build_shared_lib", "--build_java",
               "--enable_lto", *profile_flags(lock), "--include_ops_by_config", HERE / "required_operators.config",
               "--path_to_protoc_exe", protoc, "--cmake_extra_defines", *defines]
    run(command, env=env)
    strip = args.android_ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
    result = {}
    for name in ("libonnxruntime.so", "libonnxruntime4j_jni.so"):
        source = out / "Release" / name
        if not source.is_file():
            raise RuntimeError(f"Native build omitted {source}")
        target = args.work_dir / "native" / abi / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        run([strip, "--strip-unneeded", target], env=env)
        result[name] = {"sha256": digest(target), "bytes": target.stat().st_size, "load_alignments": elf_alignment(target, abi)}
    (args.work_dir / "native" / abi / "receipt.json").write_text(json.dumps({**expected, "libraries": result}, indent=2) + "\n")
    return result


def package_aar(template: Path, output: Path, work: Path, lock: dict, model: Path | None = None) -> dict:
    validate_runtime_asset(model, lock)
    require_hash(template, lock["template"]["sha256"])
    entries = {}
    with zipfile.ZipFile(template) as z:
        for entry in z.infolist():
            if not entry.is_dir() and not entry.filename.startswith("jni/"):
                entries[entry.filename] = z.read(entry)
    if "classes.jar" not in entries or "AndroidManifest.xml" not in entries:
        raise RuntimeError("Pinned template lacks Java classes or manifest")
    packaged_ndk = None
    for abi in lock["abis"]:
        receipt = json.loads((work / "native" / abi / "receipt.json").read_text())
        if receipt["identity"] != identity(lock) or receipt.get("abi") != abi or set(receipt["libraries"]) != {"libonnxruntime.so", "libonnxruntime4j_jni.so"}:
            raise RuntimeError(f"Stale native inputs for {abi}")
        ndk = receipt.get("ndk")
        if not isinstance(ndk, dict) or ndk.get("revision") != lock["ndk_version"] or set(ndk.get("tools", {})) != set(NDK_NATIVE_TOOLS):
            raise RuntimeError(f"Missing native toolchain provenance for {abi}")
        if packaged_ndk is not None and ndk != packaged_ndk:
            raise RuntimeError("Cannot package native runtimes built with different NDK toolchains")
        packaged_ndk = ndk
        for name, meta in receipt["libraries"].items():
            native = work / "native" / abi / name
            require_hash(native, meta["sha256"])
            elf_alignment(native, abi)
            entries[f"jni/{abi}/{name}"] = native.read_bytes()
    ort = work / "source/onnxruntime"
    for name in ("LICENSE", "ThirdPartyNotices.txt"):
        entries[f"assets/third_party/onnxruntime-reduced-{name}"] = (ort / name).read_bytes()
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + ".partial")
    # Stored entries avoid host zlib-version differences. AGP compresses the final APK.
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_STORED) as z:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, time.gmtime(lock["source_date_epoch"])[:6])
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_STORED
            z.writestr(info, entries[name])
    temporary.replace(output)
    return {"sha256": digest(output), "bytes": output.stat().st_size,
            "classes_jar_sha256": hashlib.sha256(entries["classes.jar"]).hexdigest(), "native_entries": sorted(x for x in entries if x.startswith("jni/"))}


def build(args, lock: dict) -> None:
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "AMD64"):
        raise RuntimeError("The locked Android toolchain requires Linux x86_64")
    check_prepared(args, lock)
    require_hash(args.template_aar, lock["template"]["sha256"])
    ndk = ndk_fingerprint(args.android_ndk, lock["ndk_version"])
    cmake, env = tools_environment(args, lock)
    protoc = build_protoc(args, lock, cmake, env)
    headers = generate_headers(args, env)
    abis = args.abi or lock["abis"]
    if set(abis) - set(lock["abis"]):
        raise RuntimeError("Unsupported Android ABI")
    for abi in abis:
        build_native(args, lock, abi, cmake, env, protoc, headers, ndk)
    if args.abi:
        print("Scoped native build completed; packaging requires the complete four-ABI set", flush=True)
        return
    result = package_aar(args.template_aar, args.output, args.work_dir, lock, args.model)
    result.update(identity=identity(lock), model_sha256=lock["runtime_asset"]["sha256"],
                  source_model_sha256=lock["source_model"]["sha256"], operators_sha256=lock["operators_sha256"],
                  build_profile=lock["build_profile"])
    args.output.with_suffix(args.output.suffix + ".receipt.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "build", "verify"))
    parser.add_argument("--work-dir", required=True, type=Path)
    parser.add_argument("--tools-dir", type=Path, default=DEFAULT_TOOLS)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--offline", action="store_true", help="Prepare using cached inputs only")
    parser.add_argument("--template-aar", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--android-sdk", type=Path)
    parser.add_argument("--android-ndk", type=Path)
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--abi", action="append", help="Native-only probe; never packages a partial AAR")
    args = parser.parse_args()
    for name in ("work_dir", "tools_dir", "model", "template_aar", "output", "android_sdk", "android_ndk"):
        value = getattr(args, name)
        if value is not None:
            setattr(args, name, value.expanduser().resolve())
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    if args.work_dir in (Path("/"), Path.home(), HERE.parents[1]):
        parser.error("Use a dedicated generated work directory")
    lock = read_lock()
    if args.command == "prepare":
        prepare(args, lock)
    elif args.command == "verify":
        check_prepared(args, lock)
        print("Prepared inputs match all source, model and tool locks")
    else:
        for name in ("template_aar", "output", "android_sdk", "android_ndk"):
            if getattr(args, name) is None:
                parser.error(f"build requires --{name.replace('_', '-')}")
        build(args, lock)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, OSError, ValueError, KeyError) as error:
        print(f"Runtime build failed: {error}", file=sys.stderr)
        sys.exit(1)
