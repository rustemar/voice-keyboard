# Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
# Licensed under the MIT License.
# Android JNI target adapted from ONNX Runtime 1.24.3 cmake/onnxruntime_java.cmake.
# Java bytecode and AAR metadata are retained from the matching Maven Central artifact.
if(NOT ANDROID)
  message(FATAL_ERROR "This JNI target is for the Android runtime build")
endif()
set(JAVA_ROOT "${REPO_ROOT}/java")
if(NOT DEFINED VOICE_JNI_HEADERS OR NOT IS_DIRECTORY "${VOICE_JNI_HEADERS}")
  message(FATAL_ERROR "Generate JNI headers before configuring the native runtime")
endif()
# FP16 Conv uses this unchanged CPU helper even when contrib kernels are disabled.
if(onnxruntime_DISABLE_CONTRIB_OPS)
  target_sources(onnxruntime_providers PRIVATE
    "${ONNXRUNTIME_ROOT}/contrib_ops/cpu/fused_activation.cc")
endif()
file(GLOB onnxruntime4j_native_src
  "${JAVA_ROOT}/src/main/native/*.c"
  "${JAVA_ROOT}/src/main/native/*.h"
  "${REPO_ROOT}/include/onnxruntime/core/session/*.h"
  "${REPO_ROOT}/orttraining/orttraining/training_api/include/*.h")
onnxruntime_add_shared_library_module(onnxruntime4j_jni ${onnxruntime4j_native_src})
set_property(TARGET onnxruntime4j_jni PROPERTY C_STANDARD 11)
onnxruntime_add_include_to_target(onnxruntime4j_jni onnxruntime_session)
target_include_directories(onnxruntime4j_jni PRIVATE
  "${REPO_ROOT}/include"
  "${REPO_ROOT}/orttraining/orttraining/training_api/include"
  "${VOICE_JNI_HEADERS}")
target_link_libraries(onnxruntime4j_jni PUBLIC onnxruntime)
# Strip local LTO symbols before the linker computes the deterministic build ID.
target_link_options(onnxruntime4j_jni PRIVATE "-Wl,-s")
