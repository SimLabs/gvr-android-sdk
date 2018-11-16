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

#ifndef TREASUREHUNT_APP_SRC_MAIN_JNI_TREASUREHUNTRENDERER_H_  // NOLINT
#define TREASUREHUNT_APP_SRC_MAIN_JNI_TREASUREHUNTRENDERER_H_  // NOLINT

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <jni.h>

#include <memory>
#include <string>
#include <thread>  // NOLINT
#include <vector>

#include "vr/gvr/capi/include/gvr.h"
#include "vr/gvr/capi/include/gvr_audio.h"
#include "vr/gvr/capi/include/gvr_controller.h"
#include "vr/gvr/capi/include/gvr_types.h"
#include "world_layout_data.h"  // NOLINT

#include "wombat_android_test/wombat_android_test_fwd.h"

class TreasureHuntRenderer {
 public:
  /**
   * Create a TreasureHuntRenderer using a given |gvr_context|.
   *
   * @param gvr_api The (non-owned) gvr_context.
   * @param gvr_audio_api The (owned) gvr::AudioApi context.
   */
  TreasureHuntRenderer(gvr_context* gvr_context,
                       std::unique_ptr<gvr::AudioApi> gvr_audio_api);

  /**
   * Destructor.
   */
  ~TreasureHuntRenderer();

  /**
   * Initialize any GL-related objects. This should be called on the rendering
   * thread with a valid GL context.
   */
  void InitializeGl();

  /**
   * Draw the TreasureHunt scene. This should be called on the rendering thread.
   */
  void DrawFrame();

  /**
   * Hide the cube if it's being targeted.
   */
  void OnFlyStateChanged(bool flying_forward);

  /**
   * Pause head tracking.
   */
  void OnPause();

  /**
   * Resume head tracking, refreshing viewer parameters if necessary.
   */
  void OnResume();

  /**
   * Get GL texture ID used to store streaming frames
   */
  int GetStreamingTextureID();

  /**
   * Get streaming texture width
   */
  int GetStreamingTextureWidth();


  /**
   * Get streaming texture height
   */
  int GetStreamingTextureHeight();

    wombat_android_test::iface *GetWombatInterface();

 private:

  /*
   * Prepares the GvrApi framebuffer for rendering, resizing if needed.
   */
  void PrepareFramebuffer();

  /**
   * Converts a raw text file, saved as a resource, into an OpenGL ES shader.
   *
   * @param type  The type of shader we will be creating.
   * @param resId The resource ID of the raw text file.
   * @return The shader object handler.
   */
  enum ViewType {
    kLeftView,
    kRightView,
    kMultiview
  };


  /**
   * Process the controller input.
   *
   * The controller state is updated with the latest touch pad, button clicking
   * information, connection state and status of the controller. A log message
   * is reported if the controller status or connection state changes. A click
   * event is triggered if a click on app/click button is detected.
   */
  void ProcessControllerInput();

  /*
   * Resume the controller api if needed.
   *
   * If the viewer type is cardboard, set the controller api pointer to null.
   * If the viewer type is daydream, initialize the controller api as needed and
   * resume.
   */
  void ResumeControllerApiAsNeeded();

  std::unique_ptr<gvr::GvrApi> gvr_api_;
  std::unique_ptr<gvr::AudioApi> gvr_audio_api_;
  std::unique_ptr<gvr::BufferViewportList> viewport_list_;
  std::unique_ptr<gvr::SwapChain> swapchain_;
  gvr::BufferViewport viewport_left_;
  gvr::BufferViewport viewport_right_;

  const gvr::Sizei reticle_render_size_;

  const std::array<float, 4> light_pos_world_space_;

  gvr::Mat4f head_view_;
  gvr::Sizei render_size_;

  bool multiview_enabled_;

  gvr::AudioSourceId audio_source_id_;

  gvr::AudioSourceId success_source_id_;

  std::thread audio_initialization_thread_;

  // Controller API entry point.
  std::unique_ptr<gvr::ControllerApi> gvr_controller_api_;

  // The latest controller state (updated once per frame).
  gvr::ControllerState gvr_controller_state_;

  gvr::ViewerType gvr_viewer_type_;

  std::shared_ptr<wombat_android_test::iface> face_;
  gvr::Mat4f eye_views[2];
  gvr::ClockTimePoint last_update;
};

#endif  // TREASUREHUNT_APP_SRC_MAIN_JNI_TREASUREHUNTRENDERER_H_  // NOLINT
