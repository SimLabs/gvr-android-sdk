/* Copyright 2017 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <android/log.h>
#include <jni.h>

#include <memory>

#include "treasure_hunt_renderer.h"  // NOLINT
#include "vr/gvr/capi/include/gvr.h"
#include "vr/gvr/capi/include/gvr_audio.h"
#include "wombat_android_test/wombat_android_test.h"


#define JNI_METHOD(return_type, method_name) \
  JNIEXPORT return_type JNICALL              \
      Java_ru_simlabs_vr_MainActivity_##method_name

namespace {

inline jlong jptr(TreasureHuntRenderer *native_treasure_hunt) {
  return reinterpret_cast<intptr_t>(native_treasure_hunt);
}

inline TreasureHuntRenderer *native(jlong ptr) {
  return reinterpret_cast<TreasureHuntRenderer *>(ptr);
}
}  // anonymous namespace

extern "C" {

JNI_METHOD(jlong, nativeCreateRenderer)
(JNIEnv *env, jclass clazz, jobject class_loader, jobject android_context,
 jlong native_gvr_api) {
  std::unique_ptr<gvr::AudioApi> audio_context(new gvr::AudioApi);
  audio_context->Init(env, android_context, class_loader,
                      GVR_AUDIO_RENDERING_BINAURAL_HIGH_QUALITY);

  return jptr(
      new TreasureHuntRenderer(reinterpret_cast<gvr_context *>(native_gvr_api),
                               std::move(audio_context)));
}

JNI_METHOD(void, nativeDestroyRenderer)
(JNIEnv *env, jclass clazz, jlong native_treasure_hunt) {
  delete native(native_treasure_hunt);
}

JNI_METHOD(void, nativeInitializeGl)
(JNIEnv *env, jobject obj, jlong native_treasure_hunt) {
  native(native_treasure_hunt)->InitializeGl();
}

JNI_METHOD(void, nativeBeforeTextureUpdate)
(JNIEnv *env, jobject obj, jlong native_treasure_hunt) {
    native(native_treasure_hunt)->GetWombatInterface()->before_texture_update();
}

JNI_METHOD(void, nativeAfterTextureUpdate)
(JNIEnv *env, jobject obj, jlong native_treasure_hunt) {
    native(native_treasure_hunt)->GetWombatInterface()->after_texture_update();
}

JNI_METHOD(void, nativeDrawFrame)
(JNIEnv *env, jobject obj, jlong native_treasure_hunt, jint frame_id) {
  native(native_treasure_hunt)->DrawFrame(frame_id);
}

JNI_METHOD(void, nativeOnFlyStateChanged)
(JNIEnv *env, jobject obj, jlong native_treasure_hunt, jboolean flying_forward) {
  native(native_treasure_hunt)->OnFlyStateChanged(flying_forward);
}

JNI_METHOD(void, nativeOnPause)
(JNIEnv *env, jobject obj, jlong native_treasure_hunt) {
  native(native_treasure_hunt)->OnPause();
}

JNI_METHOD(void, nativeOnResume)
(JNIEnv *env, jobject obj, jlong native_treasure_hunt) {
  native(native_treasure_hunt)->OnResume();
}

// Streaming
JNI_METHOD(jint, nativeGetStreamingTextureID)
(JNIEnv *env, jobject obj, jlong native_treasure_hunt) {
  return native(native_treasure_hunt)->GetStreamingTextureID();
}

JNI_METHOD(jint, nativeGetStreamingTextureWidth)
(JNIEnv *env, jobject obj, jlong native_treasure_hunt) {
  return native(native_treasure_hunt)->GetStreamingTextureWidth();
}

JNI_METHOD(jint, nativeGetStreamingTextureHeight)
(JNIEnv *env, jobject obj, jlong native_treasure_hunt) {
  return native(native_treasure_hunt)->GetStreamingTextureHeight();
}

JNI_METHOD(jstring, nativeGetHostAddress)
(JNIEnv *env, jobject obj, jlong native_treasure_hunt) {
    char const *cstr = native(native_treasure_hunt)->GetWombatInterface()->get_host_address();
    return env->NewStringUTF(cstr);
}

JNI_METHOD(void, nativeOnTextMessage)
(JNIEnv *env, jobject obj, jlong native_treasure_hunt, jint id, jstring args) {
    char const *args_cstr = env->GetStringUTFChars(args, nullptr);

    native(native_treasure_hunt)->GetWombatInterface()->on_text_message(id, args_cstr);

    env->ReleaseStringUTFChars(args, args_cstr);
}

JNI_METHOD(void, nativeOnConnected)
(JNIEnv *env, jobject obj, jlong native_treasure_hunt) {
    native(native_treasure_hunt)->GetWombatInterface()->on_connected();
}

JNI_METHOD(void, nativeEnqueueFrame)
(JNIEnv *env, jobject obj, jlong native_treasure_hunt, jint frameID, jint width, jint height, jbyteArray userData) {
    jsize const len = env->GetArrayLength(userData);

    jbyte *ptr = new jbyte[len]();
    env->GetByteArrayRegion(userData, 0, len, ptr);

    auto const wombat_interface = native(native_treasure_hunt)->GetWombatInterface();

    const wombat_android_test::frame_data_t frame_data {
            frameID,
            wombat_interface->get_streaming_texture_id(),
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            nullptr,
            reinterpret_cast<uint8_t const *>(ptr),
            static_cast<uint32_t>(len)
    };

    wombat_interface->enqueue_frame(frame_data);
}
}  // extern "C"
