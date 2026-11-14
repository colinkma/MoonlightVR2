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
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
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
    private String cameraPhysicalId;
    private Float cameraZoomRatio;
    private boolean cameraCaptureDiagnosticsLogged = false;
    private Float logicalCameraZoomRatio;
    private int logicalCameraSensorOrientationDegrees = 90;
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
        cameraCaptureDiagnosticsLogged = false;
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
            CameraCharacteristics logicalCharacteristics =
                    cameraManager.getCameraCharacteristics(cameraId);
            Integer logicalOrientation = logicalCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            logicalCameraSensorOrientationDegrees = logicalOrientation != null ? logicalOrientation : 90;
            logicalCameraZoomRatio = getRequestedZoomRatio(logicalCharacteristics, requestedZoom);

            CameraCharacteristics selectedCharacteristics = cameraPhysicalId != null
                    ? cameraManager.getCameraCharacteristics(cameraPhysicalId)
                    : logicalCharacteristics;
            Integer sensorOrientation = selectedCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            cameraSensorOrientationDegrees = sensorOrientation != null
                    ? sensorOrientation : logicalCameraSensorOrientationDegrees;
            // A physical ultra-wide output already uses its full sensor field
            // of view. Keep the logical-camera zoom request for fallback only.
            cameraZoomRatio = cameraPhysicalId == null ? logicalCameraZoomRatio : null;
            Log.i(TAG, "Selected rear camera logicalId=" + cameraId
                    + " physicalId=" + cameraPhysicalId
                    + " requestedZoomOut=" + requestedZoom
                    + " appliedZoomRatio=" + cameraZoomRatio
                    + " sensorOrientation=" + cameraSensorOrientationDegrees);
            cameraCropRegion = cameraZoomRatio == null
                    ? createZoomOutCompatibleCameraSelectionCrop(cameraId, requestedZoom)
                    : null;

            cameraManager.openCamera(cameraId, createStateCallback(generation), cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "Failed to open camera", e);
            stopCamera();
        }
    }

    private float getWideFieldMetric(CameraCharacteristics characteristics) {
        float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (focalLengths == null || focalLengths.length == 0) {
            return Float.MAX_VALUE;
        }

        android.util.SizeF physicalSize = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        float metric = Float.MAX_VALUE;
        if (physicalSize != null && physicalSize.getWidth() > 0.0f) {
            // Focal length divided by sensor width approximates horizontal
            // field-of-view: a smaller value means a wider lens.
            for (float focalLength : focalLengths) {
                metric = Math.min(metric, focalLength / physicalSize.getWidth());
            }
        } else {
            // Some vendor camera HALs omit SENSOR_INFO_PHYSICAL_SIZE for
            // physical members of a logical multi-camera. Returning MAX_VALUE
            // here made every such ultra-wide lens lose to the first logical
            // camera, so the preview silently stayed on the normal lens.
            // Focal length is an imperfect cross-sensor proxy, but is a better
            // fallback than discarding the lens entirely.
            // Use a conservative 10 mm sensor-width estimate to keep
            // this fallback on roughly the same scale as focalLength /
            // sensorWidth metrics from cameras that report physical size.
            for (float focalLength : focalLengths) {
                metric = Math.min(metric, focalLength / 10.0f);
            }
        }
        return metric;
    }

    private Float getRequestedZoomRatio(CameraCharacteristics characteristics, float requestedZoom) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }
        Range<Float> zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        if (zoomRange == null) {
            Log.i(TAG, "Camera has no CONTROL_ZOOM_RATIO_RANGE; requested zoom-out "
                    + requestedZoom + " cannot be applied as a zoom ratio");
            return null;
        }
        float requestedRatio = 1.0f / Math.max(1.0f, requestedZoom);
        float appliedRatio = Math.max(zoomRange.getLower(), Math.min(requestedRatio, zoomRange.getUpper()));
        Log.i(TAG, "Camera zoom capability: requestedZoomOut=" + requestedZoom
                + " requestedRatio=" + requestedRatio
                + " supportedRange=" + zoomRange
                + " appliedRatio=" + appliedRatio);
        return appliedRatio;
    }

    private String getRearCameraId() {
        try {
            String selectedCameraId = null;
            String selectedPhysicalId = null;
            float widestFieldMetric = Float.MAX_VALUE;
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) {
                    continue;
                }

                float logicalMetric = getWideFieldMetric(characteristics);
                Log.i(TAG, "Rear logical lens candidate id=" + cameraId
                        + " fieldMetric=" + logicalMetric);
                if (selectedCameraId == null || logicalMetric < widestFieldMetric) {
                    selectedCameraId = cameraId;
                    selectedPhysicalId = null;
                    widestFieldMetric = logicalMetric;
                }

                // Many recent phones expose the ultra-wide module only as a
                // physical member of a logical rear camera. Selecting only
                // getCameraIdList() entries silently leaves those devices on
                // the normal wide lens. On Android 10+, inspect each physical
                // lens and bind the preview Surface to the widest one.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    for (String physicalId : characteristics.getPhysicalCameraIds()) {
                        try {
                            CameraCharacteristics physical =
                                    cameraManager.getCameraCharacteristics(physicalId);
                            Integer physicalFacing = physical.get(CameraCharacteristics.LENS_FACING);
                            if (physicalFacing == null
                                    || physicalFacing != CameraCharacteristics.LENS_FACING_BACK) {
                                continue;
                            }
                            float physicalMetric = getWideFieldMetric(physical);
                            Log.i(TAG, "Rear physical lens candidate logicalId=" + cameraId
                                    + " physicalId=" + physicalId
                                    + " fieldMetric=" + physicalMetric);
                            if (physicalMetric < widestFieldMetric) {
                                selectedCameraId = cameraId;
                                selectedPhysicalId = physicalId;
                                widestFieldMetric = physicalMetric;
                            }
                        } catch (CameraAccessException | IllegalArgumentException ignored) {
                            // Some vendors expose physical IDs without
                            // exposing their individual characteristics.
                        }
                    }
                }
            }
            cameraPhysicalId = selectedPhysicalId;
            Log.i(TAG, "Widest rear lens selected: logicalId=" + selectedCameraId
                    + " physicalId=" + selectedPhysicalId + " fieldMetric=" + widestFieldMetric);
            return selectedCameraId;
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to get camera list", e);
        }
        cameraPhysicalId = null;
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
            CameraCaptureSession.StateCallback stateCallback =
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
                            // A few camera HALs report physical ultra-wide
                            // outputs but cannot configure this stream size.
                            // Fall back to the logical rear camera rather
                            // than losing passthrough entirely.
                            if (cameraPhysicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                synchronized (VrCameraManager.this) {
                                    if (generation != cameraGeneration || !isOpening) {
                                        return;
                                    }
                                    cameraPhysicalId = null;
                                    cameraZoomRatio = logicalCameraZoomRatio;
                                    cameraSensorOrientationDegrees = logicalCameraSensorOrientationDegrees;
                                }
                                createCaptureSession(generation, device);
                            } else {
                                stopCameraForGeneration(generation);
                            }
                        }
                    };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && cameraPhysicalId != null) {
                OutputConfiguration output = new OutputConfiguration(targetSurface);
                output.setPhysicalCameraId(cameraPhysicalId);
                device.createCaptureSessionByOutputConfigurations(
                        Collections.singletonList(output), stateCallback, targetHandler);
            } else {
                device.createCaptureSession(surfaces, stateCallback, targetHandler);
            }
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to create camera capture session", e);
            synchronized (this) {
                if (generation != cameraGeneration || !isOpening) {
                    return;
                }
                if (cameraPhysicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    cameraPhysicalId = null;
                    cameraZoomRatio = logicalCameraZoomRatio;
                    cameraSensorOrientationDegrees = logicalCameraSensorOrientationDegrees;
                } else {
                    stopCameraForGeneration(generation);
                    return;
                }
            }
            createCaptureSession(generation, device);
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
            session.setRepeatingRequest(previewRequest.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(CameraCaptureSession captureSession,
                                                       CaptureRequest request,
                                                       TotalCaptureResult result) {
                            synchronized (VrCameraManager.this) {
                                if (cameraCaptureDiagnosticsLogged
                                        || generation != cameraGeneration) {
                                    return;
                                }
                                cameraCaptureDiagnosticsLogged = true;
                            }
                            Float actualZoomRatio = null;
                            String activePhysicalId = null;
                            Rect actualCrop = null;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                actualZoomRatio = result.get(CaptureResult.CONTROL_ZOOM_RATIO);
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                activePhysicalId = result.get(
                                        CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
                            }
                            actualCrop = result.get(CaptureResult.SCALER_CROP_REGION);
                            Log.i(TAG, "Camera capture applied: requestedPhysicalId="
                                    + cameraPhysicalId
                                    + " activePhysicalId=" + activePhysicalId
                                    + " requestedZoomRatio=" + cameraZoomRatio
                                    + " actualZoomRatio=" + actualZoomRatio
                                    + " cropRegion=" + actualCrop
                                    + " sensorOrientation=" + cameraSensorOrientationDegrees);
                        }
                    }, targetHandler);

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
        logicalCameraZoomRatio = null;
        cameraPhysicalId = null;

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
