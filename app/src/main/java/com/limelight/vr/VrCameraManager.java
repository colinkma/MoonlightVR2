package com.limelight.vr;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.graphics.Rect;
import android.util.Range;
import android.view.Surface;

import java.util.Collections;
import java.util.List;

public class VrCameraManager {
    private static final String TAG = "VrCameraManager";

    private final Context context;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private Surface cameraSurface;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private volatile boolean isStarted = false;
    private volatile boolean isOpening = false;
    private volatile Runnable onStartedListener;
    private volatile int cameraSensorOrientationDegrees = 90;
    private Float cameraZoomRatio;
    private Rect cameraCropRegion;
    // Every start/stop operation gets a new generation. Camera2 callbacks can
    // arrive after close(), so a boolean is not sufficient to distinguish an
    // old open request from a newer one.
    private int cameraGeneration = 0;

    public VrCameraManager(Context context) {
        this.context = context;
    }

    public int getCameraSensorOrientationDegrees() {
        return cameraSensorOrientationDegrees;
    }

    public synchronized boolean isStarted() {
        return isStarted || isOpening;
    }

    public synchronized void startCamera(Surface surface, float requestedZoom, Runnable onStartedListener) {
        if (isStarted || isOpening) {
            Log.w(TAG, "Camera already started");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && context.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted");
            return;
        }

        final int generation = ++cameraGeneration;
        isOpening = true;
        this.onStartedListener = onStartedListener;
        cameraSurface = surface;
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        cameraThread = new HandlerThread("CameraThread-" + generation);
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        try {
            String cameraId = getRearCameraId();
            if (cameraId == null) {
                Log.e(TAG, "No rear camera found");
                stopCamera();
                return;
            }
            CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            cameraSensorOrientationDegrees = sensorOrientation != null ? sensorOrientation : 90;
            cameraZoomRatio = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Range<Float> zoomRange = cameraCharacteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (zoomRange != null) {
                    // The preference is a 1x-to-4x zoom-out factor. Values above
                    // 1 request a ratio below 1x (wider field of view).
                    float requestedRatio = 1.0f / Math.max(1.0f, requestedZoom);
                    cameraZoomRatio = Math.max(zoomRange.getLower(),
                            Math.min(requestedRatio, zoomRange.getUpper()));
                }
            }
            cameraCropRegion = cameraZoomRatio == null
                    ? createZoomOutCompatibleCameraSelectionCrop(cameraId, requestedZoom)
                    : null;

            cameraManager.openCamera(cameraId, createStateCallback(generation), cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "Failed to open camera", e);
            stopCamera();
        }
    }

    private String getRearCameraId() {
        try {
            String selectedCameraId = null;
            float shortestFocalLength = Float.MAX_VALUE;
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) {
                    continue;
                }
                float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                android.util.SizeF physicalSize = characteristics.get(
                        CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                float widestFieldMetric = Float.MAX_VALUE;
                if (focalLengths != null && physicalSize != null
                        && physicalSize.getWidth() > 0.0f) {
                    for (float focalLength : focalLengths) {
                        // Smaller focal-length / sensor-width means a wider
                        // horizontal field of view. Prefer the widest rear lens.
                        widestFieldMetric = Math.min(widestFieldMetric,
                                focalLength / physicalSize.getWidth());
                    }
                }
                // Prefer the rear ultra-wide module when the device exposes it
                // as a separate camera ID. This is a real wider field of view,
                // unlike shrinking the preview quad or cropping the sensor.
                if (selectedCameraId == null || widestFieldMetric < shortestFocalLength) {
                    selectedCameraId = cameraId;
                    shortestFocalLength = widestFieldMetric;
                }
            }
            return selectedCameraId;
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to get camera list", e);
        }
        return null;
    }

    private Rect createZoomOutCompatibleCameraSelectionCrop(String cameraId, float requestedZoom) {
        // Camera2 crop regions cannot extend beyond the active sensor array.
        // On pre-Android-11 devices, use the widest rear camera available and
        // leave it uncropped rather than silently zooming further in.
        return null;
    }

    private CameraDevice.StateCallback createStateCallback(final int generation) {
        return new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                synchronized (VrCameraManager.this) {
                    if (generation != cameraGeneration || !isOpening) {
                        camera.close();
                        return;
                    }
                    cameraDevice = camera;
                }
                createCaptureSession(generation, camera);
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                boolean currentRequest;
                synchronized (VrCameraManager.this) {
                    currentRequest = generation == cameraGeneration;
                    if (cameraDevice == camera) {
                        cameraDevice = null;
                    }
                }
                camera.close();
                if (currentRequest) {
                    Log.w(TAG, "Camera disconnected");
                    stopCameraForGeneration(generation);
                }
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                boolean currentRequest;
                synchronized (VrCameraManager.this) {
                    currentRequest = generation == cameraGeneration;
                    if (cameraDevice == camera) {
                        cameraDevice = null;
                    }
                }
                camera.close();
                if (currentRequest) {
                    Log.e(TAG, "Camera error: " + error);
                    stopCameraForGeneration(generation);
                }
            }
        };
    }

    private void createCaptureSession(final int generation, final CameraDevice device) {
        final Surface targetSurface;
        final Handler targetHandler;
        synchronized (this) {
            if (generation != cameraGeneration || !isOpening
                    || cameraDevice != device || cameraSurface == null
                    || cameraHandler == null) {
                return;
            }
            targetSurface = cameraSurface;
            targetHandler = cameraHandler;
        }

        try {
            List<Surface> surfaces = Collections.singletonList(targetSurface);
            Log.i(TAG, "Creating capture session: " + targetSurface
                    + " isValid=" + targetSurface.isValid());
            device.createCaptureSession(
                    surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            synchronized (VrCameraManager.this) {
                                if (generation != cameraGeneration || !isOpening
                                        || cameraDevice != device) {
                                    session.close();
                                    return;
                                }
                                captureSession = session;
                            }
                            Log.i(TAG, "Capture session configured successfully");
                            startPreview(generation, device, session, targetSurface, targetHandler);
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            session.close();
                            synchronized (VrCameraManager.this) {
                                if (generation != cameraGeneration) {
                                    return;
                                }
                            }
                            Log.e(TAG, "Camera configuration failed");
                            stopCameraForGeneration(generation);
                        }
                    },
                    targetHandler
            );
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "Failed to create camera capture session", e);
            synchronized (this) {
                if (generation != cameraGeneration) {
                    return;
                }
            }
            stopCameraForGeneration(generation);
        }
    }

    private void startPreview(final int generation, final CameraDevice device,
                              final CameraCaptureSession session, final Surface targetSurface,
                              final Handler targetHandler) {
        try {
            CaptureRequest.Builder previewRequest =
                    device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequest.addTarget(targetSurface);
            previewRequest.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && cameraZoomRatio != null) {
                previewRequest.set(CaptureRequest.CONTROL_ZOOM_RATIO, cameraZoomRatio);
            } else if (cameraCropRegion != null) {
                previewRequest.set(CaptureRequest.SCALER_CROP_REGION, cameraCropRegion);
            }
            session.setRepeatingRequest(previewRequest.build(), null, targetHandler);

            Runnable listener;
            synchronized (this) {
                if (generation != cameraGeneration || !isOpening || captureSession != session
                        || cameraDevice != device) {
                    try {
                        session.stopRepeating();
                    } catch (Exception ignored) {
                    }
                    session.close();
                    return;
                }
                isStarted = true;
                isOpening = false;
                listener = onStartedListener;
                onStartedListener = null;
            }
            Log.i(TAG, "Camera preview started");
            if (listener != null) {
                listener.run();
            }
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "Failed to start preview", e);
            synchronized (this) {
                if (generation != cameraGeneration) {
                    return;
                }
            }
            stopCameraForGeneration(generation);
        }
    }

    private synchronized void stopCameraForGeneration(int generation) {
        if (generation != cameraGeneration) {
            return;
        }
        stopCameraInternal();
    }

    public synchronized void stopCamera() {
        stopCameraInternal();
    }

    private void stopCameraInternal() {
        // Invalidate callbacks before closing resources. A late onOpened() or
        // onConfigured() from this request must not attach to a later session.
        ++cameraGeneration;
        isStarted = false;
        isOpening = false;
        onStartedListener = null;

        CameraCaptureSession oldSession = captureSession;
        CameraDevice oldDevice = cameraDevice;
        HandlerThread oldThread = cameraThread;

        captureSession = null;
        cameraDevice = null;
        cameraSurface = null;
        cameraThread = null;
        cameraHandler = null;
        cameraManager = null;
        cameraCropRegion = null;
        cameraZoomRatio = null;

        if (oldSession != null) {
            try {
                oldSession.stopRepeating();
            } catch (Exception ignored) {
            }
            oldSession.close();
        }
        if (oldDevice != null) {
            oldDevice.close();
        }
        if (oldThread != null) {
            oldThread.quitSafely();
        }
        Log.i(TAG, "Camera stopped");
    }
}
