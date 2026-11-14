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
    private Rect cameraCropRegion;
    // Every start/stop operation gets a new generation. Camera2 callbacks can
    // arrive after close(), so a boolean is not sufficient to distinguish an
    // old open request from a newer one.
    private int cameraGeneration = 0;

    public VrCameraManager(Context context) {
        this.context = context;
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
            cameraCropRegion = createZoomCropRegion(cameraId, requestedZoom);

            cameraManager.openCamera(cameraId, createStateCallback(generation), cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "Failed to open camera", e);
            stopCamera();
        }
    }

    private String getRearCameraId() {
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return cameraId;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to get camera list", e);
        }
        return null;
    }

    private Rect createZoomCropRegion(String cameraId, float requestedZoom) {
        try {
            CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(cameraId);
            Rect activeArray = characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Float maxZoomValue = characteristics.get(
                    CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            if (activeArray == null) {
                return null;
            }
            float maxZoom = maxZoomValue != null ? maxZoomValue : 1.0f;
            float zoom = Math.max(1.0f, Math.min(requestedZoom, maxZoom));
            int cropWidth = Math.max(1, Math.round(activeArray.width() / zoom));
            int cropHeight = Math.max(1, Math.round(activeArray.height() / zoom));
            int left = activeArray.centerX() - cropWidth / 2;
            int top = activeArray.centerY() - cropHeight / 2;
            return new Rect(left, top, left + cropWidth, top + cropHeight);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to calculate camera zoom crop", e);
            return null;
        }
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
            if (cameraCropRegion != null) {
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
