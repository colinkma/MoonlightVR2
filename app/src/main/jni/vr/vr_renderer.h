#ifndef VR_RENDERER_H_
#define VR_RENDERER_H_

#include <array>
#include <cstdint>
#include <vector>

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <jni.h>

#include "cardboard.h"
#include "util.h"

namespace moonlight_vr {

class VrMoonlightApp {
 public:
  static constexpr int kCurvatureModeFlat = 0;
  static constexpr int kCurvatureModeTvCinema = 1;
  static constexpr int kCurvatureModeGamingScreen = 2;
  VrMoonlightApp(JavaVM* vm, jobject activity, jobject asset_manager);
  ~VrMoonlightApp();

  jint OnSurfaceCreated(JNIEnv* env);
  void OnSurfaceChanged(int width, int height);
  void SetTextureTransform(const float* transform);
  void SetVideoAspectRatio(float aspectRatio);
  void SetLensDistortionStrength(float strength);
  float OnDrawFrame();
  void OnPause();
  void OnResume();
  void SetScreenDistance(float meters);
  float GetScreenDistance() const;
  void SetDisplayMode(int mode);
  void SetViewportOrientation(int androidRotation);
  void SetMagneticHeading(float headingRadians, float headingAccuracyRadians,
                          int sensorAccuracy);
  void SetWindowLocked(bool locked);
  void SetScreenSize(float sizeMultiplier);
  void AdjustScreenDistance(float deltaMeters);
  void AdjustScreenSize(float deltaMultiplier);
  void AdjustScreenPosition(float deltaX, float deltaY);
  void AdjustScreenRotation(float deltaRadians);
  void SetCurvatureMode(int mode);
  void SetCurvatureAmount(float percent);
  void SetHorizontalCurvature(float percent);
  void SetVerticalCurvature(float percent);
  void SetSkyboxEnabled(bool enabled);
  void SetSkyboxTexture(GLuint textureId);
  void SetSkyboxBrightness(float brightness);
  void SetLensScale(float scale);
  void AdjustLeftLensOffset(float deltaX);
  void AdjustRightLensOffset(float deltaX);
  void SetCameraTexture(GLuint textureId);
  void SetCameraTextureTransform(const float* transform);
  void SetCameraEnabled(bool enabled);
  void SetSpatialControlsAutoHide(bool enabled);
  float GetCurvatureAmount() const;
  float GetHorizontalCurvature() const;
  float GetVerticalCurvature() const;
  float GetScreenSize() const;
  void RecenterView();

 private:
  void ResetRenderTarget();
  void UpdateRenderTarget();
  bool UpdateDeviceParams();
  void RenderVideoToTexture();
  void RenderSpatialVideoToTexture();
  void UpdateSpatialGaze();
  void DrawSpatialControls(const Matrix4x4& viewProjection);
  void DestroyCardboardResources();
  void UpdateModelMatrix();
  void UpdateScreenGeometry();
  void TransformLensMesh(std::vector<float>& vertices, float scale,
                         float offset_x);
  void ApplyLensDistortionStrength(const CardboardMesh& source,
                                   std::vector<float>& uvs) const;
  void UpdateCameraModelMatrix();

  JavaVM* java_vm_;
  jobject activity_;
  jobject java_asset_mgr_;
  AAssetManager* asset_mgr_;

  CardboardLensDistortion* lens_distortion_;
  CardboardHeadTracker* head_tracker_;
  CardboardDistortionRenderer* distortion_renderer_;

  CardboardEyeTextureDescription left_eye_texture_description_;
  CardboardEyeTextureDescription right_eye_texture_description_;

  bool screen_params_changed_;
  int display_mode_ = 0;
  CardboardViewportOrientation viewport_orientation_ = kLandscapeLeft;
  // Spatial window is world-anchored by default. Edit mode temporarily lets
  // the user select and reposition it with head rotation.
  bool window_locked_ = true;
  bool window_lock_pending_ = false;
  bool recenter_anchor_yaw_only_pending_ = false;
  float recenter_anchor_vertical_offset_m_ = -0.07f;
  bool window_edit_mode_ = false;
  bool window_dragging_ = false;
  Matrix4x4 drag_start_head_matrix_;
  Matrix4x4 drag_start_window_matrix_;
  int64_t window_last_motion_nano_ = 0;
  float recenter_dwell_seconds_ = 0.0f;
  int64_t last_recenter_frame_nano_ = 0;
  bool recenter_action_armed_ = true;
  bool head_pose_valid_ = false;
  // Optional geomagnetic heading reference. When Android's rotation-vector
  // sensor is reliable, it slowly corrects the yaw component that gravity
  // cannot observe in Cardboard's 3DoF gyro/accelerometer tracker.
  float magnetic_heading_radians_ = 0.0f;
  float magnetic_heading_accuracy_radians_ = 1000.0f;
  int magnetic_heading_sensor_accuracy_ = 0;
  int64_t magnetic_heading_timestamp_nano_ = 0;
  bool magnetic_yaw_reference_valid_ = false;
  float magnetic_heading_reference_radians_ = 0.0f;
  float cardboard_yaw_reference_radians_ = 0.0f;
  float magnetic_yaw_correction_radians_ = 0.0f;
  int64_t last_magnetic_yaw_update_nano_ = 0;
  int64_t magnetic_yaw_stationary_since_nano_ = 0;
  Matrix4x4 previous_raw_head_world_matrix_;
  bool raw_head_world_valid_ = false;
  std::array<float, 3> last_head_position_ = {0.0f, 0.0f, 0.0f};
  Quatf last_head_orientation_ = Quatf(0.0f, 0.0f, 0.0f, 1.0f);
  // Camera look-down activation is measured relative to the pose when VR mode
  // starts, not the phone's absolute mounting angle in the viewer.
  Quatf head_pitch_reference_orientation_ = Quatf(0.0f, 0.0f, 0.0f, 1.0f);
  bool head_pitch_reference_valid_ = false;
  Matrix4x4 head_world_matrix_;
  Matrix4x4 previous_reticle_head_matrix_;
  bool reticle_head_pose_valid_ = false;
  int64_t reticle_last_motion_nano_ = 0;
  Matrix4x4 window_anchor_matrix_;
  Matrix4x4 toolbar_anchor_matrix_;
  bool toolbar_anchor_valid_ = false;
  float head_pitch_degrees_ = 0.0f;
  uint64_t head_pose_frame_count_ = 0;
  bool device_params_changed_;
  int screen_width_;
  int screen_height_;

  GLuint video_program_;
  GLuint line_program_;
  GLuint spatial_ui_program_ = 0;
  GLint spatial_ui_pos_attrib_ = -1;
  GLint spatial_ui_mvp_uniform_ = -1;
  GLint spatial_ui_color_uniform_ = -1;
  static constexpr float kGazeDwellSeconds = 0.8f;
  static constexpr float kWindowSelectDwellSeconds = 3.0f;
  static constexpr float kWindowIdleReleaseSeconds = 1.0f;
  static constexpr float kRecenterDwellSeconds = 2.0f;
  int gaze_target_ = -1;
  int previous_gaze_target_ = -2;
  float gaze_dwell_seconds_ = 0.0f;
  int64_t last_gaze_frame_nano_ = 0;
  bool gaze_action_armed_ = true;
  float window_select_dwell_seconds_ = 0.0f;
  int64_t last_window_select_frame_nano_ = 0;
  bool window_select_armed_ = true;
  bool spatial_controls_auto_hide_ = true;
  bool spatial_controls_visible_ = true;
  int64_t last_spatial_controls_gaze_nano_ = 0;
  GLuint video_texture_;
  GLuint render_texture_;
  GLuint framebuffer_;
  GLuint depth_renderbuffer_;
  GLint position_attrib_;
  GLint texcoord_attrib_;
  GLint texture_transform_uniform_;
  GLint sampler_uniform_;
  GLint mvp_uniform_;
  GLint line_pos_attrib_;
  GLint line_mvp_uniform_;
  GLuint skybox_program_;
  GLint skybox_pos_attrib_;
  GLint skybox_vp_uniform_;
  GLint skybox_sampler_uniform_;
  GLint skybox_brightness_uniform_;
  GLuint skybox_texture_;

  float texture_transform_[16];
  Matrix4x4 model_matrix_;
  float screen_distance_meters_;
  float screen_size_multiplier_;
  int curvature_mode_;
  float curvature_amount_percent_;
  float horizontal_curvature_percent_;
  float vertical_curvature_percent_;
  std::vector<GLfloat> screen_vertices_;
  std::vector<GLfloat> screen_texcoords_;
  bool using_curved_geometry_;
  int curved_rows_;
  int curved_vertices_per_strip_;
  bool skybox_enabled_ = true;
  float skybox_brightness_ = 1.0f;
  bool geometry_dirty_;
  float screen_yaw_radians_ = 0.0f;
  float screen_pitch_radians_ = 0.0f;
  float screen_rotation_radians_ = 0.0f;
  float lens_scale_ = 1.0f;
  float lens_distortion_strength_ = 0.70f;
  float video_aspect_ratio_ = 16.0f / 9.0f;
  float left_lens_offset_x_ = 0.0f;
  float right_lens_offset_x_ = 0.0f;
  bool lens_mesh_dirty_ = true;
  GLuint camera_texture_ = 0;
  bool camera_enabled_ = false;
  float camera_texture_transform_[16];
  Matrix4x4 camera_model_matrix_;
};

}  // namespace moonlight_vr

#endif  // VR_RENDERER_H_
