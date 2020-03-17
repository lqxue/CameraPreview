package com.camera.preview.camera2;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class Camera2Helper {

    private static final String TAG = "Camera2Helper";

    private Point maxPreviewSize;
    private Point minPreviewSize;

    private static final int START_CAMERA = 1000;

    private MyHandler handler = new MyHandler(this);

    private static class MyHandler extends Handler {

        private WeakReference<Camera2Helper> camera2HelperWeakReference;

        MyHandler(Camera2Helper camera2Helper) {
            camera2HelperWeakReference = new WeakReference<>(camera2Helper);
        }

        @Override
        public void dispatchMessage(@NonNull Message msg) {
            super.dispatchMessage(msg);
            if (msg.what == START_CAMERA) {
                if (camera2HelperWeakReference.get() != null) {
                    camera2HelperWeakReference.get().start();
                }
            }
        }
    }

    /**
     * 是否关闭了摄像头
     */
    private AtomicBoolean isClosed = new AtomicBoolean(false);
    /**
     * 是否开始了摄像头
     */
    private AtomicBoolean isStart = new AtomicBoolean(false);
    /**
     * 是否切换了摄像头
     */
    private AtomicBoolean isSwitchCamera = new AtomicBoolean(false);

    /**
     * 前摄像头
     */
    public static final String CAMERA_ID_FRONT = "1";
    /**
     * 后摄像头
     */
    public static final String CAMERA_ID_BACK = "0";


    private String mCameraId;
    private String specificCameraId;
    private Camera2Listener camera2Listener;
    private TextureView mTextureView;
    private int rotation;
    private Point previewViewSize;
    private Point specificPreviewSize;
    private boolean isMirror;
    private Context context;
    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private CameraCaptureSession mCaptureSession;

    /**
     * A reference to the opened {@link CameraDevice}.
     */
    private CameraDevice mCameraDevice;

    private Size mPreviewSize;


    private Camera2Helper(Camera2Helper.Builder builder) {
        mTextureView = builder.previewDisplayView;
        specificCameraId = builder.specificCameraId;
        camera2Listener = builder.camera2Listener;
        rotation = builder.rotation;
        previewViewSize = builder.previewViewSize;
        specificPreviewSize = builder.previewSize;
        maxPreviewSize = builder.maxPreviewSize;
        minPreviewSize = builder.minPreviewSize;
        isMirror = builder.isMirror;
        context = builder.context;
        if (isMirror) {
            mTextureView.setScaleX(-1);
        }
    }

    /**
     * 切换摄像头
     * 切换摄像头逻辑
     * 1.先设置需要切换的CameraId
     * 2.关闭摄像头
     * 3.重新设置之前设置的CameraId并打开摄像头
     * 由于连续快速的关闭马上打开会报一个java.lang.IllegalStateException: CameraDevice was already closed异常
     * 所以定义isClosed 只有未关闭的时候才能关闭
     */
    public void switchCamera() {
        //是否通过点击按钮切换
        isSwitchCamera.set(true);
        //当切换前后摄像头的时候
        if (!isClosed.get()) {
            isClosed.set(true);
            if (CAMERA_ID_BACK.equals(mCameraId)) {
                specificCameraId = CAMERA_ID_FRONT;
            } else if (CAMERA_ID_FRONT.equals(mCameraId)) {
                specificCameraId = CAMERA_ID_BACK;
            }
            stop();
        }
    }

    private int getCameraOri(int rotation, String cameraId) {
        int degrees = rotation * 90;
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
                break;
        }
        int result;
        if (CAMERA_ID_FRONT.equals(cameraId)) {
            result = (mSensorOrientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (mSensorOrientation - degrees + 360) % 360;
        }
        Log.i(TAG, "getCameraOri: " + rotation + " " + result + " " + mSensorOrientation);
        return result;
    }

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            Log.i(TAG, "onSurfaceTextureAvailable: ");
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            Log.i(TAG, "onSurfaceTextureSizeChanged: ");
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            Log.i(TAG, "onSurfaceTextureDestroyed: ");
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }

    };


    private CameraDevice.StateCallback mDeviceStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // 当相机成功打开时回调该方法，接下来可以执行创建预览的操作
            Log.i(TAG, "onOpened: " + cameraDevice + "---------------");
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
            if (camera2Listener != null) {
                camera2Listener.onCameraOpened(cameraDevice, mCameraId, mPreviewSize, getCameraOri(rotation, mCameraId), isMirror);
            }
            isStart.set(true);
            isClosed.set(false);
            isSwitchCamera.set(false);
        }


        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            // 当相机断开连接时回调该方法，应该在此执行释放相机的操作
            Log.i(TAG, "onDisconnected: ");
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            if (camera2Listener != null) {
                camera2Listener.onCameraClosed();
            }
            isStart.set(false);
            isClosed.set(false);
            isSwitchCamera.set(false);
        }

        @Override
        public void onClosed(@NonNull CameraDevice camera) {
            // 当相机关闭时回调该方法，这个方法可以不用实现
            Log.i(TAG, "onClosed: ");
            isClosed.set(true);
            isStart.set(false);
            //只有切换摄像头的时候才自动关闭后马上开启
            if (isSwitchCamera.get()) {
                Message message = Message.obtain();
                message.what = START_CAMERA;
                handler.sendMessage(message);
            }
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            // 当相机打开失败时，应该在此执行释放相机的操作
            //错误码:描述
            //CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:当前相机设备已经在一个更高优先级的地方打开了
            //CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:已打开相机数量到上限了，无法再打开新的相机了
            //CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:由于相关设备策略该相机设备无法打开，详细可见 DevicePolicyManager 的 setCameraDisabled(ComponentName, boolean) 方法
            //CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:相机设备发生了一个致命错误
            //CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:相机服务发生了一个致命错误
            Log.i(TAG, "onError: ");
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;

            if (camera2Listener != null) {
                camera2Listener.onCameraError(new Exception("error occurred, code is " + error));
            }

            isStart.set(false);
            isClosed.set(false);
            isSwitchCamera.set(false);
        }

    };


    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;

    private ImageReader mImageReader;


    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;


    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);


    /**
     * Orientation of the camera sensor
     */
    private int mSensorOrientation;

    private Size getBestSupportedSize(List<Size> sizes) {
        Size defaultSize = sizes.get(0);
        Size[] tempSizes = sizes.toArray(new Size[0]);
        Arrays.sort(tempSizes, new Comparator<Size>() {
            @Override
            public int compare(Size o1, Size o2) {
                if (o1.getWidth() > o2.getWidth()) {
                    return -1;
                } else if (o1.getWidth() == o2.getWidth()) {
                    return o1.getHeight() > o2.getHeight() ? -1 : 1;
                } else {
                    return 1;
                }
            }
        });
        sizes = new ArrayList<>(Arrays.asList(tempSizes));
        for (int i = sizes.size() - 1; i >= 0; i--) {
            if (maxPreviewSize != null) {
                if (sizes.get(i).getWidth() > maxPreviewSize.x || sizes.get(i).getHeight() > maxPreviewSize.y) {
                    sizes.remove(i);
                    continue;
                }
            }
            if (minPreviewSize != null) {
                if (sizes.get(i).getWidth() < minPreviewSize.x || sizes.get(i).getHeight() < minPreviewSize.y) {
                    sizes.remove(i);
                }
            }
        }
        if (sizes.size() == 0) {
            String msg = "can not find suitable previewSize, now using default";
            if (camera2Listener != null) {
                Log.e(TAG, msg);
                camera2Listener.onCameraError(new Exception(msg));
            }
            return defaultSize;
        }
        Size bestSize = sizes.get(0);
        float previewViewRatio;
        if (previewViewSize != null) {
            previewViewRatio = (float) previewViewSize.x / (float) previewViewSize.y;
        } else {
            previewViewRatio = (float) bestSize.getWidth() / (float) bestSize.getHeight();
        }

        if (previewViewRatio > 1) {
            previewViewRatio = 1 / previewViewRatio;
        }

        for (Size s : sizes) {
            if (specificPreviewSize != null && specificPreviewSize.x == s.getWidth() && specificPreviewSize.y == s.getHeight()) {
                return s;
            }
            if (Math.abs((s.getHeight() / (float) s.getWidth()) - previewViewRatio) < Math.abs(bestSize.getHeight() / (float) bestSize.getWidth() - previewViewRatio)) {
                bestSize = s;
            }
        }
        return bestSize;
    }

    public synchronized void start() {
        if (mCameraDevice != null) {
            return;
        }
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (mTextureView.isAvailable()) {
            openCamera();
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    public synchronized void stop() {
        if (mCameraDevice == null) {
            return;
        }
        closeCamera();
        stopBackgroundThread();
    }

    public void release() {
        stop();
        mTextureView = null;
        camera2Listener = null;
        context = null;
        if (handler != null) {
            handler.removeMessages(START_CAMERA);
            handler = null;
        }
    }

    /**
     * 设定相机输出
     *
     * @param cameraManager
     */
    private void setUpCameraOutputs(CameraManager cameraManager) {
        try {
            //优先设置指定摄像头
            if (configCameraParams(cameraManager, specificCameraId)) {
                return;
            }
            //获取可用摄像头列表
            for (String cameraId : cameraManager.getCameraIdList()) {
                if (configCameraParams(cameraManager, cameraId)) {
                    return;
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.

            if (camera2Listener != null) {
                camera2Listener.onCameraError(e);
            }
        }
    }

    /**
     * 配置相机参数
     *
     * @param manager
     * @param cameraId
     * @return
     * @throws CameraAccessException
     */
    private boolean configCameraParams(CameraManager manager, String cameraId) throws CameraAccessException {
        //CameraCharacteristics：摄像头特性。
        //通过CameraManager的getCameraCharacteristics(cameraId)来获取，用于描述特定摄像头所支持的各种特性。
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

        //通过 CameraCharacteristics 类获取相机设备的特性，包括硬件等级的支持等级.
        //LEVEL_3 > FULL > LIMIT > LEGACY
        //
        //对于大多数的手机而言，都会支持到 FULL 或 LIMIT。
        // 当支持到 FULL 等级的相机设备，将拥有比旧 API 强大的新特性，
        // 如 30fps 全高清连拍，帧之间的手动设置，RAW 格式的图片拍摄，快门零延迟以及视频速拍等，否则和旧 API 功能差别不大。
        int hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);

        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return false;
        }
        mPreviewSize = getBestSupportedSize(new ArrayList<Size>(Arrays.asList(map.getOutputSizes(SurfaceTexture.class))));
        mImageReader = ImageReader.newInstance(mPreviewSize.getWidth(), mPreviewSize.getHeight(),
                ImageFormat.YUV_420_888, 2);

        mImageReader.setOnImageAvailableListener(new OnImageAvailableListenerImpl(), mBackgroundHandler);

        mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        mCameraId = cameraId;
        return true;
    }

    private void openCamera() {
        //CameraManager：摄像头管理器。专门用于检测系统摄像头、打开系统摄像头。
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        //设定摄像头参数
        setUpCameraOutputs(cameraManager);
        configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            cameraManager.openCamera(mCameraId, mDeviceStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            if (camera2Listener != null) {
                camera2Listener.onCameraError(e);
            }
        } catch (InterruptedException e) {
            if (camera2Listener != null) {
                camera2Listener.onCameraError(e);
            }
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                //关闭对应的相机设备。
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
            if (camera2Listener != null) {
                camera2Listener.onCameraClosed();
            }
        } catch (InterruptedException e) {
            if (camera2Listener != null) {
                camera2Listener.onCameraError(e);
            }
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            //使用指定模板创建一个 CaptureRequest.Builder 用于新的捕获请求构建。
            //CaptureRequest.Builder createCaptureRequest(int templateType)参数讲解
            //CameraDevice.TEMPLATE_PREVIEWTEMPLATE_PREVIEW:用于创建一个相机预览请求。相机会优先保证高帧率而不是高画质
            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            // 设置连续自动对焦
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            //设置预览输出的 Surface
            mPreviewRequestBuilder.addTarget(surface);
            // mImageReader.getSurface() 是用于拍照输出的 Surface
            mPreviewRequestBuilder.addTarget(mImageReader.getSurface());
            // 生成一个预览的请求
            final CaptureRequest captureRequest = mPreviewRequestBuilder.build();
            // Here, we create a CameraCaptureSession for camera preview.
            //在这里，我们创建了CameraCaptureSession来进行相机预览。
            //参数说明：

            //outputs ： 输出的 Surface 集合，每个 CaptureRequest 的输出 Surface 都应该是 outputs 的一个子元素。
            //callback ： 创建会话的回调。成功时将调用 CameraCaptureSession.StateCallback 的 onConfigured(CameraCaptureSession session) 方法。
            //handler ： 指定回调执行的线程，传 null 时默认使用当前线程的 Looper。
            mCameraDevice.createCaptureSession(
                    Arrays.asList(surface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            Log.i(TAG, "onConfigured: ");
                            // The camera is already closed
                            if (null == mCameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // 开始预览，即设置反复请求
                                mCaptureSession.setRepeatingRequest(captureRequest, null, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            Log.i(TAG, "onConfigureFailed: ");
                            if (camera2Listener != null) {
                                camera2Listener.onCameraError(new Exception("configureFailed"));
                            }
                        }
                    },
                    mBackgroundHandler
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 将必要的{@link Matrix}转换配置为`mTextureView`
     * 在setUpCameraOutputs中确定了摄像机预览大小并且固定了mTextureView大小之后，应调用此方法。
     *
     * @param viewHeight`mTextureView`的高度
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == mTextureView || null == mPreviewSize) {
            return;
        }
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate((90 * (rotation - 2)) % 360, centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        Log.i(TAG, "configureTransform: " + getCameraOri(rotation, mCameraId) + "  " + rotation * 90);
        mTextureView.setTransform(matrix);
    }

    public static final class Builder {

        /**
         * 预览显示的view，目前仅支持textureView
         */
        private TextureView previewDisplayView;

        /**
         * 是否镜像显示，只支持textureView
         */
        private boolean isMirror;
        /**
         * 指定的相机ID
         */
        private String specificCameraId;
        /**
         * 事件回调
         */
        private Camera2Listener camera2Listener;
        /**
         * 屏幕的长宽，在选择最佳相机比例时用到
         */
        private Point previewViewSize;
        /**
         * 传入getWindowManager().getDefaultDisplay().getRotation()的值即可
         */
        private int rotation;
        /**
         * 指定的预览宽高，若系统支持则会以这个预览宽高进行预览
         */
        private Point previewSize;
        /**
         * 最大分辨率
         */
        private Point maxPreviewSize;
        /**
         * 最小分辨率
         */
        private Point minPreviewSize;
        /**
         * 上下文，用于获取CameraManager
         */
        private Context context;

        public Builder() {
        }


        public Builder previewOn(TextureView val) {
            previewDisplayView = val;
            return this;
        }


        public Builder isMirror(boolean val) {
            isMirror = val;
            return this;
        }

        public Builder previewSize(Point val) {
            previewSize = val;
            return this;
        }

        public Builder maxPreviewSize(Point val) {
            maxPreviewSize = val;
            return this;
        }

        public Builder minPreviewSize(Point val) {
            minPreviewSize = val;
            return this;
        }

        public Builder previewViewSize(Point val) {
            previewViewSize = val;
            return this;
        }

        public Builder rotation(int val) {
            rotation = val;
            return this;
        }


        public Builder specificCameraId(String val) {
            specificCameraId = val;
            return this;
        }

        public Builder cameraListener(Camera2Listener val) {
            camera2Listener = val;
            return this;
        }

        public Builder context(Context val) {
            context = val;
            return this;
        }

        public Camera2Helper build() {
            if (previewViewSize == null) {
                Log.e(TAG, "previewViewSize is null, now use default previewSize");
            }
            if (camera2Listener == null) {
                Log.e(TAG, "camera2Listener is null, callback will not be called");
            }
            if (previewDisplayView == null) {
                throw new NullPointerException("you must preview on a textureView or a surfaceView");
            }
            if (maxPreviewSize != null && minPreviewSize != null) {
                if (maxPreviewSize.x < minPreviewSize.x || maxPreviewSize.y < minPreviewSize.y) {
                    throw new IllegalArgumentException("maxPreviewSize must greater than minPreviewSize");
                }
            }
            return new Camera2Helper(this);
        }
    }

    private class OnImageAvailableListenerImpl implements ImageReader.OnImageAvailableListener {
        private byte[] y;
        private byte[] u;
        private byte[] v;
        private ReentrantLock lock = new ReentrantLock();

        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireNextImage();
            // Y:U:V == 4:2:2
            if (camera2Listener != null && image.getFormat() == ImageFormat.YUV_420_888) {
                Image.Plane[] planes = image.getPlanes();
                // 加锁确保y、u、v来源于同一个Image
                lock.lock();
                // 重复使用同一批byte数组，减少gc频率
                if (y == null) {
                    y = new byte[planes[0].getBuffer().limit() - planes[0].getBuffer().position()];
                    u = new byte[planes[1].getBuffer().limit() - planes[1].getBuffer().position()];
                    v = new byte[planes[2].getBuffer().limit() - planes[2].getBuffer().position()];
                }
                if (image.getPlanes()[0].getBuffer().remaining() == y.length) {
                    planes[0].getBuffer().get(y);
                    planes[1].getBuffer().get(u);
                    planes[2].getBuffer().get(v);
                    camera2Listener.onPreview(y, u, v, mPreviewSize, planes[0].getRowStride());
                }
                lock.unlock();
            }
            image.close();
        }
    }
}
