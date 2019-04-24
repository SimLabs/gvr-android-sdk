/* Copyright 2018 Google Inc. All rights reserved.
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

#ifndef VR_GVR_CAPI_INCLUDE_GVR_BETA_H_
#define VR_GVR_CAPI_INCLUDE_GVR_BETA_H_

#include "vr/gvr/capi/include/gvr_types.h"

#ifdef __cplusplus
extern "C" {
#endif

/// @defgroup Beta GVR Beta APIs
///
/// @brief These are Beta APIs that are likely to change between
/// releases.
///
/// This file contains Daydream Beta APIs. Beta APIs are not subject to the
/// usual deprecation process. Beta APIs are expected to change and may be
/// removed entirely in a future version without warning. Beta APIs might also
/// use functionality that is not available to all users or on all devices.

/// @{

// ************************************************************************** //
// *                     Experimental 6DoF Controllers                      * //
// ************************************************************************** //

/// Configuration for a specific controller
typedef enum {
  /// Used when controller configuration is unknown.
  GVR_BETA_CONTROLLER_CONFIGURATION_UNKNOWN = 0,
  /// Daydream (3DoF) controller. This controller might have simulated position
  /// data.
  GVR_BETA_CONTROLLER_CONFIGURATION_3DOF = 1,
  /// Experiemntal 6DoF controller.
  GVR_BETA_CONTROLLER_CONFIGURATION_6DOF = 2,
} gvr_beta_controller_configuration_type;

/// Tracking state for experimental 6DoF controllers.
typedef enum {
  /// Indicates whether the controller's tracking status is unknown.
  GVR_BETA_CONTROLLER_TRACKING_STATUS_FLAG_TRACKING_UNKNOWN = (1U << 0),
  /// Indicates whether the controller is tracking in full 6DoF mode.
  GVR_BETA_CONTROLLER_TRACKING_STATUS_FLAG_TRACKING_NOMINAL = (1U << 1),
  /// Indicates whether the controller is occluded. For optically tracked
  /// controllers, occlusion happens for a brief time as the user blocks the
  /// tracking system with their hand or the other controller. The position of
  /// the controller will be clamped to the last known value.
  GVR_BETA_CONTROLLER_TRACKING_STATUS_FLAG_OCCLUDED = (1U << 2),
  /// Indicates whether the controller is out of field of view of the tracking
  /// system. The position of the controller is no longer accurate and should be
  /// ignored.
  GVR_BETA_CONTROLLER_TRACKING_STATUS_FLAG_OUT_OF_FOV = (1U << 3),
} gvr_beta_controller_tracking_status_flags;

/// Returns the configuration of the given controller.
///
/// @param controller_context Controller API context.
/// @param state The controller to query.
/// @return the current controller configuration of type
/// |gvr_beta_controller_configuration_type|.
int32_t gvr_beta_controller_get_configuration_type(
    const gvr_controller_context* controller_context,
    const gvr_controller_state* state);

/// Returns the tracking status of the controller. The status is an OR'd
/// combination of GVR_BETA_CONTROLLER_TRACKING_STATUS_FLAG_* flags.
///
/// @param state The controller state to get the tracking status from.
/// @return The set of flags representing the current the tracking status.
int32_t gvr_beta_controller_state_get_tracking_status(
    const gvr_controller_state* state);

// ************************************************************************** //
// *                     Experimental See-through                           * //
// ************************************************************************** //

typedef struct gvr_beta_see_through_config_ gvr_beta_see_through_config;

// The different supported appearances that determine how camera see-through
// frames will be drawn.
typedef enum {
  // The default behavior where no camera frames will be drawn.
  GVR_BETA_SEE_THROUGH_CAMERA_MODE_DISABLED = 0,

  // The camera's image will be shown unaltered. For the devices with a
  // grayscale camera this will be a grayscale image.
  GVR_BETA_SEE_THROUGH_CAMERA_MODE_RAW_IMAGE = 1,

  // The monochrome image will be artifically colored with darker areas given a
  // blue tint, middle areas given an orange tint, and bright areas becoming
  // pure white.
  GVR_BETA_SEE_THROUGH_CAMERA_MODE_TONEMAPPED = 2,
} gvr_beta_see_through_camera_mode;

// The different scene types that an app can have. These control where the scene
// is rendered from. Generally a virtual scene the scene should be rendered from
// the users' eyes while an augmented scene should be rendered from the camera's
// position to match the see-through images. More details should be found at the
// eye offset doc.
// TODO(b/122441265): Add a link to the public docs before release.
typedef enum {
  // Virtual scene type. This represents a scene either composed entirely of
  // virtual objects with no see-through or a scene that is primarily virtual
  // with windows to see the background see-through.
  GVR_BETA_SEE_THROUGH_SCENE_TYPE_VIRTUAL_SCENE = 0,

  // Augmented scene type. This represents a scene that is primarily see-through
  // with sparse virtual objects placed on or inside the real environment. This
  // is ignored if the corresponding camera mode is disabled.
  GVR_BETA_SEE_THROUGH_SCENE_TYPE_AUGMENTED_SCENE = 1,
} gvr_beta_see_through_scene_type;

// Creates a gvr_beta_see_through_config instance. It can then be set as the
// active config through gvr_beta_set_see_through_config.
gvr_beta_see_through_config* gvr_beta_see_through_config_create(
    gvr_context* gvr);
// Destroys a gvr_beta_see_through_config instance created by
// gvr_beta_see_through_config_create.
void gvr_beta_see_through_config_destroy(gvr_beta_see_through_config** config);
// Sets the camera mode for the given see-through config which determines the
// see-through's appearance. camera_mode should be a
// gvr_beta_see_through_camera_mode. This will control whether see-through is
// shown on screen and how it will be colored.
void gvr_beta_see_through_config_set_camera_mode(
    gvr_beta_see_through_config* config, int32_t camera_mode);
// Sets the scene type on the given see-through config. scene_type should be a
// gvr_beta_see_through_scene_type. This changes the behavior of
// gvr_get_eye_from_head_matrix and
// gvr_get_head_space_from_start_space_transform based on the scene type.
void gvr_beta_see_through_config_set_scene_type(
    gvr_beta_see_through_config* config, int32_t scene_type);

// The main function for enabling/disabling and configuring See-through.
// This function should only be called from the same thread as gvr_frame_submit.
//
// This will both:
// 1. Set the camera see-through appearance. If this appearance is anything
// other than GVR_BETA_SEE_THROUGH_CAMERA_MODE_DISABLED then a buffer viewport
// will be added for each eye whenever a frame is submitted. This will cause the
// camera frames to be drawn as a background layer, with the rendering style
// determined by the camera mode. Alpha blending will work as normal, so if the
// app's bottom layer has alpha 1 then the camera frame will be occluded but if
// the alpha is 0 the camera frame will show through.
//
// 2. If the scene type is GVR_BETA_SEE_THROUGH_SCENE_TYPE_AUGMENTED_SCENE,
// it will change the behavior of gvr_get_head_space_from_start_space_transform
// to offset the input timestamp and gvr_get_eye_from_head_matrix to include the
// camera offset.
//
// @param config The gvr_beta_see_through_config struct contains the
// gvr_beta_see_through_camera_mode enum value and the
// gvr_beta_see_through_scene_type enum value.
void gvr_beta_set_see_through_config(gvr_context* context,
                                     const gvr_beta_see_through_config* config);

/// @}

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // VR_GVR_CAPI_INCLUDE_GVR_BETA_H_
