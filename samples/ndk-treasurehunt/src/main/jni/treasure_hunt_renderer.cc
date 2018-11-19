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

#include "treasure_hunt_renderer.h"  // NOLINT
#include "treasure_hunt_shaders.h"  // NOLINT

#include <android/log.h>
#include <assert.h>
#include <stdlib.h>
#include <cmath>
#include <random>

#include "vr/gvr/capi/include/gvr_version.h"

#include "wombat_android_test/wombat_android_test.h"

#define LOG_TAG "TreasureHuntCPP"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define CHECK(condition)                                                   \
  if (!(condition)) {                                                      \
    LOGE("*** CHECK FAILED at %s:%d: %s", __FILE__, __LINE__, #condition); \
    abort();                                                               \
  }

namespace {

static const uint64_t kPredictionTimeWithoutVsyncNanos = 50000000;

// Multiply two matrices.
static gvr::Mat4f MatrixMul(const gvr::Mat4f& matrix1,
                            const gvr::Mat4f& matrix2) {
  gvr::Mat4f result;
  for (int i = 0; i < 4; ++i) {
    for (int j = 0; j < 4; ++j) {
      result.m[i][j] = 0.0f;
      for (int k = 0; k < 4; ++k) {
        result.m[i][j] += matrix1.m[i][k] * matrix2.m[k][j];
      }
    }
  }
  return result;
}


// Multiplies both X coordinates of the rectangle by the given width and both Y
// coordinates by the given height.
static gvr::Rectf ModulateRect(const gvr::Rectf& rect, float width,
                               float height) {
  gvr::Rectf result = {rect.left * width, rect.right * width,
                       rect.bottom * height, rect.top * height};
  return result;
}

// Given the size of a texture in pixels and a rectangle in UV coordinates,
// computes the corresponding rectangle in pixel coordinates.
static gvr::Recti CalculatePixelSpaceRect(const gvr::Sizei& texture_size,
                                          const gvr::Rectf& texture_rect) {
  const float width = static_cast<float>(texture_size.width);
  const float height = static_cast<float>(texture_size.height);
  const gvr::Rectf rect = ModulateRect(texture_rect, width, height);
  const gvr::Recti result = {
      static_cast<int>(rect.left), static_cast<int>(rect.right),
      static_cast<int>(rect.bottom), static_cast<int>(rect.top)};
  return result;
}


static void CheckGLError(const char* label) {
  int gl_error = glGetError();
  if (gl_error != GL_NO_ERROR) {
    LOGW("GL error @ %s: %d", label, gl_error);
    // Crash immediately to make OpenGL errors obvious.
    //abort();
  }
}

// Computes a texture size that has approximately half as many pixels. This is
// equivalent to scaling each dimension by approximately sqrt(2)/2.
static gvr::Sizei HalfPixelCount(const gvr::Sizei& in) {
  // Scale each dimension by sqrt(2)/2 ~= 7/10ths.
  gvr::Sizei out;
  out.width = (7 * in.width) / 10;
  out.height = (7 * in.height) / 10;
  return out;
}

}  // anonymous namespace

TreasureHuntRenderer::TreasureHuntRenderer(
    gvr_context* gvr_context, std::unique_ptr<gvr::AudioApi> gvr_audio_api)
    : gvr_api_(gvr::GvrApi::WrapNonOwned(gvr_context)),
      gvr_audio_api_(std::move(gvr_audio_api)),
      viewport_left_(gvr_api_->CreateBufferViewport()),
      viewport_right_(gvr_api_->CreateBufferViewport()),
      reticle_render_size_{128, 128},
      light_pos_world_space_({0.0f, 2.0f, 0.0f, 1.0f}),
      audio_source_id_(-1),
      success_source_id_(-1),
      gvr_controller_api_(nullptr),
      gvr_viewer_type_(gvr_api_->GetViewerType()),
      face_(wombat_android_test::create({}), wombat_android_test::destroy),
      last_update(gvr::GvrApi::GetTimePointNow()) {
  ResumeControllerApiAsNeeded();

  LOGD("Built with GVR version: %s", GVR_SDK_VERSION_STRING);
  if (gvr_viewer_type_ == GVR_VIEWER_TYPE_CARDBOARD) {
    LOGD("Viewer type: CARDBOARD");
  } else if (gvr_viewer_type_ == GVR_VIEWER_TYPE_DAYDREAM) {
    LOGD("Viewer type: DAYDREAM");
  } else {
    LOGE("Unexpected viewer type.");
  }
}

TreasureHuntRenderer::~TreasureHuntRenderer() {
  // Join the audio initialization thread in case it still exists.
  if (audio_initialization_thread_.joinable()) {
    audio_initialization_thread_.join();
  }
}

void TreasureHuntRenderer::InitializeGl() {
  gvr_api_->InitializeGl();
  multiview_enabled_ = gvr_api_->IsFeatureSupported(GVR_FEATURE_MULTIVIEW);
  LOGD(multiview_enabled_ ? "Using multiview." : "Not using multiview.");

  render_size_ =
      HalfPixelCount(gvr_api_->GetMaximumEffectiveRenderTargetSize());
  std::vector<gvr::BufferSpec> specs;

  specs.push_back(gvr_api_->CreateBufferSpec());
  specs[0].SetColorFormat(GVR_COLOR_FORMAT_RGBA_8888);
  specs[0].SetDepthStencilFormat(GVR_DEPTH_STENCIL_FORMAT_DEPTH_16);
  specs[0].SetSamples(2);

  // With multiview, the distortion buffer is a texture array with two layers
  // whose width is half the display width.
  if (multiview_enabled_) {
    gvr::Sizei half_size = { render_size_.width / 2, render_size_.height };
    specs[0].SetMultiviewLayers(2);
    specs[0].SetSize(half_size);
  } else {
    specs[0].SetSize(render_size_);
  }

  specs.push_back(gvr_api_->CreateBufferSpec());
  specs[1].SetSize(reticle_render_size_);
  specs[1].SetColorFormat(GVR_COLOR_FORMAT_RGBA_8888);
  specs[1].SetDepthStencilFormat(GVR_DEPTH_STENCIL_FORMAT_NONE);
  specs[1].SetSamples(1);
  swapchain_.reset(new gvr::SwapChain(gvr_api_->CreateSwapChain(specs)));

  viewport_list_.reset(
      new gvr::BufferViewportList(gvr_api_->CreateEmptyBufferViewportList()));

  face_->init({});
}

void TreasureHuntRenderer::ResumeControllerApiAsNeeded() {
  switch (gvr_viewer_type_) {
    case GVR_VIEWER_TYPE_CARDBOARD:
      gvr_controller_api_.reset();
      break;
    case GVR_VIEWER_TYPE_DAYDREAM:
      if (!gvr_controller_api_) {
        // Initialized controller api.
        gvr_controller_api_.reset(new gvr::ControllerApi);
        CHECK(gvr_controller_api_);
        CHECK(gvr_controller_api_->Init(gvr::ControllerApi::DefaultOptions(),
                                        gvr_api_->cobj()));
      }
      gvr_controller_api_->Resume();
      break;
    default:
      LOGE("unexpected viewer type.");
      break;
  }
}

void TreasureHuntRenderer::ProcessControllerInput() {
  if (gvr_viewer_type_ == GVR_VIEWER_TYPE_CARDBOARD) return;
  const int old_status = gvr_controller_state_.GetApiStatus();
  const int old_connection_state = gvr_controller_state_.GetConnectionState();

  // Read current controller state.
  gvr_controller_state_.Update(*gvr_controller_api_);

  // Print new API status and connection state, if they changed.
  if (gvr_controller_state_.GetApiStatus() != old_status ||
      gvr_controller_state_.GetConnectionState() != old_connection_state) {
    LOGD("TreasureHuntApp: controller API status: %s, connection state: %s",
         gvr_controller_api_status_to_string(
             gvr_controller_state_.GetApiStatus()),
         gvr_controller_connection_state_to_string(
             gvr_controller_state_.GetConnectionState()));
  }

  // Trigger click event if app/click button is clicked.
  if (gvr_controller_state_.GetButtonDown(GVR_CONTROLLER_BUTTON_APP) ||
      gvr_controller_state_.GetButtonDown(GVR_CONTROLLER_BUTTON_CLICK)) {
      OnFlyStateChanged(false);
  }
}

void TreasureHuntRenderer::DrawFrame() {
  ProcessControllerInput();

  PrepareFramebuffer();
  gvr::Frame frame = swapchain_->AcquireFrame();

  // A client app does its rendering here.
  gvr::ClockTimePoint target_time = gvr::GvrApi::GetTimePointNow();
  target_time.monotonic_system_time_nanos += kPredictionTimeWithoutVsyncNanos;
  gvr::BufferViewport* viewport[2] = {
    &viewport_left_,
    &viewport_right_,
  };
  head_view_ = gvr_api_->GetHeadSpaceFromStartSpaceTransform(target_time);
  viewport_list_->SetToRecommendedBufferViewports();

  for (int eye = 0; eye < 2; ++eye) {
    const gvr::Eye gvr_eye = eye == 0 ? GVR_LEFT_EYE : GVR_RIGHT_EYE;
    const gvr::Mat4f eye_from_head = gvr_api_->GetEyeFromHeadMatrix(gvr_eye);
    eye_views[eye] = MatrixMul(eye_from_head, head_view_);

    viewport_list_->GetBufferViewport(eye, viewport[eye]);

    if (multiview_enabled_) {
      const gvr_rectf fullscreen = { 0, 1, 0, 1 };
      viewport[eye]->SetSourceUv(fullscreen);
      viewport[eye]->SetSourceLayer(eye);
      viewport_list_->SetBufferViewport(eye, *viewport[eye]);
    }
  }

  gvr::ClockTimePoint time_now = gvr::GvrApi::GetTimePointNow();

  wombat_android_test::update_args_t args;
  args.head_matrix = (float *) head_view_.m;
  args.time_delta = static_cast<float>(
          (time_now.monotonic_system_time_nanos - last_update.monotonic_system_time_nanos) * 1e-9
  );

  face_->update(args);
  last_update = time_now;

  frame.BindBuffer(0);
  int32_t fbo_id = frame.GetFramebufferObject(0);

  {
    uint32_t constexpr num_passes = 2;
    wombat_android_test::render_pass_args_t passes[num_passes];
      gvr::Mat4f eye_matrices[num_passes];

    for (uint32_t eye_index = 0; eye_index < num_passes; ++eye_index)
    {
      auto &render_args = passes[eye_index];

      ViewType view = eye_index == 0 ? kLeftView : kRightView;

        const gvr::BufferViewport& viewport =
                view == kLeftView ? viewport_left_ : viewport_right_;

        const auto fov = viewport.GetSourceFov();
        render_args.fov_rect = {fov.left, fov.right, fov.top, fov.bottom};

        eye_matrices[eye_index] = gvr_api_->GetEyeFromHeadMatrix(eye_index == 0 ? GVR_LEFT_EYE : GVR_RIGHT_EYE);

        render_args.eye_matrix = (float *)(eye_matrices[eye_index].m);

        const gvr::Recti pixel_rect =
                CalculatePixelSpaceRect(render_size_, viewport.GetSourceUv());
        render_args.vp_rect = {pixel_rect.left, pixel_rect.bottom,
                               pixel_rect.right - pixel_rect.left,
                               pixel_rect.top - pixel_rect.bottom};
    }


    wombat_android_test::render_args_t ra;
    ra.num_passes = num_passes;
    ra.passes = passes;
    ra.fbo_id = fbo_id;

    face_->render(ra);
  }


  frame.Unbind();
  frame.Submit(*viewport_list_, head_view_);

  CheckGLError("onDrawFrame");
}

wombat_android_test::iface *TreasureHuntRenderer::GetWombatInterface()
{
    return face_.get();
}

void TreasureHuntRenderer::PrepareFramebuffer() {
  // Because we are using 2X MSAA, we can render to half as many pixels and
  // achieve similar quality.
  const gvr::Sizei recommended_size =
      HalfPixelCount(gvr_api_->GetMaximumEffectiveRenderTargetSize());
  if (render_size_.width != recommended_size.width ||
      render_size_.height != recommended_size.height) {
    // We need to resize the framebuffer. Note that multiview uses two texture
    // layers, each with half the render width.
    gvr::Sizei framebuffer_size = recommended_size;
    if (multiview_enabled_) {
      framebuffer_size.width /= 2;
    }
    swapchain_->ResizeBuffer(0, framebuffer_size);
    render_size_ = recommended_size;
  }
}

void TreasureHuntRenderer::OnFlyStateChanged(bool flying_forward) {
  face_->set_flying_forward(flying_forward);
}

void TreasureHuntRenderer::OnPause() {
  gvr_api_->PauseTracking();
  gvr_audio_api_->Pause();
  if (gvr_controller_api_) gvr_controller_api_->Pause();
}

void TreasureHuntRenderer::OnResume() {
  gvr_api_->ResumeTracking();
  gvr_api_->RefreshViewerProfile();
  gvr_audio_api_->Resume();
  gvr_viewer_type_ = gvr_api_->GetViewerType();
  ResumeControllerApiAsNeeded();
}

int TreasureHuntRenderer::GetStreamingTextureID() {
  return face_->get_streaming_texture_id();
}

int TreasureHuntRenderer::GetStreamingTextureWidth() {
  return face_->get_streaming_texture_width();
}

int TreasureHuntRenderer::GetStreamingTextureHeight() {
  return face_->get_streaming_texture_height();
}

