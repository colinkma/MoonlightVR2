package com.limelight.vr;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import javax.microedition.khronos.egl.EGLConfig;
import android.opengl.GLSurfaceView;
import android.opengl.GLSurfaceView.Renderer;
import android.opengl.GLES20;
import android.opengl.GLES11Ext;
import android.os.Handler;
import android.os.Looper;
import android.opengl.Matrix;
import android.preference.PreferenceManager;
import android.os.SystemClock;
import android.view.Surface;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLUtils;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

import javax.microedition.khronos.opengles.GL10;

public class VrRenderer implements Renderer, SurfaceTexture.OnFrameAvailableListener {
    static {
        System.loadLibrary("vr_renderer");
    }

    public static final int CURVATURE_MODE_FLAT = 0;
    public static final int CURVATURE_MODE_TV_CINEMA = 1;
    public static final int CURVATURE_MODE_GAMING_SCREEN = 2;

    public interface SurfaceListener {
        void onSurfaceReady(Surface surface);
    }

    public interface HeadPitchListener {
        void onHeadPitchChanged(boolean lookingDown);
    }

    // Require a deliberate downward head tilt; use hysteresis to prevent
    // the passthrough from flickering near the activation boundary.
    private static final float CAMERA_ENTER_PITCH_DEGREES = 52.0f;
    private static final float CAMERA_EXIT_PITCH_DEGREES = 38.0f;
    private HeadPitchListener headPitchListener;
    private volatile boolean lookingDownForCamera = false;

    private final Context context;
    private final GLSurfaceView glSurfaceView;
    private final SurfaceListener surfaceListener;
    private final long nativeHandle;
    private final float[] textureTransform = new float[16];
    private SurfaceTexture surfaceTexture;
    private Surface videoSurface;
    private volatile boolean frameAvailable = false;
    private int[] skyboxResourceIds;
    private Resources skyboxResources;
    private int skyboxTextureId = 0;
    private int cameraTextureId = 0;
    private float[] cameraTextureTransform = new float[16];
    private float[] cameraTransformScratch = new float[16];
    private final float[] cameraRotationMatrix = new float[16];
    // Rotation of the handset's screen plane relative to gravity. Used only
    // to keep the passthrough image upright when the viewer is rolled.
    private volatile float cameraTextureRollDegrees = 0.0f;
    private volatile int cameraSensorOrientationDegrees = 90;
    private SurfaceTexture cameraSurfaceTexture;
    private Surface cameraSurface;
    private volatile boolean cameraFrameAvailable = false;

    private final SensorManager sensorManager;
    private final Sensor rotationVectorSensor;
    private final SensorEventListener magneticHeadingListener;
    private volatile float magneticHeadingRadians = 0.0f;
    private volatile float magneticHeadingAccuracyRadians = Float.MAX_VALUE;
    private volatile int magneticHeadingSensorAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
    private volatile long magneticHeadingTimestampNanos = 0L;
    private long lastMagneticHeadingSentTimestampNanos = 0L;
    private volatile int viewportRotation = Surface.ROTATION_0;
    private boolean spatialModeRequested = false;
    private boolean rendererResumed = false;
    private boolean magneticHeadingRegistered = false;

    public Surface createCameraSurface() {
        releaseCameraSurface();

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        cameraTextureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        cameraSurfaceTexture = new SurfaceTexture(cameraTextureId);
        cameraSurfaceTexture.setDefaultBufferSize(1280, 720);
        cameraSurfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                cameraFrameAvailable = true;
                glSurfaceView.requestRender();
            }
        }, new Handler(Looper.getMainLooper()));

        cameraSurface = new Surface(cameraSurfaceTexture);
        setCameraTexture(cameraTextureId);

        updateCameraRotationMatrix();

        return cameraSurface;
    }

    private void updateCameraRotationMatrix() {
        Matrix.setIdentityM(cameraRotationMatrix, 0);
        Matrix.translateM(cameraRotationMatrix, 0, 0.5f, 0.5f, 0f);
        // Keep the camera preview in the same landscape baseline as the
        // original working renderer. The camera sensor's 90-degree mounting
        // correction is required even when Android reports ROTATION_90 for
        // the landscape Activity; subtracting display rotation here cancels
        // the correction and leaves the camera image portrait.
        float cameraCorrectionDegrees = cameraSensorOrientationDegrees
                - cameraTextureRollDegrees;
        Matrix.rotateM(cameraRotationMatrix, 0,
                cameraCorrectionDegrees, 0f, 0f, 1f);
        Matrix.translateM(cameraRotationMatrix, 0, -0.5f, -0.5f, 0f);
    }

    public void setCameraSensorOrientationDegrees(int orientationDegrees) {
        cameraSensorOrientationDegrees = ((orientationDegrees % 360) + 360) % 360;
        glSurfaceView.requestRender();
    }

    public void setCameraTexture(int textureId) {
        this.cameraTextureId = textureId;
        nativeSetCameraTexture(nativeHandle, textureId);
    }

    // Must be called on the GL thread after the camera has been stopped.
    public void releaseCameraSurface() {
        cameraFrameAvailable = false;
        if (cameraSurface != null) {
            cameraSurface.release();
            cameraSurface = null;
        }
        if (cameraSurfaceTexture != null) {
            cameraSurfaceTexture.setOnFrameAvailableListener(null);
            cameraSurfaceTexture.release();
            cameraSurfaceTexture = null;
        }
        if (cameraTextureId != 0) {
            int[] textures = { cameraTextureId };
            GLES20.glDeleteTextures(1, textures, 0);
            cameraTextureId = 0;
        }
        nativeSetCameraTexture(nativeHandle, 0);
    }

    public void setCameraTextureTransform(float[] transform) {
        nativeSetCameraTextureTransform(nativeHandle, transform);
    }

    public void setCameraEnabled(boolean enabled) {
        glSurfaceView.queueEvent(() -> nativeSetCameraEnabled(nativeHandle, enabled));
    }

    public void setSpatialControlsAutoHide(boolean enabled) {
        glSurfaceView.queueEvent(() -> nativeSetSpatialControlsAutoHide(nativeHandle, enabled));
    }

    public void updateCameraTextureTransform(float[] transform) {
        if (transform != null) {
            System.arraycopy(transform, 0, cameraTextureTransform, 0, 16);
        }
    }

    public VrRenderer(Context context, GLSurfaceView glSurfaceView, SurfaceListener surfaceListener) {
        this.context = context;
        this.glSurfaceView = glSurfaceView;
        this.surfaceListener = surfaceListener;
        this.nativeHandle = nativeCreate(context, context.getAssets());
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.rotationVectorSensor = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) : null;
        this.magneticHeadingListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
                float[] sensorMatrix = new float[9];
                float[] displayMatrix = new float[9];
                float[] orientation = new float[3];
                SensorManager.getRotationMatrixFromVector(sensorMatrix, event.values);

                int axisX = SensorManager.AXIS_X;
                int axisY = SensorManager.AXIS_Y;
                switch (viewportRotation) {
                    case Surface.ROTATION_90:
                        axisX = SensorManager.AXIS_Y;
                        axisY = SensorManager.AXIS_MINUS_X;
                        break;
                    case Surface.ROTATION_180:
                        axisX = SensorManager.AXIS_MINUS_X;
                        axisY = SensorManager.AXIS_MINUS_Y;
                        break;
                    case Surface.ROTATION_270:
                        axisX = SensorManager.AXIS_MINUS_Y;
                        axisY = SensorManager.AXIS_X;
                        break;
                    default:
                        break;
                }
                if (!SensorManager.remapCoordinateSystem(
                        sensorMatrix, axisX, axisY, displayMatrix)) {
                    return;
                }
                SensorManager.getOrientation(displayMatrix, orientation);
                if (!Float.isFinite(orientation[0])) return;

                // Preserve the established physical-roll value used by the
                // VR display. Do not replace this with Euler roll: that value
                // changes the native display pose and broke the previously
                // corrected screen orientation.
                float gravityX = -sensorMatrix[6];
                float gravityY = -sensorMatrix[7];
                if (Float.isFinite(gravityX) && Float.isFinite(gravityY)) {
                    float gravityInPlaneSquared =
                            gravityX * gravityX + gravityY * gravityY;
                    if (gravityInPlaneSquared > 0.04f) {
                        float neutralLandscapeDegrees = 0.0f;
                        if (viewportRotation == Surface.ROTATION_90) {
                            neutralLandscapeDegrees = 90.0f;
                        } else if (viewportRotation == Surface.ROTATION_270) {
                            neutralLandscapeDegrees = -90.0f;
                        } else if (viewportRotation == Surface.ROTATION_180) {
                            neutralLandscapeDegrees = 180.0f;
                        }
                        float relativeRoll = (float) -Math.toDegrees(
                                Math.atan2(gravityX, -gravityY)) - neutralLandscapeDegrees;
                        while (relativeRoll > 180.0f) relativeRoll -= 360.0f;
                        while (relativeRoll < -180.0f) relativeRoll += 360.0f;
                        // Gravity-projected roll is only for correcting the camera texture.
                        // Cardboard already supplies the head roll for the VR display; applying
                        // this value to the screen model would rotate it a second time.
                        cameraTextureRollDegrees = relativeRoll;
                    } else {
                        cameraTextureRollDegrees = 0.0f;
                    }
                }

                magneticHeadingRadians = orientation[0];
                // Keep the sensor's reported geomagnetic accuracy while the
                // device is rolled. The camera image's independent gravity-
                // projected roll correction above must not disable yaw data.
                magneticHeadingAccuracyRadians = event.values.length > 4
                        ? event.values[4]
                        : (event.accuracy == SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                                ? 0.25f : Float.MAX_VALUE);
                magneticHeadingSensorAccuracy = event.accuracy;
                magneticHeadingTimestampNanos = event.timestamp != 0L
                        ? event.timestamp : SystemClock.elapsedRealtimeNanos();
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                magneticHeadingSensorAccuracy = accuracy;
            }
        };
        if (rotationVectorSensor == null) {
            LimeLog.warning("Magnetic yaw correction unavailable: no rotation-vector sensor");
        }
        for (int i = 0; i < 16; i++) {
            textureTransform[i] = (i % 5 == 0) ? 1f : 0f;
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
        LimeLog.info("VrRenderer.onSurfaceCreated: oldVideoSurface=" + videoSurface + " oldSurfaceTexture=" + surfaceTexture);
        int textureId = nativeOnSurfaceCreated(nativeHandle);
        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setOnFrameAvailableListener(this);
        videoSurface = new Surface(surfaceTexture);
        LimeLog.info("VrRenderer.onSurfaceCreated: newVideoSurface=" + videoSurface + " isValid=" + videoSurface.isValid());
        if (surfaceListener != null) {
            surfaceListener.onSurfaceReady(videoSurface);
        }

        if (skyboxResources != null && skyboxResourceIds != null && skyboxResourceIds.length == 6) {
            loadSkyboxCubemap(skyboxResources, skyboxResourceIds);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl10, int width, int height) {
        LimeLog.info("VrRenderer.onSurfaceChanged: width=" + width + " height=" + height);
        // The Activity may switch from portrait to landscape after this view is
        // created. Read the actual display rotation on the GL thread so the
        // first captured world anchor always uses the live viewport frame.
        if (context instanceof Activity) {
            int rotation = ((Activity) context).getWindowManager().getDefaultDisplay().getRotation();
            viewportRotation = rotation;
            updateCameraRotationMatrix();
            nativeSetViewportOrientation(nativeHandle, rotation);
        }
        nativeOnSurfaceChanged(nativeHandle, width, height);
    }

    private int frameDeliverCount = 0;
    private int frameConsumeCount = 0;
    private long lastFrameAvailableLog = 0;
    private long lastConsumeLog = 0;

    @Override
    public void onDrawFrame(GL10 gl10) {
        if (surfaceTexture != null) {
            try {
                surfaceTexture.updateTexImage();
                frameConsumeCount++;
                surfaceTexture.getTransformMatrix(textureTransform);
                nativeSetTextureTransform(nativeHandle, textureTransform);
                frameAvailable = false;
                long now = SystemClock.elapsedRealtime();
                if (frameConsumeCount % 60 == 0 || now - lastConsumeLog > 5000) {
                    LimeLog.warning("VR render: consumed frame #" + frameConsumeCount + " (delivered=" + frameDeliverCount + ")");
                    lastConsumeLog = now;
                }
            } catch (Exception e) {
            }
        }
        if (cameraSurfaceTexture != null && cameraFrameAvailable) {
            try {
                cameraSurfaceTexture.updateTexImage();
                cameraSurfaceTexture.getTransformMatrix(cameraTextureTransform);
                System.arraycopy(cameraTextureTransform, 0, cameraTransformScratch, 0, 16);
                updateCameraRotationMatrix();
                Matrix.multiplyMM(cameraTextureTransform, 0, cameraRotationMatrix, 0, cameraTransformScratch, 0);
                nativeSetCameraTextureTransform(nativeHandle, cameraTextureTransform);
                cameraFrameAvailable = false;
            } catch (Exception e) {
                LimeLog.warning("Error updating camera texture: " + e.getMessage());
            }
        }
        long headingTimestamp = magneticHeadingTimestampNanos;
        if (headingTimestamp != 0L &&
                headingTimestamp != lastMagneticHeadingSentTimestampNanos) {
            nativeSetMagneticHeading(nativeHandle, magneticHeadingRadians,
                    magneticHeadingAccuracyRadians, magneticHeadingSensorAccuracy);
            lastMagneticHeadingSentTimestampNanos = headingTimestamp;
        }
        float headPitchDegrees = nativeOnDrawFrame(nativeHandle);
        // Persist toolbar slider changes without exposing screen distance in
        // Settings. Writes occur only when the value actually changes.
        float currentScreenDistance = nativeGetScreenDistance(nativeHandle);
        int storedDistance = Math.round(currentScreenDistance * 10.0f);
        int previousStoredDistance = PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(PreferenceConfiguration.VR_SCREEN_DISTANCE_PREF_STRING, 20);
        if (storedDistance != previousStoredDistance) {
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putInt(PreferenceConfiguration.VR_SCREEN_DISTANCE_PREF_STRING, storedDistance)
                    .apply();
        }
        boolean shouldLookDown = lookingDownForCamera
                ? headPitchDegrees <= -CAMERA_EXIT_PITCH_DEGREES
                : headPitchDegrees <= -CAMERA_ENTER_PITCH_DEGREES;
        if (shouldLookDown != lookingDownForCamera) {
            lookingDownForCamera = shouldLookDown;
            HeadPitchListener listener = headPitchListener;
            if (listener != null) {
                listener.onHeadPitchChanged(lookingDownForCamera);
            }
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        frameDeliverCount++;
        long now = SystemClock.elapsedRealtime();
        if (frameDeliverCount % 60 == 0 || now - lastFrameAvailableLog > 5000) {
            LimeLog.warning("VR render: frame available #" + frameDeliverCount + " (consumed=" + frameConsumeCount + ")");
            lastFrameAvailableLog = now;
        }
        frameAvailable = true;
        glSurfaceView.requestRender();
    }

    public void onResume() {
        LimeLog.info("VrRenderer.onResume");
        rendererResumed = true;
        updateMagneticHeadingRegistration();
        glSurfaceView.onResume();
        // Keep Cardboard tracker state changes on the same GL thread that reads
        // its pose every frame. Calling pause/resume from the Activity thread
        // races nativeOnDrawFrame during fast Activity transitions.
        glSurfaceView.queueEvent(() -> nativeOnResume(nativeHandle));
    }

    public void onPause() {
        LimeLog.info("VrRenderer.onPause");
        rendererResumed = false;
        updateMagneticHeadingRegistration();
        if (lookingDownForCamera) {
            lookingDownForCamera = false;
            HeadPitchListener listener = headPitchListener;
            if (listener != null) {
                listener.onHeadPitchChanged(false);
            }
        }
        // Queue the tracker pause before pausing the GL thread so no pose query
        // can overlap a tracker lifecycle transition.
        glSurfaceView.queueEvent(() -> nativeOnPause(nativeHandle));
        glSurfaceView.onPause();
    }

    public void release() {
        rendererResumed = false;
        spatialModeRequested = false;
        updateMagneticHeadingRegistration();
        if (cameraTextureId != 0) {
            int[] texture = new int[] { cameraTextureId };
            GLES20.glDeleteTextures(1, texture, 0);
            cameraTextureId = 0;
        }
        if (cameraSurface != null) {
            cameraSurface.release();
            cameraSurface = null;
        }
        if (cameraSurfaceTexture != null) {
            cameraSurfaceTexture.setOnFrameAvailableListener(null);
            cameraSurfaceTexture.release();
            cameraSurfaceTexture = null;
        }
        if (skyboxTextureId != 0) {
            int[] texture = new int[] { skyboxTextureId };
            GLES20.glDeleteTextures(1, texture, 0);
            skyboxTextureId = 0;
        }
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.setOnFrameAvailableListener(null);
            surfaceTexture.release();
            surfaceTexture = null;
        }
        nativeDestroy(nativeHandle);
    }

    public Surface getVideoSurface() {
        return videoSurface;
    }

    public void setVideoAspectRatio(int width, int height) {
        if (width > 0 && height > 0) nativeSetVideoAspectRatio(nativeHandle, (float) width / height);
    }

    public void setLensDistortionStrength(float strength) {
        nativeSetLensDistortionStrength(nativeHandle, Math.max(0.0f, Math.min(1.0f, strength)));
    }

    public void setHeadPitchListener(HeadPitchListener listener) {
        this.headPitchListener = listener;
    }

    public void setDisplayMode(final int mode) {
        spatialModeRequested = mode == 1;
        updateMagneticHeadingRegistration();
        glSurfaceView.queueEvent(() -> nativeSetDisplayMode(nativeHandle, mode));
    }

    private synchronized void updateMagneticHeadingRegistration() {
        if (sensorManager == null || rotationVectorSensor == null) return;
        boolean shouldRegister = spatialModeRequested && rendererResumed;
        if (shouldRegister && !magneticHeadingRegistered) {
            magneticHeadingRegistered = sensorManager.registerListener(
                    magneticHeadingListener, rotationVectorSensor,
                    SensorManager.SENSOR_DELAY_GAME);
            if (!magneticHeadingRegistered) {
                LimeLog.warning("Magnetic yaw correction unavailable: sensor registration failed");
            }
        } else if (!shouldRegister && magneticHeadingRegistered) {
            sensorManager.unregisterListener(magneticHeadingListener);
            magneticHeadingRegistered = false;
        }
    }

    public void setViewportOrientation(final int rotation) {
        // Keep Java-side sensor remapping in sync with Cardboard's viewport.
        viewportRotation = rotation;
        glSurfaceView.queueEvent(() -> nativeSetViewportOrientation(nativeHandle, rotation));
    }

    public void setWindowLocked(final boolean locked) {
        glSurfaceView.queueEvent(() -> nativeSetWindowLocked(nativeHandle, locked));
    }

    public void setScreenDistance(float distanceMeters) {
        glSurfaceView.queueEvent(() -> nativeSetScreenDistance(nativeHandle, distanceMeters));
    }

    public void setScreenSize(float sizeMultiplier) {
        nativeSetScreenSize(nativeHandle, sizeMultiplier);
    }

    public void adjustScreenDistance(float deltaMeters) {
        glSurfaceView.queueEvent(() -> nativeAdjustScreenDistance(nativeHandle, deltaMeters));
    }

    public void adjustScreenSize(float deltaMultiplier) {
        nativeAdjustScreenSize(nativeHandle, deltaMultiplier);
    }

    public void adjustScreenPosition(float deltaX, float deltaY) {
        nativeAdjustScreenPosition(nativeHandle, deltaX, deltaY);
    }

    public void adjustScreenRotation(float deltaRadians) {
        glSurfaceView.queueEvent(() -> nativeAdjustScreenRotation(nativeHandle, deltaRadians));
    }

    public void recenterView() {
        // Tracker recenter mutates the same native state read by the GL thread.
        // Serialize it with pose sampling to avoid a data race and mixed frames.
        glSurfaceView.queueEvent(() -> nativeRecenterView(nativeHandle));
    }

    public void setLensScale(float scale) {
        nativeSetLensScale(nativeHandle, scale);
    }

    public void adjustLeftLensOffset(float deltaX) {
        nativeAdjustLeftLensOffset(nativeHandle, deltaX);
    }

    public void adjustRightLensOffset(float deltaX) {
        nativeAdjustRightLensOffset(nativeHandle, deltaX);
    }

    public void setCurvatureMode(int mode) {
        nativeSetCurvatureMode(nativeHandle, mode);
    }

    public void setCurvatureAmount(float percent) {
        nativeSetCurvatureAmount(nativeHandle, percent);
    }

    public void setHorizontalCurvature(float percent) {
        nativeSetHorizontalCurvature(nativeHandle, percent);
    }

    public void setVerticalCurvature(float percent) {
        nativeSetVerticalCurvature(nativeHandle, percent);
    }
 
    public void setSkyboxEnabled(boolean enabled) {
        nativeSetSkyboxEnabled(nativeHandle, enabled);
    }

    public void setSkyboxBrightness(float brightness) {
        nativeSetSkyboxBrightness(nativeHandle, brightness);
    }

    public void loadSkyboxCubemap(Resources resources, int[] resourceIds) {
        if (resourceIds == null || resourceIds.length != 6) {
            LimeLog.warning("loadSkyboxCubemap: expected 6 resource IDs, got " + (resourceIds != null ? resourceIds.length : 0));
            return;
        }

        skyboxResources = resources;
        skyboxResourceIds = resourceIds.clone();

        if (skyboxTextureId != 0) {
            int[] oldTexture = new int[] { skyboxTextureId };
            GLES20.glDeleteTextures(1, oldTexture, 0);
            skyboxTextureId = 0;
        }

        int[] textureIds = new int[1];
        GLES20.glGenTextures(1, textureIds, 0);
        if (textureIds[0] == 0) {
            LimeLog.warning("loadSkyboxCubemap: glGenTextures returned 0");
            nativeSetSkyboxTexture(nativeHandle, 0);
            return;
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_CUBE_MAP, textureIds[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        int[] cubeMapTargets = {
            GLES20.GL_TEXTURE_CUBE_MAP_POSITIVE_X,
            GLES20.GL_TEXTURE_CUBE_MAP_NEGATIVE_X,
            GLES20.GL_TEXTURE_CUBE_MAP_POSITIVE_Y,
            GLES20.GL_TEXTURE_CUBE_MAP_NEGATIVE_Y,
            GLES20.GL_TEXTURE_CUBE_MAP_POSITIVE_Z,
            GLES20.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z
        };

        // Panorama order is based on Minecraft's 6-image panorama strip layout.
        // Minecraft provides images in order: right, left, up, down, front, back.
        // These must be mapped to OpenGL cubemap faces: +X, -X, +Y, -Y, +Z, -Z.
        // See: https://minecraft.wiki/w/Panorama
        // DO NOT change this mapping without understanding Minecraft's panorama format.
        int[] panoramaToFaceMap = {
            1,  // panorama_1 -> +X (right)
            3,  // panorama_3 -> -X (left)
            4,  // panorama_4 -> +Y (up)
            5,  // panorama_5 -> -Y (down)
            0,  // panorama_0 -> +Z (front)
            2   // panorama_2 -> -Z (back)
        };

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        boolean allFacesLoaded = true;

        for (int i = 0; i < 6; i++) {
            int resourceIndex = panoramaToFaceMap[i];
            Bitmap bitmap = null;
            try {
                bitmap = BitmapFactory.decodeResource(resources, resourceIds[resourceIndex], options);
                if (bitmap != null) {
                    bitmap = sanitizeCubemapBorder(bitmap, i);
                    GLUtils.texImage2D(cubeMapTargets[i], 0, bitmap, 0);
                    int glError = GLES20.glGetError();
                    if (glError != GLES20.GL_NO_ERROR) {
                        allFacesLoaded = false;
                        LimeLog.warning("loadSkyboxCubemap: GL error 0x" + Integer.toHexString(glError) + " on face " + i + " size=" + bitmap.getWidth() + "x" + bitmap.getHeight());
                    }
                } else {
                    allFacesLoaded = false;
                    LimeLog.warning("loadSkyboxCubemap: decodeResource returned null for face " + i);
                }
            } catch (Exception e) {
                allFacesLoaded = false;
                LimeLog.warning("loadSkyboxCubemap: failed to load face " + i + ": " + e.getMessage());
            } finally {
                if (bitmap != null) {
                    bitmap.recycle();
                }
            }
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_CUBE_MAP, 0);

        if (!allFacesLoaded) {
            GLES20.glDeleteTextures(1, textureIds, 0);
            nativeSetSkyboxTexture(nativeHandle, 0);
            LimeLog.warning("loadSkyboxCubemap: cubemap upload failed; disabled skybox texture");
            return;
        }

        nativeSetSkyboxTexture(nativeHandle, textureIds[0]);
        skyboxTextureId = textureIds[0];
        LimeLog.info("loadSkyboxCubemap: loaded cubemap with texture ID " + textureIds[0]);
    }
 
    public float getCurvatureAmount() {
        return nativeGetCurvatureAmount(nativeHandle);
    }

    public float getHorizontalCurvature() {
        return nativeGetHorizontalCurvature(nativeHandle);
    }

    public float getVerticalCurvature() {
        return nativeGetVerticalCurvature(nativeHandle);
    }

    public float getScreenSize() {
        return nativeGetScreenSize(nativeHandle);
    }

    private Bitmap sanitizeCubemapBorder(Bitmap bitmap, int faceIndex) {
        if (bitmap == null || bitmap.getConfig() == Bitmap.Config.RGB_565) {
            return bitmap;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width < 2 || height < 2) {
            return bitmap;
        }

        boolean hasTransparentBorder = false;
        for (int x = 0; x < width && !hasTransparentBorder; x++) {
            if (((bitmap.getPixel(x, 0) >>> 24) == 0) || ((bitmap.getPixel(x, height - 1) >>> 24) == 0)) {
                hasTransparentBorder = true;
            }
        }
        for (int y = 0; y < height && !hasTransparentBorder; y++) {
            if (((bitmap.getPixel(0, y) >>> 24) == 0) || ((bitmap.getPixel(width - 1, y) >>> 24) == 0)) {
                hasTransparentBorder = true;
            }
        }

        if (!hasTransparentBorder) {
            return bitmap;
        }

        Bitmap sanitized = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        if (sanitized == null) {
            return bitmap;
        }

        for (int x = 0; x < width; x++) {
            if ((sanitized.getPixel(x, 0) >>> 24) == 0) {
                sanitized.setPixel(x, 0, sanitized.getPixel(x, 1));
            }
            if ((sanitized.getPixel(x, height - 1) >>> 24) == 0) {
                sanitized.setPixel(x, height - 1, sanitized.getPixel(x, height - 2));
            }
        }

        for (int y = 0; y < height; y++) {
            if ((sanitized.getPixel(0, y) >>> 24) == 0) {
                sanitized.setPixel(0, y, sanitized.getPixel(1, y));
            }
            if ((sanitized.getPixel(width - 1, y) >>> 24) == 0) {
                sanitized.setPixel(width - 1, y, sanitized.getPixel(width - 2, y));
            }
        }

        LimeLog.warning("loadSkyboxCubemap: fixed transparent edge pixels on face " + faceIndex);
        bitmap.recycle();
        return sanitized;
    }

    private native long nativeCreate(Context context, AssetManager assetManager);
    private native void nativeDestroy(long handle);
    private native int nativeOnSurfaceCreated(long handle);
    private native void nativeOnSurfaceChanged(long handle, int width, int height);
    private native void nativeSetTextureTransform(long handle, float[] transform);
    private native void nativeSetVideoAspectRatio(long handle, float aspectRatio);
    private native void nativeSetLensDistortionStrength(long handle, float strength);
    private native float nativeOnDrawFrame(long handle);
    private native void nativeSetMagneticHeading(long handle, float headingRadians,
            float headingAccuracyRadians, int sensorAccuracy);
    private native void nativeOnPause(long handle);
    private native void nativeOnResume(long handle);
    private native void nativeSetDisplayMode(long handle, int mode);
    private native void nativeSetViewportOrientation(long handle, int rotation);
    private native void nativeSetWindowLocked(long handle, boolean locked);
    private native void nativeSetScreenDistance(long handle, float distanceMeters);
    private native float nativeGetScreenDistance(long handle);
    private native void nativeSetScreenSize(long handle, float sizeMultiplier);
    private native void nativeAdjustScreenDistance(long handle, float deltaMeters);
    private native void nativeAdjustScreenSize(long handle, float deltaMultiplier);
    private native void nativeAdjustScreenPosition(long handle, float deltaX, float deltaY);
    private native void nativeAdjustScreenRotation(long handle, float deltaRadians);
    private native void nativeRecenterView(long handle);
    private native void nativeSetLensScale(long handle, float scale);
    private native void nativeAdjustLeftLensOffset(long handle, float deltaX);
    private native void nativeAdjustRightLensOffset(long handle, float deltaX);
    private native void nativeSetCurvatureMode(long handle, int mode);
    private native void nativeSetCurvatureAmount(long handle, float percent);
    private native void nativeSetHorizontalCurvature(long handle, float percent);
    private native void nativeSetVerticalCurvature(long handle, float percent);
    private native void nativeSetSkyboxEnabled(long handle, boolean enabled);
    private native void nativeSetSkyboxTexture(long handle, int textureId);
    private native void nativeSetSkyboxBrightness(long handle, float brightness);
    private native void nativeSetCameraTexture(long handle, int textureId);
    private native void nativeSetCameraTextureTransform(long handle, float[] transform);
    private native void nativeSetCameraEnabled(long handle, boolean enabled);
    private native void nativeSetSpatialControlsAutoHide(long handle, boolean enabled);
    private native float nativeGetCurvatureAmount(long handle);
    private native float nativeGetHorizontalCurvature(long handle);
    private native float nativeGetVerticalCurvature(long handle);
    private native float nativeGetScreenSize(long handle);
}
