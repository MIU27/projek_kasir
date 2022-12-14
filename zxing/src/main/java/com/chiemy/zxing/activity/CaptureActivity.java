/*
 * Copyright (C) 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.chiemy.zxing.activity;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import com.chiemy.zxing.R;
import com.chiemy.zxing.camera.CameraManager;
import com.chiemy.zxing.decode.DecodeThread;
import com.chiemy.zxing.utils.BeepManager;
import com.chiemy.zxing.utils.CaptureActivityHandler;
import com.google.zxing.Result;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

/**
 * This activity opens the camera and does the actual scanning on a background
 * thread. It draws a viewfinder to help the user place the barcode correctly,
 * shows feedback as the image processing is happening, and then overlays the
 * results when a scan is successful.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 * @author Sean Owen
 */
public class CaptureActivity extends Activity implements SurfaceHolder.Callback {

    private static final String CAMERA_PERMISSION = Manifest.permission.CAMERA;

    private static final String TAG = CaptureActivity.class.getSimpleName();

    private CameraManager cameraManager;
    private CaptureActivityHandler handler;
    //private InactivityTimer inactivityTimer;
    private BeepManager beepManager;

    private SurfaceView scanPreview = null;
    private RelativeLayout scanContainer;
    private RelativeLayout scanCropView;
    private ImageView scanLine;

    private Rect mCropRect = null;
    private boolean isHasSurface = false;
    private Handler redecodeHandler;
    private RedecodeRunnable redecodeRunnable;

    public Handler getHandler() {
        return handler;
    }

    public CameraManager getCameraManager() {
        return cameraManager;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(getLayoutId());

        scanPreview = (SurfaceView) findViewById(R.id.capture_preview);
        scanContainer = (RelativeLayout) findViewById(R.id.capture_container);
        scanCropView = (RelativeLayout) findViewById(R.id.capture_crop_view);
        scanLine = (ImageView) findViewById(R.id.capture_scan_line);

        //inactivityTimer = new InactivityTimer(this);
        beepManager = new BeepManager(this);

        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                TranslateAnimation animation = new TranslateAnimation(Animation.RELATIVE_TO_PARENT, 0.0f, Animation
                        .RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT,
                        0.9f);
                animation.setDuration(4500);
                animation.setRepeatCount(-1);
                animation.setRepeatMode(Animation.RESTART);
                scanLine.startAnimation(animation);
            }
        });

        redecodeHandler = new Handler();
        redecodeRunnable = new RedecodeRunnable(this);
    }

    protected int getLayoutId(){
        return R.layout.activity_capture;
    }

    private void resume() {
        // CameraManager must be initialized here, not in onCreate(). This is
        // necessary because we don't
        // want to open the camera driver and measure the screen size if we're
        // going to show the help on
        // first launch. That led to bugs where the scanning rectangle was the
        // wrong size and partially
        // off screen.
        if (cameraManager == null) {
            cameraManager = new CameraManager(getApplication());
        }

        handler = null;

        if (isHasSurface) {
            // The activity was paused but not stopped, so the surface still
            // exists. Therefore
            // surfaceCreated() won't be called, so init the camera here.
            onInitCamera(scanPreview.getHolder());
        } else {
            // Install the callback and wait for surfaceCreated() to init the
            // camera.
            scanPreview.getHolder().addCallback(this);
        }
        //inactivityTimer.onResume();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resume();
    }

    @Override
    protected void onPause() {
        if (handler != null) {
            handler.quitSynchronously();
            handler = null;
        }
        //inactivityTimer.onPause();
        if (cameraManager != null){
            cameraManager.closeDriver();
        }
        beepManager.close();
        if (!isHasSurface) {
            scanPreview.getHolder().removeCallback(this);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        //inactivityTimer.shutdown();
        super.onDestroy();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (holder == null) {
            Log.e(TAG, "*** WARNING *** surfaceCreated() gave us a null surface!");
        }
        if (!isHasSurface) {
            isHasSurface = true;
            onInitCamera(holder);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        isHasSurface = false;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

    }

    /**
     * A valid barcode has been found, so give an indication of success and show
     * the results.
     *
     * @param rawResult The contents of the barcode.
     * @param bundle    The extras
     */
    public void handleDecode(Result rawResult, Bundle bundle) {
        beepManager.playBeepSoundAndVibrate();
        onDecode(rawResult, bundle);
    }

    protected void onDecode(Result rawResult, Bundle bundle){

    }

    protected void restartPreviewAndDecode(long delay) {
        redecodeHandler.postDelayed(redecodeRunnable, delay);
    }

    protected void onInitCamera(SurfaceHolder surfaceHolder) {
        // ?????????????????????????????????
        int hasCameraPermission = ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION);
        // ????????????????????????
        if (hasCameraPermission != PackageManager.PERMISSION_GRANTED) {
            // ????????????????????????????????????????????????????????????????????????????????????true
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    CAMERA_PERMISSION)) {
                showMessageOKCancel("??????????????????????????????????????????",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                requestCameraPermission();
                            }
                        });
            }else{
                if (!permissionDeny){
                    requestCameraPermission();
                }
            }
        }else{
            initCamera(surfaceHolder);
        }
    }

    private void requestCameraPermission(){
        ActivityCompat.requestPermissions(this,
                new String[] {CAMERA_PERMISSION},
                REQUEST_CODE_ASK_PERMISSIONS);
    }

    private void showMessageOKCancel(String message, DialogInterface.OnClickListener okListener) {
        new android.support.v7.app.AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton("??????", okListener)
                .setNegativeButton("??????", null)
                .create()
                .show();
    }


    protected void initCamera(final SurfaceHolder surfaceHolder) {
        if (surfaceHolder == null) {
            throw new IllegalStateException("No SurfaceHolder provided");
        }
        if (cameraManager.isOpen()) {
            Log.w(TAG, "initCamera() while already open -- late SurfaceView callback?");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    cameraManager.openDriver(surfaceHolder);
                    // Creating the handler starts the preview, which can also throw a
                    // RuntimeException.
                    if (handler == null) {
                        handler = new CaptureActivityHandler(CaptureActivity.this, cameraManager, DecodeThread.BARCODE_MODE);
                    }

                    initCrop();
                } catch (IOException ioe) {
                    Log.w(TAG, ioe);
                    displayFrameworkBugMessageAndExit();
                } catch (RuntimeException e) {
                    // Barcode Scanner has seen crashes in the wild of this variety:
                    // java.?lang.?RuntimeException: Fail to connect to camera service
                    Log.w(TAG, "Unexpected error initializing camera", e);
                    displayFrameworkBugMessageAndExit();
                }
            }
        }).start();

    }

    private void displayFrameworkBugMessageAndExit() {
        // camera error
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.app_name));
        builder.setMessage("Camera error");
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                finish();
            }

        });
        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {

            @Override
            public void onCancel(DialogInterface dialog) {
                finish();
            }
        });
        builder.show();
    }

    public void restartPreviewAfterDelay(long delayMS) {
        if (handler != null) {
            handler.sendEmptyMessageDelayed(R.id.restart_preview, delayMS);
        }
    }

    public Rect getCropRect() {
        return mCropRect;
    }

    /**
     * ??????????????????????????????
     */
    private void initCrop() {
        int cameraWidth = cameraManager.getCameraResolution().y;
        int cameraHeight = cameraManager.getCameraResolution().x;

        /** ??????????????????????????????????????? */
        int[] location = new int[2];
        scanCropView.getLocationInWindow(location);

        int cropLeft = location[0];
        int cropTop = location[1] - getStatusBarHeight();

        int cropWidth = scanCropView.getWidth();
        int cropHeight = scanCropView.getHeight();

        /** ??????????????????????????? */
        int containerWidth = scanContainer.getWidth();
        int containerHeight = scanContainer.getHeight();

        /** ?????????????????????????????????????????????x?????? */
        int x = cropLeft * cameraWidth / containerWidth;
        /** ?????????????????????????????????????????????y?????? */
        int y = cropTop * cameraHeight / containerHeight;

        /** ???????????????????????????????????? */
        int width = cropWidth * cameraWidth / containerWidth;
        /** ???????????????????????????????????? */
        int height = cropHeight * cameraHeight / containerHeight;

        /** ?????????????????????????????? */
        mCropRect = new Rect(x, y, width + x, height + y);
    }

    private int getStatusBarHeight() {
        try {
            Class<?> c = Class.forName("com.android.internal.R$dimen");
            Object obj = c.newInstance();
            Field field = c.getField("status_bar_height");
            int x = Integer.parseInt(field.get(obj).toString());
            return getResources().getDimensionPixelSize(x);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }


    private static class RedecodeRunnable implements Runnable {
        private WeakReference<CaptureActivity> weakReference;

        public RedecodeRunnable(CaptureActivity activity) {
            weakReference = new WeakReference<CaptureActivity>(activity);
        }

        @Override
        public void run() {
            CaptureActivity activity = weakReference.get();
            if (activity != null && activity.handler != null) {
                activity.handler.restartPreviewAndDecode();
            }
        }
    }

    private boolean permissionDeny = false;
    private static final int REQUEST_CODE_ASK_PERMISSIONS = 1;
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CODE_ASK_PERMISSIONS:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission Granted
                    initCamera(scanPreview.getHolder());
                } else {
                    // Permission Denied
                    //Toast.makeText(this, "", Toast.LENGTH_SHORT).show();
                    permissionDeny = true;
                    // ?????????onResume?????????onResume???????????????????????????????????????
                }
                break;
            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }
}