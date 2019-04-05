package tl.cordova.google.mobile.vision.scanner.ui.camera;

// ----------------------------------------------------------------------------
// |  Android Imports
// ----------------------------------------------------------------------------
import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.os.Build;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresPermission;
import android.support.annotation.StringDef;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

// ----------------------------------------------------------------------------
// |  Google Imports
// ----------------------------------------------------------------------------
import com.google.android.gms.common.images.Size;
import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.Frame;

// ----------------------------------------------------------------------------
// |  Java Imports
// ----------------------------------------------------------------------------
import java.io.IOException;
import java.lang.Thread.State;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The CameraSource send the preview frames to the barcode detector.
@SuppressWarnings("deprecation")
public class CameraSource {
  // ----------------------------------------------------------------------------
  // | Public Properties
  // ----------------------------------------------------------------------------
  @SuppressLint("InlinedApi")
  public static final int CAMERA_FACING_BACK = CameraInfo.CAMERA_FACING_BACK;
  @SuppressLint("InlinedApi")
  public static final int CAMERA_FACING_FRONT = CameraInfo.CAMERA_FACING_FRONT;

  // ----------------------------------------------------------------------------
  // | Private Properties
  // ----------------------------------------------------------------------------
  private static final String TAG = "OpenCameraSource";

  /**
   * The dummy surface texture must be assigned a chosen name. Since we never use
   * an OpenGL context, we can choose any ID we want here.
   */
  private static final int DUMMY_TEXTURE_NAME = 100;

  /**
   * If the absolute difference between a preview size aspect ratio and a picture
   * size aspect ratio is less than this tolerance, they are considered to be the
   * same aspect ratio.
   */
  private static final float ASPECT_RATIO_TOLERANCE = 0.01f;

  private Context                 _Context                                    ;
  private final Object            _CameraLock             = new Object()      ;
  private Camera                  _Camera                                     ;
  private int                     _Facing                 = CAMERA_FACING_BACK;
  private int                     _Rotation                                   ;
  private Size                    _PreviewSize                                ;
  private float                   _RequestedFps           = 30.0f             ;
  private int                     _RequestedPreviewWidth  = 1024              ;
  private int                     _RequestedPreviewHeight = 768               ;
  private String                  _FocusMode              = null              ;
  private String                  _FlashMode              = null              ;
  private SurfaceView             _DummySurfaceView                           ;
  private SurfaceTexture          _DummySurfaceTexture                        ;
  private Thread                  _ProcessingThread                           ;
  private FrameProcessingRunnable _FrameProcessor                             ;
  private Map<byte[], ByteBuffer> _BytesToByteBuffer      = new HashMap<>()   ;

  // ----------------------------------------------------------------------------
  // | Helpers
  // ----------------------------------------------------------------------------
  @StringDef({ Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE, Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO,
      Camera.Parameters.FOCUS_MODE_AUTO, Camera.Parameters.FOCUS_MODE_EDOF, Camera.Parameters.FOCUS_MODE_FIXED,
      Camera.Parameters.FOCUS_MODE_INFINITY, Camera.Parameters.FOCUS_MODE_MACRO })
  @Retention(RetentionPolicy.SOURCE)
  private @interface FocusMode {
  };

  @StringDef({ Camera.Parameters.FLASH_MODE_ON, Camera.Parameters.FLASH_MODE_OFF, Camera.Parameters.FLASH_MODE_AUTO,
      Camera.Parameters.FLASH_MODE_RED_EYE, Camera.Parameters.FLASH_MODE_TORCH })
  @Retention(RetentionPolicy.SOURCE)
  private @interface FlashMode {
  };

  public interface ShutterCallback {
    void onShutter();
  }

  public interface PictureCallback {
    void onPictureTaken(byte[] data);
  }

  public interface AutoFocusCallback {
    void onAutoFocus(boolean success);
  }

  public interface AutoFocusMoveCallback {
    void onAutoFocusMoving(boolean start);
  }

  // ----------------------------------------------------------------------------
  // | Builder
  // ----------------------------------------------------------------------------
  public static class Builder {
    private final Detector<?> _Detector;
    private CameraSource _CameraSource = new CameraSource();

    public Builder(Context context, Detector<?> detector) {
      if (context == null) {
        throw new IllegalArgumentException("No context supplied.");
      }
      if (detector == null) {
        throw new IllegalArgumentException("No detector supplied.");
      }

      _Detector = detector;
      _CameraSource._Context = context;
    }

    public Builder setRequestedFps(float fps) {
      if (fps <= 0) {
        throw new IllegalArgumentException("Invalid fps: " + fps);
      }
      _CameraSource._RequestedFps = fps;
      return this;
    }

    public Builder setFocusMode(@FocusMode String mode) {
      _CameraSource._FocusMode = mode;
      return this;
    }

    public Builder setFlashMode(@FlashMode String mode) {
      _CameraSource._FlashMode = mode;
      return this;
    }

    /**
     * Sets the desired width and height of the camera frames in pixels. If the
     * exact desired values are not available options, the best matching available
     * options are selected. Also, we try to select a preview size which corresponds
     * to the aspect ratio of an associated full picture size, if applicable.
     * Default: 1024x768.
     */
    public Builder setRequestedPreviewSize(int width, int height) {
      final int MAX = 1000000;
      if ((width <= 0) || (width > MAX) || (height <= 0) || (height > MAX)) {
        throw new IllegalArgumentException("Invalid preview size: " + width + "x" + height);
      }
      _CameraSource._RequestedPreviewWidth = width;
      _CameraSource._RequestedPreviewHeight = height;
      return this;
    }

    public Builder setFacing(int facing) {
      if ((facing != CAMERA_FACING_BACK) && (facing != CAMERA_FACING_FRONT)) {
        throw new IllegalArgumentException("Invalid camera: " + facing);
      }
      _CameraSource._Facing = facing;
      return this;
    }
    
    public CameraSource build() {
      _CameraSource._FrameProcessor = _CameraSource.new FrameProcessingRunnable(_Detector);
      return _CameraSource;
    }
  }

  // ----------------------------------------------------------------------------
  // | Public Functions
  // ---------------------------------------------------------------------------- 
  public void release() {
    synchronized (_CameraLock) {
      stop();
      _FrameProcessor.release();
    }
  }

  @RequiresPermission(Manifest.permission.CAMERA)
  public CameraSource start() throws IOException {
    synchronized (_CameraLock) {
      if (_Camera != null) {
        return this;
      }

      _Camera = createCamera();

      // SurfaceTexture was introduced in Honeycomb (11), so if we are running and
      // old version of Android. fall back to use SurfaceView.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
        _DummySurfaceTexture = new SurfaceTexture(DUMMY_TEXTURE_NAME);
        _Camera.setPreviewTexture(_DummySurfaceTexture);
      } else {
        _DummySurfaceView = new SurfaceView(_Context);
        _Camera.setPreviewDisplay(_DummySurfaceView.getHolder());
      }
      _Camera.startPreview();

      _ProcessingThread = new Thread(_FrameProcessor);
      _FrameProcessor.setActive(true);
      _ProcessingThread.start();
    }
    return this;
  }

  @RequiresPermission(Manifest.permission.CAMERA)
  public CameraSource start(SurfaceHolder surfaceHolder) throws IOException {
    synchronized (_CameraLock) {
      if (_Camera != null) {
        return this;
      }

      _Camera = createCamera();
      _Camera.setPreviewDisplay(surfaceHolder);
      _Camera.startPreview();

      _ProcessingThread = new Thread(_FrameProcessor);
      _FrameProcessor.setActive(true);
      _ProcessingThread.start();
    }
    return this;
  }

  public void stop() {
    synchronized (_CameraLock) {
      _FrameProcessor.setActive(false);
      if (_ProcessingThread != null) {
        try {
          // Wait for the thread to complete to ensure that we can't have multiple threads
          // executing at the same time (i.e., which would happen if we called start too
          // quickly after stop).
          _ProcessingThread.join();
        } catch (InterruptedException e) {
          Log.d(TAG, "Frame processing thread interrupted on release.");
        }
        _ProcessingThread = null;
      }

      // clear the buffer to prevent oom exceptions
      _BytesToByteBuffer.clear();

      if (_Camera != null) {
        _Camera.stopPreview();
        _Camera.setPreviewCallbackWithBuffer(null);
        try {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            _Camera.setPreviewTexture(null);
          } else {
            _Camera.setPreviewDisplay(null);
          }
        } catch (Exception e) {
          Log.e(TAG, "Failed to clear camera preview: " + e);
        }
        _Camera.release();
        _Camera = null;
      }
    }
  }

  public Size getPreviewSize() {
    return _PreviewSize;
  }

  public int getCameraFacing() {
    return _Facing;
  }

  public int doZoom(float scale) {
    synchronized (_CameraLock) {
      if (_Camera == null) {
        return 0;
      }
      int currentZoom = 0;
      int maxZoom;
      Camera.Parameters parameters = _Camera.getParameters();
      if (!parameters.isZoomSupported()) {
        Log.w(TAG, "Zoom is not supported on this device");
        return currentZoom;
      }
      maxZoom = parameters.getMaxZoom();

      currentZoom = parameters.getZoom() + 1;
      float newZoom;
      if (scale > 1) {
        newZoom = currentZoom + scale * (maxZoom / 10);
      } else {
        newZoom = currentZoom * scale;
      }
      currentZoom = Math.round(newZoom) - 1;
      if (currentZoom < 0) {
        currentZoom = 0;
      } else if (currentZoom > maxZoom) {
        currentZoom = maxZoom;
      }
      parameters.setZoom(currentZoom);
      _Camera.setParameters(parameters);
      return currentZoom;
    }
  }

  public void takePicture(ShutterCallback shutter, PictureCallback jpeg) {
    synchronized (_CameraLock) {
      if (_Camera != null) {
        PictureStartCallback startCallback = new PictureStartCallback();
        startCallback.mDelegate = shutter;
        PictureDoneCallback doneCallback = new PictureDoneCallback();
        doneCallback.mDelegate = jpeg;
        _Camera.takePicture(startCallback, null, null, doneCallback);
      }
    }
  }

  @Nullable
  @FocusMode
  public String getFocusMode() {
    return _FocusMode;
  }

  public boolean setFocusMode(@FocusMode String mode) {
    synchronized (_CameraLock) {
      if (_Camera != null && mode != null) {
        Camera.Parameters parameters = _Camera.getParameters();
        if (parameters.getSupportedFocusModes().contains(mode)) {
          parameters.setFocusMode(mode);
          _Camera.setParameters(parameters);
          _FocusMode = mode;
          return true;
        }
      }

      return false;
    }
  }

  @Nullable
  @FlashMode
  public String getFlashMode() {
    return _FlashMode;
  }

  public boolean setFlashMode(@FlashMode String mode) {
    synchronized (_CameraLock) {
      if (_Camera != null && mode != null) {
        Camera.Parameters parameters = _Camera.getParameters();
        if (parameters.getSupportedFlashModes().contains(mode)) {
          parameters.setFlashMode(mode);
          _Camera.setParameters(parameters);
          _FlashMode = mode;
          return true;
        }
      }

      return false;
    }
  }

  public void autoFocus(@Nullable AutoFocusCallback cb) {
    synchronized (_CameraLock) {
      if (_Camera != null) {
        CameraAutoFocusCallback autoFocusCallback = null;
        if (cb != null) {
          autoFocusCallback = new CameraAutoFocusCallback();
          autoFocusCallback.mDelegate = cb;
        }
        _Camera.autoFocus(autoFocusCallback);
      }
    }
  }

  public void cancelAutoFocus() {
    synchronized (_CameraLock) {
      if (_Camera != null) {
        _Camera.cancelAutoFocus();
      }
    }
  }

  @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
  public boolean setAutoFocusMoveCallback(@Nullable AutoFocusMoveCallback cb) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
      return false;
    }

    synchronized (_CameraLock) {
      if (_Camera != null) {
        CameraAutoFocusMoveCallback autoFocusMoveCallback = null;
        if (cb != null) {
          autoFocusMoveCallback = new CameraAutoFocusMoveCallback();
          autoFocusMoveCallback.mDelegate = cb;
        }
        _Camera.setAutoFocusMoveCallback(autoFocusMoveCallback);
      }
    }

    return true;
  }

  // ==============================================================================================
  // Private
  // ==============================================================================================
  private CameraSource() { // Constructor is private to force creation using the builder class.
  }

  private class PictureStartCallback implements Camera.ShutterCallback {
    private ShutterCallback mDelegate;

    @Override
    public void onShutter() {
      if (mDelegate != null) {
        mDelegate.onShutter();
      }
    }
  }

  private class PictureDoneCallback implements Camera.PictureCallback {
    private PictureCallback mDelegate;

    @Override
    public void onPictureTaken(byte[] data, Camera camera) {
      if (mDelegate != null) {
        mDelegate.onPictureTaken(data);
      }
      synchronized (_CameraLock) {
        if (_Camera != null) {
          _Camera.startPreview();
        }
      }
    }
  }

  private class CameraAutoFocusCallback implements Camera.AutoFocusCallback {
    private AutoFocusCallback mDelegate;

    @Override
    public void onAutoFocus(boolean success, Camera camera) {
      if (mDelegate != null) {
        mDelegate.onAutoFocus(success);
      }
    }
  }

  @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
  private class CameraAutoFocusMoveCallback implements Camera.AutoFocusMoveCallback {
    private AutoFocusMoveCallback mDelegate;

    @Override
    public void onAutoFocusMoving(boolean start, Camera camera) {
      if (mDelegate != null) {
        mDelegate.onAutoFocusMoving(start);
      }
    }
  }

  @SuppressLint("InlinedApi")
  private Camera createCamera() {
    int requestedCameraId = getIdForRequestedCamera(_Facing);
    if (requestedCameraId == -1) {
      throw new RuntimeException("Could not find requested camera.");
    }
    Camera camera = Camera.open(requestedCameraId);

    SizePair sizePair = selectSizePair(camera, _RequestedPreviewWidth, _RequestedPreviewHeight);
    if (sizePair == null) {
      throw new RuntimeException("Could not find suitable preview size.");
    }
    Size pictureSize = sizePair.pictureSize();
    _PreviewSize = sizePair.previewSize();

    int[] previewFpsRange = selectPreviewFpsRange(camera, _RequestedFps);
    if (previewFpsRange == null) {
      throw new RuntimeException("Could not find suitable preview frames per second range.");
    }

    Camera.Parameters parameters = camera.getParameters();

    if (pictureSize != null) {
      parameters.setPictureSize(pictureSize.getWidth(), pictureSize.getHeight());
    }

    parameters.setPreviewSize(_PreviewSize.getWidth(), _PreviewSize.getHeight());
    parameters.setPreviewFpsRange(previewFpsRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
        previewFpsRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);
    parameters.setPreviewFormat(ImageFormat.NV21);

    setRotation(camera, parameters, requestedCameraId);

    if (_FocusMode != null) {
      if (parameters.getSupportedFocusModes().contains(_FocusMode)) {
        parameters.setFocusMode(_FocusMode);
      } else {
        Log.i(TAG, "Camera focus mode: " + _FocusMode + " is not supported on this device.");
      }
    }

    // setting _FocusMode to the one set in the params
    _FocusMode = parameters.getFocusMode();

    if (_FlashMode != null) {
      if (parameters.getSupportedFlashModes() != null) {
        if (parameters.getSupportedFlashModes().contains(_FlashMode)) {
          parameters.setFlashMode(_FlashMode);
        } else {
          Log.i(TAG, "Camera flash mode: " + _FlashMode + " is not supported on this device.");
        }
      }
    }

    // setting _FlashMode to the one set in the params
    _FlashMode = parameters.getFlashMode();

    camera.setParameters(parameters);

    // Four frame buffers are needed for working with the camera:
    //
    // one for the frame that is currently being executed upon in doing detection
    // one for the next pending frame to process immediately upon completing
    // detection
    // two for the frames that the camera uses to populate future preview images
    camera.setPreviewCallbackWithBuffer(new CameraPreviewCallback());
    camera.addCallbackBuffer(createPreviewBuffer(_PreviewSize));
    camera.addCallbackBuffer(createPreviewBuffer(_PreviewSize));
    camera.addCallbackBuffer(createPreviewBuffer(_PreviewSize));
    camera.addCallbackBuffer(createPreviewBuffer(_PreviewSize));

    return camera;
  }

  /**
   * Gets the id for the camera specified by the direction it is facing. Returns
   * -1 if no such camera was found.
   *
   * @param facing the desired camera (front-facing or rear-facing)
   */
  private static int getIdForRequestedCamera(int facing) {
    CameraInfo cameraInfo = new CameraInfo();
    for (int i = 0; i < Camera.getNumberOfCameras(); ++i) {
      Camera.getCameraInfo(i, cameraInfo);
      if (cameraInfo.facing == facing) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Selects the most suitable preview and picture size, given the desired width
   * and height.
   * <p/>
   * Even though we may only need the preview size, it's necessary to find both
   * the preview size and the picture size of the camera together, because these
   * need to have the same aspect ratio. On some hardware, if you would only set
   * the preview size, you will get a distorted image.
   *
   * @param camera        the camera to select a preview size from
   * @param desiredWidth  the desired width of the camera preview frames
   * @param desiredHeight the desired height of the camera preview frames
   * @return the selected preview and picture size pair
   */
  private static SizePair selectSizePair(Camera camera, int desiredWidth, int desiredHeight) {
    List<SizePair> validPreviewSizes = generateValidPreviewSizeList(camera);

    // The method for selecting the best size is to minimize the sum of the
    // differences between
    // the desired values and the actual values for width and height. This is
    // certainly not the
    // only way to select the best size, but it provides a decent tradeoff between
    // using the
    // closest aspect ratio vs. using the closest pixel area.
    SizePair selectedPair = null;
    int minDiff = Integer.MAX_VALUE;
    for (SizePair sizePair : validPreviewSizes) {
      Size size = sizePair.previewSize();
      int diff = Math.abs(size.getWidth() - desiredWidth) + Math.abs(size.getHeight() - desiredHeight);
      if (diff < minDiff) {
        selectedPair = sizePair;
        minDiff = diff;
      }
    }

    return selectedPair;
  }

  /**
   * Stores a preview size and a corresponding same-aspect-ratio picture size. To
   * avoid distorted preview images on some devices, the picture size must be set
   * to a size that is the same aspect ratio as the preview size or the preview
   * may end up being distorted. If the picture size is null, then there is no
   * picture size with the same aspect ratio as the preview size.
   */
  private static class SizePair {
    private Size mPreview;
    private Size mPicture;

    public SizePair(android.hardware.Camera.Size previewSize, android.hardware.Camera.Size pictureSize) {
      mPreview = new Size(previewSize.width, previewSize.height);
      if (pictureSize != null) {
        mPicture = new Size(pictureSize.width, pictureSize.height);
      }
    }

    public Size previewSize() {
      return mPreview;
    }

    @SuppressWarnings("unused")
    public Size pictureSize() {
      return mPicture;
    }
  }

  /**
   * Generates a list of acceptable preview sizes. Preview sizes are not
   * acceptable if there is not a corresponding picture size of the same aspect
   * ratio. If there is a corresponding picture size of the same aspect ratio, the
   * picture size is paired up with the preview size.
   * <p/>
   * This is necessary because even if we don't use still pictures, the still
   * picture size must be set to a size that is the same aspect ratio as the
   * preview size we choose. Otherwise, the preview images may be distorted on
   * some devices.
   */
  private static List<SizePair> generateValidPreviewSizeList(Camera camera) {
    Camera.Parameters parameters = camera.getParameters();
    List<android.hardware.Camera.Size> supportedPreviewSizes = parameters.getSupportedPreviewSizes();
    List<android.hardware.Camera.Size> supportedPictureSizes = parameters.getSupportedPictureSizes();
    List<SizePair> validPreviewSizes = new ArrayList<>();
    for (android.hardware.Camera.Size previewSize : supportedPreviewSizes) {
      float previewAspectRatio = (float) previewSize.width / (float) previewSize.height;

      // By looping through the picture sizes in order, we favor the higher
      // resolutions.
      // We choose the highest resolution in order to support taking the full
      // resolution
      // picture later.
      for (android.hardware.Camera.Size pictureSize : supportedPictureSizes) {
        float pictureAspectRatio = (float) pictureSize.width / (float) pictureSize.height;
        if (Math.abs(previewAspectRatio - pictureAspectRatio) < ASPECT_RATIO_TOLERANCE) {
          validPreviewSizes.add(new SizePair(previewSize, pictureSize));
          break;
        }
      }
    }

    // If there are no picture sizes with the same aspect ratio as any preview
    // sizes, allow all
    // of the preview sizes and hope that the camera can handle it. Probably
    // unlikely, but we
    // still account for it.
    if (validPreviewSizes.size() == 0) {
      Log.w(TAG, "No preview sizes have a corresponding same-aspect-ratio picture size");
      for (android.hardware.Camera.Size previewSize : supportedPreviewSizes) {
        // The null picture size will let us know that we shouldn't set a picture size.
        validPreviewSizes.add(new SizePair(previewSize, null));
      }
    }

    return validPreviewSizes;
  }

  /**
   * Selects the most suitable preview frames per second range, given the desired
   * frames per second.
   *
   * @param camera            the camera to select a frames per second range from
   * @param desiredPreviewFps the desired frames per second for the camera preview
   *                          frames
   * @return the selected preview frames per second range
   */
  private int[] selectPreviewFpsRange(Camera camera, float desiredPreviewFps) {
    // The camera API uses integers scaled by a factor of 1000 instead of
    // floating-point frame
    // rates.
    int desiredPreviewFpsScaled = (int) (desiredPreviewFps * 1000.0f);

    // The method for selecting the best range is to minimize the sum of the
    // differences between
    // the desired value and the upper and lower bounds of the range. This may
    // select a range
    // that the desired value is outside of, but this is often preferred. For
    // example, if the
    // desired frame rate is 29.97, the range (30, 30) is probably more desirable
    // than the
    // range (15, 30).
    int[] selectedFpsRange = null;
    int minDiff = Integer.MAX_VALUE;
    List<int[]> previewFpsRangeList = camera.getParameters().getSupportedPreviewFpsRange();
    for (int[] range : previewFpsRangeList) {
      int deltaMin = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MIN_INDEX];
      int deltaMax = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MAX_INDEX];
      int diff = Math.abs(deltaMin) + Math.abs(deltaMax);
      if (diff < minDiff) {
        selectedFpsRange = range;
        minDiff = diff;
      }
    }
    return selectedFpsRange;
  }

  /**
   * Calculates the correct rotation for the given camera id and sets the rotation
   * in the parameters. It also sets the camera's display orientation and
   * rotation.
   *
   * @param parameters the camera parameters for which to set the rotation
   * @param cameraId   the camera id to set rotation based on
   */
  private void setRotation(Camera camera, Camera.Parameters parameters, int cameraId) {
    WindowManager windowManager = (WindowManager) _Context.getSystemService(Context.WINDOW_SERVICE);
    int degrees = 0;
    int rotation = windowManager.getDefaultDisplay().getRotation();
    switch (rotation) {
    case Surface.ROTATION_0:
      degrees = 0;
      break;
    case Surface.ROTATION_90:
      degrees = 90;
      break;
    case Surface.ROTATION_180:
      degrees = 180;
      break;
    case Surface.ROTATION_270:
      degrees = 270;
      break;
    default:
      Log.e(TAG, "Bad rotation value: " + rotation);
    }

    CameraInfo cameraInfo = new CameraInfo();
    Camera.getCameraInfo(cameraId, cameraInfo);

    int angle;
    int displayAngle;
    if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
      angle = (cameraInfo.orientation + degrees) % 360;
      displayAngle = (360 - angle) % 360; // compensate for it being mirrored
    } else { // back-facing
      angle = (cameraInfo.orientation - degrees + 360) % 360;
      displayAngle = angle;
    }

    // This corresponds to the rotation constants in {@link Frame}.
    _Rotation = angle / 90;

    camera.setDisplayOrientation(displayAngle);
    parameters.setRotation(angle);
  }

  /**
   * Creates one buffer for the camera preview callback. The size of the buffer is
   * based off of the camera preview size and the format of the camera image.
   *
   * @return a new preview buffer of the appropriate size for the current camera
   *         settings
   */
  private byte[] createPreviewBuffer(Size previewSize) {
    int bitsPerPixel = ImageFormat.getBitsPerPixel(ImageFormat.NV21);
    long sizeInBits = previewSize.getHeight() * previewSize.getWidth() * bitsPerPixel;
    int bufferSize = (int) Math.ceil(sizeInBits / 8.0d) + 1;

    //
    // NOTICE: This code only works when using play services v. 8.1 or higher.
    //

    // Creating the byte array this way and wrapping it, as opposed to using
    // .allocate(),
    // should guarantee that there will be an array to work with.
    byte[] byteArray = new byte[bufferSize];
    ByteBuffer buffer = ByteBuffer.wrap(byteArray);
    if (!buffer.hasArray() || (buffer.array() != byteArray)) {
      // I don't think that this will ever happen. But if it does, then we wouldn't be
      // passing the preview content to the underlying detector later.
      throw new IllegalStateException("Failed to create valid buffer for camera source.");
    }

    _BytesToByteBuffer.put(byteArray, buffer);
    return byteArray;
  }

  // ==============================================================================================
  // Frame processing
  // ==============================================================================================

  /**
   * Called when the camera has a new preview frame.
   */
  private class CameraPreviewCallback implements Camera.PreviewCallback {
    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
      _FrameProcessor.setNextFrame(data, camera);
    }
  }

  /**
   * This runnable controls access to the underlying receiver, calling it to
   * process frames when available from the camera. This is designed to run
   * detection on frames as fast as possible (i.e., without unnecessary context
   * switching or waiting on the next frame).
   * <p/>
   * While detection is running on a frame, new frames may be received from the
   * camera. As these frames come in, the most recent frame is held onto as
   * pending. As soon as detection and its associated processing are done for the
   * previous frame, detection on the mostly recently received frame will
   * immediately start on the same thread.
   */
  private class FrameProcessingRunnable implements Runnable {
    private Detector<?> _Detector;
    private long mStartTimeMillis = SystemClock.elapsedRealtime();

    // This lock guards all of the member variables below.
    private final Object mLock = new Object();
    private boolean mActive = true;

    // These pending variables hold the state associated with the new frame awaiting
    // processing.
    private long mPendingTimeMillis;
    private int mPendingFrameId = 0;
    private ByteBuffer mPendingFrameData;

    FrameProcessingRunnable(Detector<?> detector) {
      _Detector = detector;
    }

    /**
     * Releases the underlying receiver. This is only safe to do after the
     * associated thread has completed, which is managed in camera source's release
     * method above.
     */
    @SuppressLint("Assert")
    void release() {
      assert (_ProcessingThread.getState() == State.TERMINATED);
      _Detector.release();
      _Detector = null;
    }

    /**
     * Marks the runnable as active/not active. Signals any blocked threads to
     * continue.
     */
    void setActive(boolean active) {
      synchronized (mLock) {
        mActive = active;
        mLock.notifyAll();
      }
    }

    /**
     * Sets the frame data received from the camera. This adds the previous unused
     * frame buffer (if present) back to the camera, and keeps a pending reference
     * to the frame data for future use.
     */
    void setNextFrame(byte[] data, Camera camera) {
      synchronized (mLock) {
        if (mPendingFrameData != null) {
          camera.addCallbackBuffer(mPendingFrameData.array());
          mPendingFrameData = null;
        }

        if (!_BytesToByteBuffer.containsKey(data)) {
          Log.d(TAG, "Skipping frame.  Could not find ByteBuffer associated with the image " + "data from the camera.");
          return;
        }

        // Timestamp and frame ID are maintained here, which will give downstream code
        // some
        // idea of the timing of frames received and when frames were dropped along the
        // way.
        mPendingTimeMillis = SystemClock.elapsedRealtime() - mStartTimeMillis;
        mPendingFrameId++;
        mPendingFrameData = _BytesToByteBuffer.get(data);

        // Notify the processor thread if it is waiting on the next frame (see below).
        mLock.notifyAll();
      }
    }

    /**
     * As long as the processing thread is active, this executes detection on frames
     * continuously. The next pending frame is either immediately available or
     * hasn't been received yet. Once it is available, we transfer the frame info to
     * local variables and run detection on that frame. It immediately loops back
     * for the next frame without pausing.
     * <p/>
     * If detection takes longer than the time in between new frames from the
     * camera, this will mean that this loop will run without ever waiting on a
     * frame, avoiding any context switching or frame acquisition time latency.
     * <p/>
     * If you find that this is using more CPU than you'd like, you should probably
     * decrease the FPS setting above to allow for some idle time in between frames.
     */
    @Override
    public void run() {
      Frame outputFrame;
      ByteBuffer data;

      while (true) {
        synchronized (mLock) {
          while (mActive && (mPendingFrameData == null)) {
            try {
              // Wait for the next frame to be received from the camera, since we
              // don't have it yet.
              mLock.wait();
            } catch (InterruptedException e) {
              Log.d(TAG, "Frame processing loop terminated.", e);
              return;
            }
          }

          if (!mActive) {
            // Exit the loop once this camera source is stopped or released. We check
            // this here, immediately after the wait() above, to handle the case where
            // setActive(false) had been called, triggering the termination of this
            // loop.
            return;
          }

          outputFrame = new Frame.Builder()
              .setImageData(mPendingFrameData, _PreviewSize.getWidth(), _PreviewSize.getHeight(), ImageFormat.NV21)
              .setId(mPendingFrameId).setTimestampMillis(mPendingTimeMillis).setRotation(_Rotation).build();

          // Hold onto the frame data locally, so that we can use this for detection
          // below. We need to clear mPendingFrameData to ensure that this buffer isn't
          // recycled back to the camera before we are done using that data.
          data = mPendingFrameData;
          mPendingFrameData = null;
        }

        // The code below needs to run outside of synchronization, because this will
        // allow
        // the camera to add pending frame(s) while we are running detection on the
        // current
        // frame.

        try {
          _Detector.receiveFrame(outputFrame);
        } catch (Throwable t) {
          Log.e(TAG, "Exception thrown from receiver.", t);
        } finally {
          _Camera.addCallbackBuffer(data.array());
        }
      }
    }
  }
}
