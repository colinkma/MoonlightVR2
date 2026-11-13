#include "vr_renderer.h"

#include <jni.h>
#include <android/asset_manager_jni.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <iterator>
#include <string>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/log.h>

#include "cardboard.h"
#include "qr_code.h"

namespace moonlight_vr {

namespace {

constexpr float kScreenWidthMeters = 2.2f;
constexpr float kScreenAspectRatio = 16.0f / 9.0f;
constexpr float kMinScreenDistanceMeters = 1.0f;
constexpr float kMaxScreenDistanceMeters = 4.0f;
constexpr float kDefaultScreenDistanceMeters = 2.0f;
constexpr float kMinLensScale = 0.5f;
constexpr float kMaxLensScale = 3.0f;


constexpr float kMaxHorizontalCurvatureAngle = 3.14159265358979323846f;
constexpr float kMaxVerticalCurvatureAngle = 1.57079632679489661923f;
constexpr int kCurvedGridCols = 64;
constexpr int kCurvedGridRowsTv = 32;
constexpr int kCurvedGridRowsGaming = 2;
constexpr float kMinCurvatureAngle = 0.0001f;

const GLfloat kQuadVertices[] = {
    -1.f, -1.f, 0.f,
     1.f, -1.f, 0.f,
    -1.f,  1.f, 0.f,
     1.f,  1.f, 0.f,
};

const GLfloat kQuadTexCoords[] = {
     0.f, 0.f,
     1.f, 0.f,
     0.f, 1.f,
     1.f, 1.f,
};

const char kVideoVertexShader[] =
    "attribute vec4 a_Position;"              "\n"
    "attribute vec2 a_TexCoord;"             "\n"
    "uniform mat4 u_TextureTransform;"       "\n"
    "uniform mat4 u_MVP;"                    "\n"
    "varying vec2 v_TexCoord;"               "\n"
    "void main() {"                           "\n"
    "  v_TexCoord = (u_TextureTransform * vec4(a_TexCoord, 0.0, 1.0)).xy;" "\n"
    "  gl_Position = u_MVP * a_Position;"     "\n"
    "}";

const char kVideoFragmentShader[] =
    "#extension GL_OES_EGL_image_external : require" "\n"
    "precision mediump float;"                 "\n"
    "uniform samplerExternalOES u_Texture;"    "\n"
    "varying vec2 v_TexCoord;"                 "\n"
    "void main() {"                            "\n"
    "  gl_FragColor = texture2D(u_Texture, v_TexCoord);" "\n"
    "}";

const char kLineVertexShader[] =
    "attribute vec4 a_Position;"              "\n"
    "uniform mat4 u_MVP;"                    "\n"
    "void main() {"                           "\n"
    "  gl_Position = u_MVP * a_Position;"     "\n"
    "}";

const char kLineFragmentShader[] =
    "precision mediump float;"                "\n"
    "void main() {"                           "\n"
    "  gl_FragColor = vec4(0.627, 0.627, 0.627, 1.0);" "\n"
    "}";

const GLfloat kLineVertices[] = {
    0.f, -1.f, 0.f,
    0.f,  1.f, 0.f,
};

const char kSkyboxVertexShader[] =
    "attribute vec4 a_Position;"           "\n"
    "uniform mat4 u_VP;"                  "\n"
    "varying vec3 v_TexCoord;"             "\n"
    "void main() {"                        "\n"
    "  v_TexCoord = a_Position.xyz;"       "\n"
    "  vec4 pos = u_VP * vec4(a_Position.xyz, 1.0);" "\n"
    "  gl_Position = pos.xyww;"           "\n"
    "}";

const char kSkyboxFragmentShader[] =
    "precision mediump float;"                               "\n"
    "varying vec3 v_TexCoord;"                               "\n"
    "uniform samplerCube u_SkyboxTexture;"                   "\n"
    "uniform float u_Brightness;"                            "\n"
    "void main() {"                                          "\n"
    "  vec3 dir = normalize(v_TexCoord);"                    "\n"
    "  vec4 color = textureCube(u_SkyboxTexture, dir);"      "\n"
    "  float brightness = clamp(u_Brightness, 0.0, 1.0);"    "\n"
    "  gl_FragColor = vec4(color.rgb * brightness, color.a);" "\n"
    "}";

const GLfloat kSkyboxVertices[] = {
    -1.0f,  1.0f, -1.0f,  -1.0f, -1.0f, -1.0f,   1.0f, -1.0f, -1.0f,
     1.0f, -1.0f, -1.0f,   1.0f,  1.0f, -1.0f,  -1.0f,  1.0f, -1.0f,
    -1.0f, -1.0f,  1.0f,  -1.0f, -1.0f, -1.0f,  -1.0f,  1.0f, -1.0f,
    -1.0f,  1.0f, -1.0f,  -1.0f,  1.0f,  1.0f,  -1.0f, -1.0f,  1.0f,
     1.0f, -1.0f, -1.0f,   1.0f, -1.0f,  1.0f,   1.0f,  1.0f,  1.0f,
     1.0f,  1.0f,  1.0f,   1.0f,  1.0f, -1.0f,   1.0f, -1.0f, -1.0f,
    -1.0f, -1.0f,  1.0f,  -1.0f,  1.0f,  1.0f,   1.0f,  1.0f,  1.0f,
     1.0f,  1.0f,  1.0f,   1.0f, -1.0f,  1.0f,  -1.0f, -1.0f,  1.0f,
    -1.0f,  1.0f, -1.0f,   1.0f,  1.0f, -1.0f,   1.0f,  1.0f,  1.0f,
     1.0f,  1.0f,  1.0f,  -1.0f,  1.0f,  1.0f,  -1.0f,  1.0f, -1.0f,
    -1.0f, -1.0f, -1.0f,  -1.0f, -1.0f,  1.0f,   1.0f, -1.0f, -1.0f,
     1.0f, -1.0f, -1.0f,  -1.0f, -1.0f,  1.0f,   1.0f, -1.0f,  1.0f
};


const char kSpatialUiVertexShader[] =
    "attribute vec4 a_Position;\n"
    "uniform mat4 u_MVP;\n"
    "void main() { gl_Position = u_MVP * a_Position; }";
const char kSpatialUiFragmentShader[] =
    "precision mediump float;\n"
    "uniform vec4 u_Color;\n"
    "void main() { gl_FragColor = u_Color; }";
const GLfloat kSpatialUiQuad[] = {
    -0.5f,-0.5f,0.f, 0.5f,-0.5f,0.f, -0.5f,0.5f,0.f,
    -0.5f,0.5f,0.f, 0.5f,-0.5f,0.f, 0.5f,0.5f,0.f
};
bool InvertMatrix(const Matrix4x4& input, Matrix4x4& output) {
  float a[4][8] = {};
  for (int r=0;r<4;++r) { for (int c=0;c<4;++c) a[r][c]=input.m[r][c]; a[r][r+4]=1.f; }
  for (int c=0;c<4;++c) {
    int pivot=c;
    for (int r=c+1;r<4;++r) if (std::fabs(a[r][c])>std::fabs(a[pivot][c])) pivot=r;
    if (std::fabs(a[pivot][c])<1e-7f) return false;
    if (pivot!=c) for (int k=0;k<8;++k) std::swap(a[pivot][k],a[c][k]);
    const float d=a[c][c]; for (int k=0;k<8;++k) a[c][k]/=d;
    for (int r=0;r<4;++r) if (r!=c) { const float f=a[r][c]; for (int k=0;k<8;++k) a[r][k]-=f*a[c][k]; }
  }
  output=Matrix4x4();
  for (int r=0;r<4;++r) for (int c=0;c<4;++c) output.m[r][c]=a[r][c+4];
  return true;
}

}  // namespace

VrMoonlightApp::VrMoonlightApp(JavaVM* vm, jobject activity, jobject asset_manager)
    : java_vm_(vm),
      activity_(nullptr),
      java_asset_mgr_(nullptr),
      asset_mgr_(nullptr),
      lens_distortion_(nullptr),
      head_tracker_(nullptr),
      distortion_renderer_(nullptr),
      screen_params_changed_(false),
      device_params_changed_(true),
      screen_width_(0),
      screen_height_(0),
      video_program_(0),
      line_program_(0),
      video_texture_(0),
      render_texture_(0),
      framebuffer_(0),
      depth_renderbuffer_(0),
      position_attrib_(-1),
      texcoord_attrib_(-1),
       texture_transform_uniform_(-1),
       sampler_uniform_(-1),
       mvp_uniform_(-1),
       line_pos_attrib_(-1),
       line_mvp_uniform_(-1),
       skybox_program_(0),
       skybox_pos_attrib_(-1),
       skybox_vp_uniform_(-1),
       skybox_sampler_uniform_(-1),
       skybox_texture_(0),
       model_matrix_(),
      screen_distance_meters_(kDefaultScreenDistanceMeters),
      screen_size_multiplier_(1.0f),
      curvature_mode_(VrMoonlightApp::kCurvatureModeFlat),
      curvature_amount_percent_(50.f),
      horizontal_curvature_percent_(50.f),
      vertical_curvature_percent_(50.f),
      using_curved_geometry_(false),
      curved_rows_(0),
      curved_vertices_per_strip_(0),
      geometry_dirty_(true) {
  std::fill(std::begin(texture_transform_), std::end(texture_transform_), 0.f);
  texture_transform_[0] = texture_transform_[5] = texture_transform_[10] =
      texture_transform_[15] = 1.f;

  JNIEnv* env = nullptr;
  int result = java_vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
  if (result != JNI_OK) {
    // If the environment doesn't exist, attach the current thread
    result = java_vm_->AttachCurrentThread(&env, nullptr);
    if (result != JNI_OK) {
      // Log error if attaching fails
      return;
    }
  }

  if (env != nullptr) {
    activity_ = env->NewGlobalRef(activity);
    java_asset_mgr_ = env->NewGlobalRef(asset_manager);
    asset_mgr_ = AAssetManager_fromJava(env, asset_manager);
  }

  if (activity_ && java_asset_mgr_) {
    // Cardboard initialization provides the viewer profile used for optical
    // distortion correction. No head tracker is created in fixed SBS mode.
    Cardboard_initializeAndroid(java_vm_, activity_);
  }

  for (int i = 0; i < 4; ++i) {
    head_world_matrix_.m[i][i] = 1.0f;
    window_anchor_matrix_.m[i][i] = 1.0f;
  }

  UpdateModelMatrix();
  UpdateScreenGeometry();
  UpdateCameraModelMatrix();

  std::fill(std::begin(camera_texture_transform_), std::end(camera_texture_transform_), 0.f);
  camera_texture_transform_[0] = camera_texture_transform_[5] =
      camera_texture_transform_[10] = camera_texture_transform_[15] = 1.f;
}

VrMoonlightApp::~VrMoonlightApp() {
  DestroyCardboardResources();
  if (head_tracker_ != nullptr) {
    CardboardHeadTracker_destroy(head_tracker_);
    head_tracker_ = nullptr;
  }

  if (render_texture_) {
    glDeleteTextures(1, &render_texture_);
  }
  if (video_texture_) {
    glDeleteTextures(1, &video_texture_);
  }
  if (line_program_) {
    glDeleteProgram(line_program_);
  }
  if (spatial_ui_program_) {
    glDeleteProgram(spatial_ui_program_);
  }
  if (skybox_program_) {
    glDeleteProgram(skybox_program_);
  }
  if (framebuffer_) {
    glDeleteFramebuffers(1, &framebuffer_);
  }
  if (depth_renderbuffer_) {
    glDeleteRenderbuffers(1, &depth_renderbuffer_);
  }
  JNIEnv* env = nullptr;
  if (java_vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
    if (activity_) {
      env->DeleteGlobalRef(activity_);
      activity_ = nullptr;
    }
    if (java_asset_mgr_) {
      env->DeleteGlobalRef(java_asset_mgr_);
      java_asset_mgr_ = nullptr;
    }
  }
}

jint VrMoonlightApp::OnSurfaceCreated(JNIEnv* env) {
  if (video_program_ == 0) {
    GLuint vertex_shader = LoadGLShader(GL_VERTEX_SHADER, kVideoVertexShader);
    GLuint fragment_shader = LoadGLShader(GL_FRAGMENT_SHADER, kVideoFragmentShader);
    video_program_ = glCreateProgram();
    glAttachShader(video_program_, vertex_shader);
    glAttachShader(video_program_, fragment_shader);
    glLinkProgram(video_program_);
    glDeleteShader(vertex_shader);
    glDeleteShader(fragment_shader);
    position_attrib_ = glGetAttribLocation(video_program_, "a_Position");
    texcoord_attrib_ = glGetAttribLocation(video_program_, "a_TexCoord");
    texture_transform_uniform_ = glGetUniformLocation(video_program_,
                                                      "u_TextureTransform");
    mvp_uniform_ = glGetUniformLocation(video_program_, "u_MVP");
    sampler_uniform_ = glGetUniformLocation(video_program_, "u_Texture");
  }

  if (line_program_ == 0) {
    GLuint line_vs = LoadGLShader(GL_VERTEX_SHADER, kLineVertexShader);
    GLuint line_fs = LoadGLShader(GL_FRAGMENT_SHADER, kLineFragmentShader);
    line_program_ = glCreateProgram();
    glAttachShader(line_program_, line_vs);
    glAttachShader(line_program_, line_fs);
    glLinkProgram(line_program_);
    glDeleteShader(line_vs);
    glDeleteShader(line_fs);
    line_pos_attrib_ = glGetAttribLocation(line_program_, "a_Position");
    line_mvp_uniform_ = glGetUniformLocation(line_program_, "u_MVP");
  }


  if (spatial_ui_program_ == 0) {
    GLuint vs=LoadGLShader(GL_VERTEX_SHADER,kSpatialUiVertexShader);
    GLuint fs=LoadGLShader(GL_FRAGMENT_SHADER,kSpatialUiFragmentShader);
    if (vs && fs) {
      spatial_ui_program_=glCreateProgram();
      glAttachShader(spatial_ui_program_,vs); glAttachShader(spatial_ui_program_,fs);
      glLinkProgram(spatial_ui_program_);
      GLint linked=GL_FALSE; glGetProgramiv(spatial_ui_program_,GL_LINK_STATUS,&linked);
      if (!linked) { glDeleteProgram(spatial_ui_program_); spatial_ui_program_=0; }
    }
    if (vs) glDeleteShader(vs); if (fs) glDeleteShader(fs);
    if (spatial_ui_program_) {
      spatial_ui_pos_attrib_=glGetAttribLocation(spatial_ui_program_,"a_Position");
      spatial_ui_mvp_uniform_=glGetUniformLocation(spatial_ui_program_,"u_MVP");
      spatial_ui_color_uniform_=glGetUniformLocation(spatial_ui_program_,"u_Color");
    }
  }

  if (skybox_program_ == 0) {
    GLuint skybox_vs = LoadGLShader(GL_VERTEX_SHADER, kSkyboxVertexShader);
    GLuint skybox_fs = LoadGLShader(GL_FRAGMENT_SHADER, kSkyboxFragmentShader);
    if (skybox_vs != 0 && skybox_fs != 0) {
      skybox_program_ = glCreateProgram();
      glAttachShader(skybox_program_, skybox_vs);
      glAttachShader(skybox_program_, skybox_fs);
      glLinkProgram(skybox_program_);
      GLint linked = GL_FALSE;
      glGetProgramiv(skybox_program_, GL_LINK_STATUS, &linked);
      if (linked == GL_FALSE) {
        GLint length = 0;
        glGetProgramiv(skybox_program_, GL_INFO_LOG_LENGTH, &length);
        if (length > 0) {
          std::string info(length, '\0');
          glGetProgramInfoLog(skybox_program_, length, nullptr, info.data());
          VR_LOGE("Skybox program link failed: %s", info.c_str());
        }
        glDeleteProgram(skybox_program_);
        skybox_program_ = 0;
      }
      glDeleteShader(skybox_vs);
      glDeleteShader(skybox_fs);
    }

    if (skybox_program_ != 0) {
      skybox_pos_attrib_ = glGetAttribLocation(skybox_program_, "a_Position");
      skybox_vp_uniform_ = glGetUniformLocation(skybox_program_, "u_VP");
      skybox_sampler_uniform_ = glGetUniformLocation(skybox_program_, "u_SkyboxTexture");
      skybox_brightness_uniform_ = glGetUniformLocation(skybox_program_, "u_Brightness");
    }
  }

  if (video_texture_ == 0) {
    glGenTextures(1, &video_texture_);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, video_texture_);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
  }

  ResetRenderTarget();

  return static_cast<jint>(video_texture_);
}

void VrMoonlightApp::OnSurfaceChanged(int width, int height) {
  screen_width_ = width;
  screen_height_ = height;
  screen_params_changed_ = true;
  UpdateRenderTarget();
}

void VrMoonlightApp::SetTextureTransform(const float* transform) {
  if (transform != nullptr) {
    std::copy(transform, transform + 16, texture_transform_);
  }
}

void VrMoonlightApp::SetVideoAspectRatio(float aspect_ratio) {
  if (std::isfinite(aspect_ratio) && aspect_ratio > 0.1f && aspect_ratio < 10.0f) {
    video_aspect_ratio_ = aspect_ratio;
    UpdateModelMatrix();
  }
}

void VrMoonlightApp::SetDisplayMode(int mode) {
  head_pitch_reference_valid_ = false;
  if (mode == 1) {
    if (head_tracker_ == nullptr) {
      head_tracker_ = CardboardHeadTracker_create();
      if (head_tracker_ != nullptr) {
        // Match the official Cardboard NDK sample to suppress high-frequency
        // sensor noise without adding frame-by-frame pose accumulation.
        CardboardHeadTracker_setLowPassFilter(head_tracker_, 6);
      }
    }
    if (head_tracker_ != nullptr) {
      display_mode_ = 1;
      toolbar_anchor_valid_ = false;
      spatial_controls_visible_ = true;
      last_spatial_controls_gaze_nano_ = 0;
      // Start as a spatially anchored floating window; the first valid pose
      // captures the anchor on the GL thread without a one-frame jump.
      window_locked_ = true;
      window_lock_pending_ = true;
      window_edit_mode_ = false;
      window_dragging_ = false;
      window_select_dwell_seconds_ = 0.f;
      window_select_armed_ = true;
      recenter_dwell_seconds_ = 0.f;
      recenter_action_armed_ = true;
      CardboardHeadTracker_resume(head_tracker_);
    } else {
      display_mode_ = 0;
      window_locked_ = false;
      window_lock_pending_ = false;
      VR_LOGE("Unable to create Cardboard head tracker; falling back to fixed SBS mode");
    }
  } else {
    display_mode_ = 0;
    toolbar_anchor_valid_ = false;
    window_edit_mode_ = false;
    window_dragging_ = false;
    if (head_tracker_ != nullptr) {
      CardboardHeadTracker_pause(head_tracker_);
    }
  }
}

void VrMoonlightApp::SetViewportOrientation(int android_rotation) {
  switch (android_rotation) {
    case 0:
      viewport_orientation_ = kPortrait;
      break;
    case 1:
      viewport_orientation_ = kLandscapeLeft;
      break;
    case 2:
      viewport_orientation_ = kPortraitUpsideDown;
      break;
    case 3:
    default:
      viewport_orientation_ = kLandscapeRight;
      break;
  }
}

void VrMoonlightApp::SetWindowLocked(bool locked) {
  if (locked && !window_locked_) {
    if (head_pose_valid_) {
      window_anchor_matrix_ = head_world_matrix_;
      window_lock_pending_ = false;
    } else {
      window_lock_pending_ = true;
    }
  } else if (!locked) {
    window_lock_pending_ = false;
  }
  window_locked_ = locked;
}

void VrMoonlightApp::SetLensDistortionStrength(float strength) {
  lens_distortion_strength_ = std::clamp(strength, 0.0f, 1.0f);
  lens_mesh_dirty_ = true;
}

float VrMoonlightApp::OnDrawFrame() {
  if (screen_width_ == 0 || screen_height_ == 0 || !UpdateDeviceParams()) {
    return head_pitch_degrees_;
  }

  if (display_mode_ == 1 && head_tracker_ != nullptr) {
    float position[3] = {0.0f, 0.0f, 0.0f};
    float orientation[4] = {0.0f, 0.0f, 0.0f, 1.0f};
    // Match the official Cardboard NDK sample's 50 ms prediction horizon.
    CardboardHeadTracker_getPose(head_tracker_, GetBootTimeNano() + 50000000LL,
                                 viewport_orientation_, position, orientation);

    // Reject invalid sensor samples rather than allowing a zero/NaN quaternion
    // to poison the view and world-anchor matrices.
    const float orientation_norm_sq =
        orientation[0] * orientation[0] + orientation[1] * orientation[1] +
        orientation[2] * orientation[2] + orientation[3] * orientation[3];
    if (!std::isfinite(orientation_norm_sq) || orientation_norm_sq < 1.0e-8f) {
      if (!head_pose_valid_) {
        return head_pitch_degrees_;
      }
      VR_LOGE("Cardboard returned invalid head pose quaternion");
    } else {
      const float inverse_norm = 1.0f / std::sqrt(orientation_norm_sq);
      // CardboardHeadTracker_getPose() already converts the sensor pose
      // into the requested viewport coordinate system. Remapping X/Z here
      // would rotate pitch into roll a second time and make the window drift.
      last_head_orientation_ = Quatf(
          orientation[0] * inverse_norm, orientation[1] * inverse_norm,
          orientation[2] * inverse_norm, orientation[3] * inverse_norm);
    }

    // Cardboard provides 3DoF orientation, but its translation is not a
    // room-tracked 6DoF position. Keep the anchor rotational to avoid drift.
    last_head_position_ = {0.0f, 0.0f, 0.0f};
    ++head_pose_frame_count_;
    if ((head_pose_frame_count_ % 120) == 0) {
      __android_log_print(ANDROID_LOG_INFO, VR_LOG_TAG,
          "Head pose #%llu q=(%.4f,%.4f,%.4f,%.4f) locked=%d pending=%d",
          static_cast<unsigned long long>(head_pose_frame_count_),
          last_head_orientation_.x, last_head_orientation_.y,
          last_head_orientation_.z, last_head_orientation_.w,
          window_locked_ ? 1 : 0, window_lock_pending_ ? 1 : 0);
    }

    // Cardboard's returned quaternion is the head_view rotation (the
    // world-to-camera transform), as used by Google's NDK sample. The
    // camera-to-world transform used for world anchors, gaze and reticle is
    // therefore its conjugate/inverse.
    const Quatf inverse_head_orientation(
        -last_head_orientation_.x, -last_head_orientation_.y,
        -last_head_orientation_.z, last_head_orientation_.w);
    head_world_matrix_ = inverse_head_orientation.ToMatrix();
    head_world_matrix_.m[0][3] = 0.0f;
    head_world_matrix_.m[1][3] = 0.0f;
    head_world_matrix_.m[2][3] = 0.0f;
    head_pose_valid_ = true;
    if (!toolbar_anchor_valid_) {
      toolbar_anchor_matrix_ = head_world_matrix_;
      toolbar_anchor_valid_ = true;
    }
    if (window_locked_ && window_lock_pending_) {
      window_anchor_matrix_ = head_world_matrix_;
      window_lock_pending_ = false;
    }

    // Establish a neutral pose when spatial mode starts. Camera activation
    // must respond to the user's relative head movement, not to the phone's
    // absolute mounting angle or the device's natural screen orientation.
    if (!head_pitch_reference_valid_) {
      head_pitch_reference_orientation_ = last_head_orientation_;
      head_pitch_reference_valid_ = true;
    }
    const Quatf inverse_current_orientation(
        -last_head_orientation_.x, -last_head_orientation_.y,
        -last_head_orientation_.z, last_head_orientation_.w);
    const Quatf relative_camera_orientation =
        head_pitch_reference_orientation_ * inverse_current_orientation;
    const Matrix4x4 relative_camera_world = relative_camera_orientation.ToMatrix();
    // A downward head rotation produces a negative pitch value.
    const float sin_pitch = std::clamp(-relative_camera_world.m[1][2],
                                       -1.0f, 1.0f);
    head_pitch_degrees_ = std::asin(sin_pitch) * 180.0f /
                          3.14159265358979323846f;
    RenderSpatialVideoToTexture();
  } else {
    head_pitch_degrees_ = 0.0f;
    RenderVideoToTexture();
  }

  left_eye_texture_description_.texture = render_texture_;
  left_eye_texture_description_.left_u = 0.0f;
  left_eye_texture_description_.right_u = 0.5f;
  left_eye_texture_description_.top_v = 1.0f;
  left_eye_texture_description_.bottom_v = 0.0f;

  right_eye_texture_description_.texture = render_texture_;
  right_eye_texture_description_.left_u = 0.5f;
  right_eye_texture_description_.right_u = 1.0f;
  right_eye_texture_description_.top_v = 1.0f;
  right_eye_texture_description_.bottom_v = 0.0f;

  CardboardDistortionRenderer_renderEyeToDisplay(
      distortion_renderer_, 0, 0, 0, screen_width_, screen_height_,
      &left_eye_texture_description_, &right_eye_texture_description_);
  return head_pitch_degrees_;
}

void VrMoonlightApp::OnPause() {
  if (head_tracker_ != nullptr) {
    CardboardHeadTracker_pause(head_tracker_);
  }
}

void VrMoonlightApp::OnResume() {
  head_pitch_reference_valid_ = false;
  device_params_changed_ = true;
  if (display_mode_ == 1 && head_tracker_ != nullptr) {
    CardboardHeadTracker_resume(head_tracker_);
  }
}

void VrMoonlightApp::ResetRenderTarget() {
  if (render_texture_) {
    glDeleteTextures(1, &render_texture_);
    render_texture_ = 0;
  }
  if (framebuffer_) {
    glDeleteFramebuffers(1, &framebuffer_);
    framebuffer_ = 0;
  }
  if (depth_renderbuffer_) {
    glDeleteRenderbuffers(1, &depth_renderbuffer_);
    depth_renderbuffer_ = 0;
  }
}

void VrMoonlightApp::UpdateRenderTarget() {
  ResetRenderTarget();
  if (screen_width_ == 0 || screen_height_ == 0) {
    return;
  }

  glGenTextures(1, &render_texture_);
  glBindTexture(GL_TEXTURE_2D, render_texture_);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, screen_width_, screen_height_, 0,
               GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

  glGenRenderbuffers(1, &depth_renderbuffer_);
  glBindRenderbuffer(GL_RENDERBUFFER, depth_renderbuffer_);
  glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, screen_width_,
                        screen_height_);

  glGenFramebuffers(1, &framebuffer_);
  glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                         render_texture_, 0);
  glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                            GL_RENDERBUFFER, depth_renderbuffer_);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);

  left_eye_texture_description_.texture = render_texture_;
  left_eye_texture_description_.left_u = 0.f;
  left_eye_texture_description_.right_u = 0.5f;
  left_eye_texture_description_.top_v = 1.f;
  left_eye_texture_description_.bottom_v = 0.f;

  right_eye_texture_description_ = left_eye_texture_description_;
  right_eye_texture_description_.left_u = 0.5f;
  right_eye_texture_description_.right_u = 1.f;
}

bool VrMoonlightApp::UpdateDeviceParams() {
  bool hardware_recreated = false;

  if (screen_params_changed_ || device_params_changed_) {
    uint8_t* params = nullptr;
    int size = 0;
    CardboardQrCode_getSavedDeviceParams(&params, &size);
    bool free_params = false;
    if (size == 0) {
      CardboardQrCode_getCardboardV1DeviceParams(&params, &size);
    } else {
      free_params = true;
    }

    if (screen_width_ == 0 || screen_height_ == 0 || size == 0) {
      if (free_params && params != nullptr) {
        CardboardQrCode_destroy(params);
      }
      return false;
    }

    if (lens_distortion_ != nullptr) {
      CardboardLensDistortion_destroy(lens_distortion_);
      lens_distortion_ = nullptr;
    }
    lens_distortion_ = CardboardLensDistortion_create(params, size, screen_width_,
                                                      screen_height_);
    if (free_params && params != nullptr) {
      CardboardQrCode_destroy(params);
    }
    if (lens_distortion_ == nullptr) {
      return false;
    }

    if (distortion_renderer_ != nullptr) {
      CardboardDistortionRenderer_destroy(distortion_renderer_);
      distortion_renderer_ = nullptr;
    }
    CardboardOpenGlEsDistortionRendererConfig config{kGlTexture2D};
    distortion_renderer_ = CardboardOpenGlEs2DistortionRenderer_create(&config);
    if (distortion_renderer_ == nullptr) {
      return false;
    }

    screen_params_changed_ = false;
    device_params_changed_ = false;
    hardware_recreated = true;
  }

  if (hardware_recreated || lens_mesh_dirty_) {
    if (lens_distortion_ == nullptr || distortion_renderer_ == nullptr) {
      return false;
    }

    CardboardMesh left_mesh;
    CardboardMesh right_mesh;
    CardboardLensDistortion_getDistortionMesh(lens_distortion_, kLeft, &left_mesh);
    CardboardLensDistortion_getDistortionMesh(lens_distortion_, kRight, &right_mesh);

    std::vector<float> custom_left(left_mesh.vertices,
                                   left_mesh.vertices + left_mesh.n_vertices * 2);
    std::vector<float> custom_right(right_mesh.vertices,
                                    right_mesh.vertices + right_mesh.n_vertices * 2);
    std::vector<float> custom_left_uvs(left_mesh.uvs,
                                       left_mesh.uvs + left_mesh.n_vertices * 2);
    std::vector<float> custom_right_uvs(right_mesh.uvs,
                                        right_mesh.uvs + right_mesh.n_vertices * 2);

    TransformLensMesh(custom_left, lens_scale_, left_lens_offset_x_);
    TransformLensMesh(custom_right, lens_scale_, right_lens_offset_x_);
    ApplyLensDistortionStrength(left_mesh, custom_left_uvs);
    ApplyLensDistortionStrength(right_mesh, custom_right_uvs);

    left_mesh.vertices = custom_left.data();
    right_mesh.vertices = custom_right.data();
    left_mesh.uvs = custom_left_uvs.data();
    right_mesh.uvs = custom_right_uvs.data();

    CardboardDistortionRenderer_setMesh(distortion_renderer_, &left_mesh, kLeft);
    CardboardDistortionRenderer_setMesh(distortion_renderer_, &right_mesh, kRight);

    lens_mesh_dirty_ = false;
  }

  return true;
}

void VrMoonlightApp::ApplyLensDistortionStrength(const CardboardMesh& source,
                                                   std::vector<float>& uvs) const {
  if (source.vertices == nullptr || uvs.empty() || source.n_vertices <= 0) {
    return;
  }
  float min_x = source.vertices[0], max_x = source.vertices[0];
  float min_y = source.vertices[1], max_y = source.vertices[1];
  for (int i = 0; i < source.n_vertices; ++i) {
    const float x = source.vertices[i * 2], y = source.vertices[i * 2 + 1];
    min_x = std::min(min_x, x); max_x = std::max(max_x, x);
    min_y = std::min(min_y, y); max_y = std::max(max_y, y);
  }
  const float width = max_x - min_x, height = max_y - min_y;
  if (width <= 0.0001f || height <= 0.0001f) return;
  // Blend Cardboard's inverse-distortion UVs with a linear, undistorted map.
  for (int i = 0; i < source.n_vertices; ++i) {
    const float linear_u = (source.vertices[i * 2] - min_x) / width;
    const float linear_v = (source.vertices[i * 2 + 1] - min_y) / height;
    uvs[i * 2] = linear_u + (uvs[i * 2] - linear_u) * lens_distortion_strength_;
    uvs[i * 2 + 1] = linear_v + (uvs[i * 2 + 1] - linear_v) * lens_distortion_strength_;
  }
}

void VrMoonlightApp::TransformLensMesh(std::vector<float>& vertices, float scale,
                                       float offset_x) {
  if (vertices.empty()) {
    return;
  }

  float min_x = vertices[0];
  float max_x = vertices[0];
  float min_y = vertices[1];
  float max_y = vertices[1];

  for (size_t i = 0; i + 1 < vertices.size(); i += 2) {
    min_x = std::min(min_x, vertices[i]);
    max_x = std::max(max_x, vertices[i]);
    min_y = std::min(min_y, vertices[i + 1]);
    max_y = std::max(max_y, vertices[i + 1]);
  }

  const float c_x = (min_x + max_x) * 0.5f;
  const float c_y = (min_y + max_y) * 0.5f;

  const float scaled_min_x = c_x + (min_x - c_x) * scale;
  const float scaled_max_x = c_x + (max_x - c_x) * scale;

  // Cardboard's per-eye meshes already encode the physical lens centers
  // and may have intentionally asymmetric bounds. Never force each mesh into
  // an artificial half-screen rectangle; that destroys the SDK's stereo
  // alignment. Only constrain custom scaling/offset to the full display.
  const float tx_min = -1.0f - scaled_min_x;
  const float tx_max = 1.0f - scaled_max_x;
  // At large user-selected scales the mesh can be wider than the display,
  // making the legal offset interval inverted. Center it in that case rather
  // than violating std::clamp's low <= high precondition.
  const float safe_offset_x = tx_min <= tx_max
      ? std::clamp(offset_x, tx_min, tx_max)
      : (tx_min + tx_max) * 0.5f;

  for (size_t i = 0; i + 1 < vertices.size(); i += 2) {
    vertices[i] = c_x + (vertices[i] - c_x) * scale + safe_offset_x;
    vertices[i + 1] = c_y + (vertices[i + 1] - c_y) * scale;
  }
}

void VrMoonlightApp::RenderVideoToTexture() {
  if (framebuffer_ == 0 || render_texture_ == 0 || video_texture_ == 0) {
    return;
  }

  glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
  glViewport(0, 0, screen_width_, screen_height_);
  glDisable(GL_DEPTH_TEST);
  glClearColor(0.f, 0.f, 0.f, 1.f);
  glClear(GL_COLOR_BUFFER_BIT);

  glUseProgram(video_program_);
  glEnableVertexAttribArray(position_attrib_);
  glVertexAttribPointer(position_attrib_, 3, GL_FLOAT, GL_FALSE,
                        sizeof(GLfloat) * 3, kQuadVertices);
  glEnableVertexAttribArray(texcoord_attrib_);
  glVertexAttribPointer(texcoord_attrib_, 2, GL_FLOAT, GL_FALSE, 0,
                        kQuadTexCoords);

  glUniformMatrix4fv(texture_transform_uniform_, 1, GL_FALSE, texture_transform_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_EXTERNAL_OES, video_texture_);
  glUniform1i(sampler_uniform_, 0);

  Matrix4x4 identity;
  for (int i = 0; i < 4; ++i) {
    identity.m[i][i] = 1.0f;
  }
  const std::array<float, 16> identity_gl = identity.ToGlArray();
  glUniformMatrix4fv(mvp_uniform_, 1, GL_FALSE, identity_gl.data());

  const int left_eye_width = screen_width_ / 2;
  const int right_eye_width = screen_width_ - left_eye_width;

  // Fit the complete source frame in each eye without cropping or stretching.
  const auto draw_eye = [this](int x, int width) {
    if (width <= 0 || screen_height_ <= 0) return;
    const float eye_aspect = static_cast<float>(width) / screen_height_;
    int draw_width = width, draw_height = screen_height_;
    if (video_aspect_ratio_ > eye_aspect) {
      draw_height = std::max(1, static_cast<int>(std::lround(width / video_aspect_ratio_)));
    } else {
      draw_width = std::max(1, static_cast<int>(std::lround(screen_height_ * video_aspect_ratio_)));
    }
    glViewport(x + (width - draw_width) / 2, (screen_height_ - draw_height) / 2,
               draw_width, draw_height);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
  };
  draw_eye(0, left_eye_width);
  draw_eye(left_eye_width, right_eye_width);

  glDisableVertexAttribArray(position_attrib_);
  glDisableVertexAttribArray(texcoord_attrib_);
  glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
}


void VrMoonlightApp::RenderSpatialVideoToTexture() {
  if (!framebuffer_ || !render_texture_ || !video_texture_ ||
      !lens_distortion_ || !head_pose_valid_) return;
  glBindFramebuffer(GL_FRAMEBUFFER,framebuffer_);
  glDisable(GL_DEPTH_TEST);
  glClearColor(0.f,0.f,0.f,1.f);
  glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
  glUseProgram(video_program_);
  glEnableVertexAttribArray(position_attrib_);
  glVertexAttribPointer(position_attrib_,3,GL_FLOAT,GL_FALSE,sizeof(GLfloat)*3,kQuadVertices);
  glEnableVertexAttribArray(texcoord_attrib_);
  glVertexAttribPointer(texcoord_attrib_,2,GL_FLOAT,GL_FALSE,0,kQuadTexCoords);
  glActiveTexture(GL_TEXTURE0); glUniform1i(sampler_uniform_,0);
  Matrix4x4 identity; for(int i=0;i<4;++i) identity.m[i][i]=1.f;
  const auto identity_gl=identity.ToGlArray();
  const Matrix4x4 window_world=(window_locked_?window_anchor_matrix_:head_world_matrix_)*model_matrix_;
  UpdateSpatialGaze();
  const int left_width=screen_width_/2, right_width=screen_width_-left_width;
  const auto draw_eye=[&](CardboardEye eye,int x,int width) {
    if(width<=0||screen_height_<=0) return;
    glViewport(x,0,width,screen_height_);
    glUseProgram(video_program_);
    glEnableVertexAttribArray(position_attrib_);
    glVertexAttribPointer(position_attrib_,3,GL_FLOAT,GL_FALSE,
                          sizeof(GLfloat)*3,kQuadVertices);
    glEnableVertexAttribArray(texcoord_attrib_);
    glVertexAttribPointer(texcoord_attrib_,2,GL_FLOAT,GL_FALSE,0,kQuadTexCoords);
    if(camera_enabled_&&camera_texture_) {
      glUniformMatrix4fv(texture_transform_uniform_,1,GL_FALSE,camera_texture_transform_);
      glUniformMatrix4fv(mvp_uniform_,1,GL_FALSE,identity_gl.data());
      glBindTexture(GL_TEXTURE_EXTERNAL_OES,camera_texture_);
      glDrawArrays(GL_TRIANGLE_STRIP,0,4); glBindTexture(GL_TEXTURE_EXTERNAL_OES,0);
    }
    float projection_gl[16]={}, eye_gl[16]={};
    CardboardLensDistortion_getProjectionMatrix(lens_distortion_,eye,0.1f,100.f,projection_gl);
    CardboardLensDistortion_getEyeFromHeadMatrix(lens_distortion_,eye,eye_gl);
    const Matrix4x4 projection=GetMatrixFromGlArray(projection_gl);
    const Matrix4x4 eye_from_head=GetMatrixFromGlArray(eye_gl);
    const Matrix4x4 head_view=GetViewMatrixFromPose({0.f,0.f,0.f},last_head_orientation_);
    const Matrix4x4 vp=projection*eye_from_head*head_view;
    const auto mvp=(vp*window_world).ToGlArray();
    glUniformMatrix4fv(texture_transform_uniform_,1,GL_FALSE,texture_transform_);
    glUniformMatrix4fv(mvp_uniform_,1,GL_FALSE,mvp.data());
    glBindTexture(GL_TEXTURE_EXTERNAL_OES,video_texture_);
    glDrawArrays(GL_TRIANGLE_STRIP,0,4); glBindTexture(GL_TEXTURE_EXTERNAL_OES,0);
    DrawSpatialControls(vp);
  };
  draw_eye(kLeft,0,left_width); draw_eye(kRight,left_width,right_width);
  glDisableVertexAttribArray(position_attrib_);
  glDisableVertexAttribArray(texcoord_attrib_);
  glBindFramebuffer(GL_FRAMEBUFFER,0);
}

void VrMoonlightApp::UpdateSpatialGaze() {
  const int64_t now = GetBootTimeNano();
  const Matrix4x4 window_world =
      (window_locked_ ? window_anchor_matrix_ : head_world_matrix_) * model_matrix_;
  const Matrix4x4 control_world =
      window_world * GetTranslationMatrix({0.0f, 1.18f, 0.04f}) *
      GetScaleMatrix({0.75f, 0.50f, 1.0f});

  Matrix4x4 inverse_control;
  float local_x = 0.0f, local_y = 0.0f;
  bool control_ray_valid = InvertMatrix(control_world, inverse_control);
  if (control_ray_valid) {
    const std::array<float,4> origin={0.f,0.f,0.f,1.f};
    const std::array<float,4> forward={-head_world_matrix_.m[0][2],-head_world_matrix_.m[1][2],-head_world_matrix_.m[2][2],0.f};
    const auto o=inverse_control*origin, d=inverse_control*forward;
    constexpr float plane_z=0.035f;
    if(std::fabs(d[2])<1e-5f) control_ray_valid=false;
    else {
      const float t=(plane_z-o[2])/d[2];
      local_x=o[0]+t*d[0]; local_y=o[1]+t*d[1];
      if(t<=0.f) control_ray_valid=false;
    }
  }

  gaze_target_ = -1;
  if (control_ray_valid && std::fabs(local_y) < 0.105f) {
    const float centers[]={-0.80f,-0.40f,0.f,0.40f,0.80f};
    for(int i=0;i<5;++i) {
      if(std::fabs(local_x-centers[i])<0.145f) { gaze_target_=i; break; }
    }
  }

  // Looking just above the toolbar for two seconds recentres the screen.
  const bool recenter_target = control_ray_valid && local_x > -0.9f &&
      local_x < 0.9f && local_y > 0.22f && local_y < 0.82f;
  if (recenter_target && recenter_action_armed_) {
    if (last_recenter_frame_nano_ != 0) {
      const float dt=static_cast<float>(now-last_recenter_frame_nano_)/1.0e9f;
      recenter_dwell_seconds_ += std::clamp(dt,0.f,0.05f);
    }
    if (recenter_dwell_seconds_ >= kRecenterDwellSeconds) {
      // Preserve user-selected size and viewing distance; reset only the
      // window's angular offset and anchor it directly in front of the user.
      screen_yaw_radians_=0.f;
      screen_pitch_radians_=0.f;
      screen_rotation_radians_=0.f;
      UpdateModelMatrix();
      window_anchor_matrix_=head_world_matrix_;
      window_locked_=true;
      window_lock_pending_=false;
      window_edit_mode_=false;
      window_dragging_=false;
      window_select_dwell_seconds_=0.f;
      window_select_armed_=true;
      recenter_action_armed_=false;
      recenter_dwell_seconds_=0.f;
    }
  } else if (!recenter_target) {
    recenter_dwell_seconds_=0.f;
    recenter_action_armed_=true;
  }
  last_recenter_frame_nano_=now;

  if (gaze_target_ >= 0) {
    spatial_controls_visible_ = true;
    last_spatial_controls_gaze_nano_ = now;
  } else if (last_spatial_controls_gaze_nano_ == 0) {
    last_spatial_controls_gaze_nano_ = now;
  } else if (spatial_controls_auto_hide_ &&
             now-last_spatial_controls_gaze_nano_>=3000000000LL) {
    spatial_controls_visible_=false;
  }

  if (gaze_target_ != previous_gaze_target_) {
    gaze_dwell_seconds_=0.f;
    gaze_action_armed_=true;
    previous_gaze_target_=gaze_target_;
  }
  if(gaze_target_>=0&&last_gaze_frame_nano_!=0) {
    const float dt=static_cast<float>(now-last_gaze_frame_nano_)/1.0e9f;
    gaze_dwell_seconds_+=std::clamp(dt,0.f,0.05f);
  } else if(gaze_target_<0) gaze_dwell_seconds_=0.f;
  last_gaze_frame_nano_=now;

  if(gaze_target_>=0&&gaze_dwell_seconds_>=kGazeDwellSeconds&&gaze_action_armed_) {
    switch(gaze_target_) {
      case 0: AdjustScreenDistance(-0.15f); break;
      case 1: AdjustScreenDistance(0.15f); break;
      case 2: AdjustScreenSize(-0.10f); break;
      case 3: AdjustScreenSize(0.10f); break;
      case 4:
        window_edit_mode_=!window_edit_mode_;
        window_dragging_=false;
        window_select_dwell_seconds_=0.f;
        window_select_armed_=true;
        if (!window_edit_mode_) {
          // Dragging already updates the world anchor continuously. Turning
          // edit mode off simply commits that final anchor in place.
          window_locked_=true;
          window_lock_pending_=false;
        }
        break;
    }
    gaze_action_armed_=false;
    gaze_dwell_seconds_=0.f;
  }

  // In edit mode, stare at the video window for three seconds to grab it.
  bool video_hit=false;
  if(window_edit_mode_&&!window_dragging_) {
    Matrix4x4 inverse_window;
    if(InvertMatrix(window_world,inverse_window)) {
      const std::array<float,4> origin={0.f,0.f,0.f,1.f};
      const std::array<float,4> forward={-head_world_matrix_.m[0][2],-head_world_matrix_.m[1][2],-head_world_matrix_.m[2][2],0.f};
      const auto o=inverse_window*origin,d=inverse_window*forward;
      if(std::fabs(d[2])>1e-5f) {
        const float t=-o[2]/d[2];
        const float x=o[0]+t*d[0],y=o[1]+t*d[1];
        video_hit=t>0.f&&std::fabs(x)<1.0f&&std::fabs(y)<1.0f;
      }
    }
    if(video_hit&&window_select_armed_) {
      if(last_window_select_frame_nano_!=0) {
        const float dt=static_cast<float>(now-last_window_select_frame_nano_)/1.0e9f;
        window_select_dwell_seconds_+=std::clamp(dt,0.f,0.05f);
      }
      if(window_select_dwell_seconds_>=kWindowSelectDwellSeconds) {
        window_dragging_=true;
        drag_start_head_matrix_=head_world_matrix_;
        drag_start_window_matrix_=window_anchor_matrix_;
        window_last_motion_nano_=now;
        window_select_armed_=false;
      }
    } else if(!video_hit) {
      window_select_dwell_seconds_=0.f;
      window_select_armed_=true;
    }
  }

  if(window_dragging_) {
    Matrix4x4 inverse_start_head;
    if(InvertMatrix(drag_start_head_matrix_,inverse_start_head)) {
      const Matrix4x4 delta=head_world_matrix_*inverse_start_head;
      const Matrix4x4 moved=delta*drag_start_window_matrix_;
      float motion=0.f;
      for(int row=0;row<3;++row) for(int col=0;col<3;++col)
        motion+=std::fabs(head_world_matrix_.m[row][col]-drag_start_head_matrix_.m[row][col]);
      if(motion>0.025f) {
        window_anchor_matrix_=moved;
        drag_start_head_matrix_=head_world_matrix_;
        drag_start_window_matrix_=moved;
        window_last_motion_nano_=now;
      } else if(now-window_last_motion_nano_>=
                static_cast<int64_t>(kWindowIdleReleaseSeconds*1.0e9f)) {
        window_dragging_=false;
        window_select_dwell_seconds_=0.f;
        window_select_armed_=false;
      }
    }
  }
  last_window_select_frame_nano_=now;
}
void VrMoonlightApp::DrawSpatialControls(const Matrix4x4& vp) {
  const bool draw_toolbar = !(spatial_controls_auto_hide_ && !spatial_controls_visible_);
  const Matrix4x4 window_world =
      (window_locked_ ? window_anchor_matrix_ : head_world_matrix_) * model_matrix_;
  const Matrix4x4 control_world =
      window_world * GetTranslationMatrix({0.0f, 1.18f, 0.04f}) *
      GetScaleMatrix({0.75f, 0.50f, 1.0f});
  if(!spatial_ui_program_) return;
  glUseProgram(spatial_ui_program_);
  glEnableVertexAttribArray(spatial_ui_pos_attrib_);
  glVertexAttribPointer(spatial_ui_pos_attrib_,3,GL_FLOAT,GL_FALSE,0,kSpatialUiQuad);
  const float centers[]={-0.80f,-0.40f,0.f,0.40f,0.80f};
  const auto rect=[&](float x,float y,float z,float w,float h,const float* color) {
    const Matrix4x4 local=GetTranslationMatrix({x,y,z})*GetScaleMatrix({w,h,1.f});
    const auto mvp=(vp*control_world*local).ToGlArray();
    glUniformMatrix4fv(spatial_ui_mvp_uniform_,1,GL_FALSE,mvp.data());
    glUniform4fv(spatial_ui_color_uniform_,1,color);
    glDrawArrays(GL_TRIANGLES,0,6);
  };
  if (draw_toolbar) {
  const float panel[]={0.018f,0.024f,0.038f,0.92f};
  const float normal[]={0.13f,0.16f,0.21f,1.f};
  const float focus[]={0.08f,0.38f,0.72f,1.f};
  const float locked_color[]={0.08f,0.28f,0.20f,1.f};
  const float edit_color[]={0.42f,0.22f,0.08f,1.f};
  const float white[]={0.9f,0.94f,1.f,1.f};
  rect(0.f,0.f,0.025f,2.02f,0.27f,panel);
  for(int i=0;i<5;++i) {
    rect(centers[i],0.f,0.035f,0.31f,0.20f,
         gaze_target_==i?focus:(i==4&&window_edit_mode_?edit_color:(i==4&&window_locked_?locked_color:normal)));
    if(i<2) {
      rect(centers[i],0.f,0.046f,0.105f,0.012f,white);
      if(i==1) rect(centers[i],0.f,0.047f,0.012f,0.105f,white);
    } else if(i<4) {
      rect(centers[i]-0.035f,0.f,0.046f,0.012f,0.085f,white);
      rect(centers[i]+0.035f,0.f,0.046f,0.012f,0.085f,white);
      rect(centers[i],0.f,0.046f,0.06f,0.012f,white);
      if(i==3) rect(centers[i],0.f,0.047f,0.012f,0.06f,white);
    } else {
      // Four corner marks: spatial placement/edit mode, not a padlock.
      rect(centers[i]-0.035f,0.025f,0.046f,0.012f,0.035f,white);
      rect(centers[i]+0.035f,0.025f,0.046f,0.012f,0.035f,white);
      rect(centers[i]-0.035f,-0.025f,0.046f,0.012f,0.035f,white);
      rect(centers[i]+0.035f,-0.025f,0.046f,0.012f,0.035f,white);
    }

  }

  }

  // One clockwise progress ring around the reticle communicates every
  // dwell action; toolbar glyphs remain static.
  const float reticle_color[] = {0.78f, 0.84f, 0.92f, 0.95f};
  float progress=0.f;
  if (recenter_dwell_seconds_>0.f)
    progress=std::clamp(recenter_dwell_seconds_/kRecenterDwellSeconds,0.f,1.f);
  else if (window_dragging_)
    progress=std::clamp(static_cast<float>(GetBootTimeNano()-window_last_motion_nano_)/1.0e9f,0.f,1.f);
  else if (window_edit_mode_&&!window_dragging_&&window_select_dwell_seconds_>0.f)
    progress=std::clamp(window_select_dwell_seconds_/kWindowSelectDwellSeconds,0.f,1.f);
  else if (gaze_target_>=0&&gaze_action_armed_)
    progress=std::clamp(gaze_dwell_seconds_/kGazeDwellSeconds,0.f,1.f);

  const Matrix4x4 reticle_pose = GetTranslationMatrix({0.0f, 0.0f, -1.35f});
  constexpr int kRingSegments=48;
  const int visible_segments=progress>0.f
      ? std::max(1,static_cast<int>(std::ceil(progress*kRingSegments)))
      : kRingSegments;
  for(int i=0;i<visible_segments;++i) {
    const float angle=-1.57079632679f-
        (6.28318530718f*static_cast<float>(i)/kRingSegments);
    Matrix4x4 rotate;
    for(int row=0;row<4;++row)
      for(int col=0;col<4;++col)
        rotate.m[row][col]=(row==col)?1.f:0.f;
    rotate.m[0][0]=std::cos(angle); rotate.m[0][1]=-std::sin(angle);
    rotate.m[1][0]=std::sin(angle); rotate.m[1][1]=std::cos(angle);
    const Matrix4x4 segment =
        GetTranslationMatrix({0.034f*std::cos(angle),0.034f*std::sin(angle),0.f}) *
        rotate * GetScaleMatrix({0.0035f,0.008f,1.f});
    const auto ring_mvp=(vp*head_world_matrix_*reticle_pose*segment).ToGlArray();
    glUniformMatrix4fv(spatial_ui_mvp_uniform_,1,GL_FALSE,ring_mvp.data());
    glUniform4fv(spatial_ui_color_uniform_,1,reticle_color);
    glDrawArrays(GL_TRIANGLES,0,6);
  }
  glDisableVertexAttribArray(spatial_ui_pos_attrib_);
}

void VrMoonlightApp::DestroyCardboardResources() {
  if (distortion_renderer_ != nullptr) {
    CardboardDistortionRenderer_destroy(distortion_renderer_);
    distortion_renderer_ = nullptr;
  }
  if (lens_distortion_ != nullptr) {
    CardboardLensDistortion_destroy(lens_distortion_);
    lens_distortion_ = nullptr;
  }
}

void VrMoonlightApp::RecenterView() {
  head_pitch_reference_valid_ = false;
  if (head_tracker_ != nullptr) {
    CardboardHeadTracker_recenter(head_tracker_);
    head_pose_valid_ = false;
    toolbar_anchor_valid_ = false;
    // A recenter changes the tracker coordinate frame. Recapture a locked
    // window anchor from the first valid pose in the new frame; otherwise the
    // old anchor would be mixed with the recentered view and appear to jump.
    window_lock_pending_ = window_locked_;
  }
}

void VrMoonlightApp::SetScreenDistance(float meters) {
  screen_distance_meters_ = meters;
  if (screen_distance_meters_ < kMinScreenDistanceMeters) {
    screen_distance_meters_ = kMinScreenDistanceMeters;
  } else if (screen_distance_meters_ > kMaxScreenDistanceMeters) {
    screen_distance_meters_ = kMaxScreenDistanceMeters;
  }
  UpdateModelMatrix();
}

void VrMoonlightApp::SetScreenSize(float sizeMultiplier) {
  screen_size_multiplier_ = sizeMultiplier;
  if (screen_size_multiplier_ < 0.25f) {
    screen_size_multiplier_ = 0.25f;
  } else if (screen_size_multiplier_ > 4.0f) {
    screen_size_multiplier_ = 4.0f;
  }
  UpdateModelMatrix();
  geometry_dirty_ = true;
}

void VrMoonlightApp::AdjustScreenDistance(float deltaMeters) {
  screen_distance_meters_ += deltaMeters;
  if (screen_distance_meters_ < kMinScreenDistanceMeters) {
    screen_distance_meters_ = kMinScreenDistanceMeters;
  } else if (screen_distance_meters_ > kMaxScreenDistanceMeters) {
    screen_distance_meters_ = kMaxScreenDistanceMeters;
  }
  UpdateModelMatrix();
}

void VrMoonlightApp::AdjustScreenSize(float deltaMultiplier) {
  screen_size_multiplier_ += deltaMultiplier;
  if (screen_size_multiplier_ < 0.25f) {
    screen_size_multiplier_ = 0.25f;
  } else if (screen_size_multiplier_ > 4.0f) {
    screen_size_multiplier_ = 4.0f;
  }
  UpdateModelMatrix();
  geometry_dirty_ = true;
}

void VrMoonlightApp::AdjustScreenPosition(float deltaX, float deltaY) {
  const float kYawSensitivity = 3.0f;
  const float kPitchSensitivity = 3.0f;

  screen_yaw_radians_ += deltaX * kYawSensitivity;
  screen_pitch_radians_ += deltaY * kPitchSensitivity;

  screen_pitch_radians_ = std::clamp(screen_pitch_radians_, -1.047f, 1.047f);
  screen_yaw_radians_ = std::clamp(screen_yaw_radians_, -3.14159f, 3.14159f);
  UpdateModelMatrix();
}

void VrMoonlightApp::AdjustScreenRotation(float deltaRadians) {
  screen_rotation_radians_ += deltaRadians;
  const float two_pi = 6.28318530717958647692f;
  while (screen_rotation_radians_ >= two_pi) screen_rotation_radians_ -= two_pi;
  while (screen_rotation_radians_ < 0.0f) screen_rotation_radians_ += two_pi;
  UpdateModelMatrix();
}

void VrMoonlightApp::SetCurvatureMode(int mode) {
  if (mode < kCurvatureModeFlat || mode > kCurvatureModeGamingScreen) {
    return;
  }
  curvature_mode_ = mode;
  geometry_dirty_ = true;
}

void VrMoonlightApp::SetCurvatureAmount(float percent) {
  curvature_amount_percent_ = std::clamp(percent, 0.f, 100.f);
  geometry_dirty_ = true;
}

void VrMoonlightApp::SetHorizontalCurvature(float percent) {
  horizontal_curvature_percent_ = std::clamp(percent, 0.f, 100.f);
  geometry_dirty_ = true;
}

void VrMoonlightApp::SetVerticalCurvature(float percent) {
  vertical_curvature_percent_ = std::clamp(percent, 0.f, 100.f);
  geometry_dirty_ = true;
}

float VrMoonlightApp::GetCurvatureAmount() const {
  return curvature_amount_percent_;
}

float VrMoonlightApp::GetHorizontalCurvature() const {
  return horizontal_curvature_percent_;
}

float VrMoonlightApp::GetVerticalCurvature() const {
  return vertical_curvature_percent_;
}

float VrMoonlightApp::GetScreenSize() const {
  return screen_size_multiplier_;
}

void VrMoonlightApp::SetSkyboxEnabled(bool enabled) {
  skybox_enabled_ = enabled;
}

void VrMoonlightApp::SetSkyboxTexture(GLuint textureId) {
  skybox_texture_ = textureId;
}

void VrMoonlightApp::SetSkyboxBrightness(float brightness) {
  skybox_brightness_ = std::max(0.0f, std::min(1.0f, brightness));
}

void VrMoonlightApp::SetLensScale(float scale) {
  lens_scale_ = std::clamp(scale, kMinLensScale, kMaxLensScale);
  lens_mesh_dirty_ = true;
}

void VrMoonlightApp::AdjustLeftLensOffset(float delta_x) {
  left_lens_offset_x_ += delta_x;
  lens_mesh_dirty_ = true;
}

void VrMoonlightApp::AdjustRightLensOffset(float delta_x) {
  right_lens_offset_x_ += delta_x;
  lens_mesh_dirty_ = true;
}
 
void VrMoonlightApp::UpdateModelMatrix() {
  const float half_width = kScreenWidthMeters * 0.5f * screen_size_multiplier_;
  const float half_height = half_width / video_aspect_ratio_;
  const std::array<float, 3> scale = {half_width, half_height, 1.0f};
  const std::array<float, 3> translation = {0.0f, 0.0f, -screen_distance_meters_};

  Matrix4x4 local_rot;
  for (int i = 0; i < 16; ++i) local_rot.m[i/4][i%4] = (i%5 == 0) ? 1.0f : 0.0f;
  float cos_r = std::cos(screen_rotation_radians_);
  float sin_r = std::sin(screen_rotation_radians_);
  local_rot.m[0][0] = cos_r;  local_rot.m[0][1] = -sin_r;
  local_rot.m[1][0] = sin_r;  local_rot.m[1][1] = cos_r;

  Matrix4x4 orbit_rot;
  for (int i = 0; i < 16; ++i) orbit_rot.m[i/4][i%4] = (i%5 == 0) ? 1.0f : 0.0f;
  float cos_y = std::cos(screen_yaw_radians_);
  float sin_y = std::sin(screen_yaw_radians_);
  float cos_x = std::cos(screen_pitch_radians_);
  float sin_x = std::sin(screen_pitch_radians_);

  orbit_rot.m[0][0] = cos_y;
  orbit_rot.m[0][1] = sin_y * sin_x;
  orbit_rot.m[0][2] = sin_y * cos_x;
  orbit_rot.m[1][0] = 0.0f;
  orbit_rot.m[1][1] = cos_x;
  orbit_rot.m[1][2] = -sin_x;
  orbit_rot.m[2][0] = -sin_y;
  orbit_rot.m[2][1] = cos_y * sin_x;
  orbit_rot.m[2][2] = cos_y * cos_x;

  model_matrix_ = orbit_rot * GetTranslationMatrix(translation) * local_rot * GetScaleMatrix(scale);
}

void VrMoonlightApp::UpdateScreenGeometry() {
  const float half_width = kScreenWidthMeters * 0.5f * screen_size_multiplier_;
  const float half_height = half_width / kScreenAspectRatio;

  float global_ratio = curvature_amount_percent_ / 100.0f;
  float h_ratio = global_ratio * (horizontal_curvature_percent_ / 100.0f);
  float v_ratio = global_ratio * (vertical_curvature_percent_ / 100.0f);

  if (curvature_mode_ == kCurvatureModeGamingScreen) {
    v_ratio = 0.0f;
  }

  if (curvature_mode_ == kCurvatureModeFlat || (h_ratio <= 0.001f && v_ratio <= 0.001f)) {
    using_curved_geometry_ = false;
    curved_rows_ = 0;
    curved_vertices_per_strip_ = 0;
    screen_vertices_.clear();
    screen_texcoords_.clear();
    return;
  }

  curved_rows_ = (curvature_mode_ == kCurvatureModeTvCinema) ? kCurvedGridRowsTv : kCurvedGridRowsGaming;
  curved_vertices_per_strip_ = (kCurvedGridCols + 1) * 2;
  const float rows_float = static_cast<float>(curved_rows_);

  const float max_theta = h_ratio * kMaxHorizontalCurvatureAngle;
  const float max_phi = v_ratio * kMaxVerticalCurvatureAngle;

  screen_vertices_.clear();
  screen_texcoords_.clear();
  screen_vertices_.reserve(curved_rows_ * curved_vertices_per_strip_ * 3);
  screen_texcoords_.reserve(curved_rows_ * curved_vertices_per_strip_ * 2);

  for (int row = 0; row < curved_rows_; ++row) {
    for (int col = 0; col <= kCurvedGridCols; ++col) {
      const float u = static_cast<float>(col) / static_cast<float>(kCurvedGridCols);
      float flat_x = (u - 0.5f) * 2.0f * half_width;

      for (int v_idx = 0; v_idx < 2; ++v_idx) {
        const float v_norm = (static_cast<float>(row + v_idx) / rows_float);
        float flat_y = (v_norm - 0.5f) * 2.0f * half_height;

        float phys_x = flat_x;
        float phys_y = flat_y;
        float phys_z = 0.0f;

        if (curvature_mode_ == kCurvatureModeGamingScreen) {
          if (max_theta > 0.001f) {
            float radius = (2.0f * half_width) / max_theta;
            float theta = flat_x / radius;
            phys_x = radius * std::sin(theta);
            phys_z = radius * (1.0f - std::cos(theta));
          }
        } else if (curvature_mode_ == kCurvatureModeTvCinema) {
          float r_h = (max_theta > 0.001f) ? ((2.0f * half_width) / max_theta) : 10000.0f;
          float r_v = (max_phi > 0.001f) ? ((2.0f * half_height) / max_phi) : 10000.0f;

          float theta = (max_theta > 0.001f) ? (flat_x / r_h) : 0.0f;
          float phi = (max_phi > 0.001f) ? (flat_y / r_v) : 0.0f;

          phys_x = r_h * std::sin(theta) * std::cos(phi);
          phys_y = r_v * std::sin(phi);

          float z_h = r_h * (1.0f - std::cos(theta));
          float z_v = r_v * (1.0f - std::cos(phi));
          phys_z = z_h * std::cos(phi) + z_v;
        }

        float final_x = phys_x / half_width;
        float final_y = phys_y / half_height;
        float final_z = phys_z;

        screen_vertices_.push_back(final_x);
        screen_vertices_.push_back(final_y);
        screen_vertices_.push_back(final_z);
        screen_texcoords_.push_back(u);
        screen_texcoords_.push_back(1.0f - v_norm);
      }
    }
  }
  using_curved_geometry_ = true;
}

void VrMoonlightApp::UpdateCameraModelMatrix() {
  const float pip_width = 0.8f;
  const float pip_height = pip_width / (16.0f / 9.0f);

  const std::array<float, 3> scale = {pip_width * 0.5f, -pip_height * 0.5f, 1.0f};
  const std::array<float, 3> translation = {0.0f, -0.6f, -1.2f};

  camera_model_matrix_ = GetTranslationMatrix(translation) * GetScaleMatrix(scale);
}

void VrMoonlightApp::SetCameraTexture(GLuint texture_id) {
  camera_texture_ = texture_id;
}

void VrMoonlightApp::SetCameraTextureTransform(const float* transform) {
  if (transform != nullptr) {
    std::copy(transform, transform + 16, camera_texture_transform_);
  }
}

void VrMoonlightApp::SetCameraEnabled(bool enabled) {
  camera_enabled_ = enabled;
}

void VrMoonlightApp::SetSpatialControlsAutoHide(bool enabled) {
  spatial_controls_auto_hide_ = enabled;
  if (!enabled) {
    spatial_controls_visible_ = true;
  } else if (last_spatial_controls_gaze_nano_ == 0) {
    spatial_controls_visible_ = true;
  }
}

}  // namespace moonlight_vr
