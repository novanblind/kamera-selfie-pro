package com.novankamerasel;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaActionSound;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SurfaceHolder.Callback, SensorEventListener, TextToSpeech.OnInitListener {

    private static final int REQ_PERMISSIONS = 1001;
    private static final String PREF_NAME = "novan_camera_selfie_prefs";

    private SharedPreferences sp;
    private TextToSpeech tts;
    private Vibrator vibrator;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private MediaActionSound actionSound;
    private Handler mainHandler;

    private Camera cam;
    private SurfaceHolder currentHolder;
    private MediaRecorder mediaRecorder;

    private TextView tvStatus;
    private Button btnRecordVideo;
    private Button btnStopRecord;
    private Button btnMode;
    private Button btnSwitchCam;

    private String cameraFacing = "front";
    private int holdDuration = 1000;
    private boolean vibrationEnabled = true;
    private boolean shutterSoundEnabled = true;
    private int selectedVideoWidth = 1280;
    private int selectedVideoHeight = 720;
    private String flashMode = "off";
    private int shakeSensitivity = 22;
    private boolean shakeStopEnabled = true;

    private String currentMode = "photo"; // "photo" atau "video"
    private boolean isCapturing = false;
    private boolean isSettingsOpen = false;
    private boolean isRecordingVideo = false;
    private boolean isCameraOpening = false;
    private boolean isFaceDetectionActive = false;
    private boolean isSensorRegistered = false;

    private Long perfectStartTime = null;
    private File lastVideoFile = null;
    private long videoStartTime = 0;

    private long lastSpeakTime = 0;
    private String lastSpeakText = "";
    private boolean wasFaceDetected = false;
    private long lastNoFaceAlertTime = 0;
    private long lastFaceProcessTime = 0;

    private float lastSensorX = 0, lastSensorY = 0, lastSensorZ = 0;
    private long lastSensorUpdate = 0;
    private long lastShakeTriggerTime = 0;

    private int cachedFrontCamId = -1;
    private int cachedBackCamId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mainHandler = new Handler(Looper.getMainLooper());
        sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        actionSound = new MediaActionSound();
        try { actionSound.load(MediaActionSound.SHUTTER_CLICK); } catch (Exception ignored) {}

        tts = new TextToSpeech(this, this);

        loadSettings();
        buildUi();
        checkAndRequestPermissions();
    }

    private void loadSettings() {
        cameraFacing = sp.getString("camera_facing", "front");
        holdDuration = sp.getInt("hold_duration", 1000);
        vibrationEnabled = sp.getBoolean("vibration_enabled", true);
        shutterSoundEnabled = sp.getBoolean("shutter_sound_enabled", true);
        selectedVideoWidth = sp.getInt("video_width", 1280);
        selectedVideoHeight = sp.getInt("video_height", 720);
        flashMode = sp.getString("camera_flash_mode", "off");
        shakeSensitivity = sp.getInt("shake_sensitivity", 22);
        shakeStopEnabled = sp.getBoolean("shake_stop_enabled", true);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(new Locale("id", "ID"));
            speakGuidance("Kamera selfie by novan aktif.", true);
        }
    }

    private void speakGuidance(String text, boolean force) {
        if (isSettingsOpen || isRecordingVideo || "video".equals(currentMode) || tts == null) return;
        long now = System.currentTimeMillis();

        if (!force) {
            if (text.equals(lastSpeakText)) {
                if (now - lastSpeakTime < 1800) return;
            } else {
                if (now - lastSpeakTime < 500) return;
            }
        }

        lastSpeakTime = now;
        lastSpeakText = text;
        tts.speak(text, force ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, null);
    }

    private void triggerVibrate(long ms) {
        if (!vibrationEnabled || vibrator == null) return;
        try {
            vibrator.vibrate(ms);
        } catch (Exception ignored) {}
    }

    private void playShutterSound() {
        if (!shutterSoundEnabled || actionSound == null) return;
        try {
            actionSound.play(MediaActionSound.SHUTTER_CLICK);
        } catch (Exception ignored) {}
    }

    private void checkAndRequestPermissions() {
        List<String> perms = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.CAMERA);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
        } else {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        if (!perms.isEmpty()) {
            requestPermissions(perms.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (currentHolder != null) {
            startCameraPreview(currentHolder);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        SurfaceView surfaceView = new SurfaceView(this);
        surfaceView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        surfaceView.getHolder().addCallback(this);
        surfaceView.getHolder().setKeepScreenOn(true);
        root.addView(surfaceView);

        tvStatus = new TextView(this);
        tvStatus.setText("Kamera aktif (Mode Foto)");
        tvStatus.setTextSize(17);
        tvStatus.setTextColor(Color.WHITE);
        tvStatus.setBackgroundColor(Color.parseColor("#99000000"));
        tvStatus.setPadding(28, 22, 28, 22);
        tvStatus.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tvParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        tvStatus.setLayoutParams(tvParams);
        root.addView(tvStatus);

        btnRecordVideo = new Button(this);
        btnRecordVideo.setText("Mulai Rekam Video");
        btnRecordVideo.setTextSize(17);
        btnRecordVideo.setTextColor(Color.WHITE);
        btnRecordVideo.setBackgroundColor(Color.parseColor("#16A34A"));
        btnRecordVideo.setPadding(20, 18, 20, 18);
        btnRecordVideo.setVisibility(View.GONE);
        FrameLayout.LayoutParams recParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        recParams.setMargins(24, 0, 24, 86);
        btnRecordVideo.setLayoutParams(recParams);
        btnRecordVideo.setOnClickListener(v -> startVideoRecording());
        root.addView(btnRecordVideo);

        btnStopRecord = new Button(this);
        btnStopRecord.setText("Berhenti Rekam Video");
        btnStopRecord.setTextSize(17);
        btnStopRecord.setTextColor(Color.WHITE);
        btnStopRecord.setBackgroundColor(Color.parseColor("#DC2626"));
        btnStopRecord.setPadding(20, 18, 20, 18);
        btnStopRecord.setVisibility(View.GONE);
        FrameLayout.LayoutParams stopParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        stopParams.setMargins(24, 0, 24, 86);
        btnStopRecord.setLayoutParams(stopParams);
        btnStopRecord.setOnClickListener(v -> stopVideoRecording());
        root.addView(btnStopRecord);

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(Color.parseColor("#CC000000"));
        bottomBar.setPadding(12, 12, 12, 12);
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bottomBar.setLayoutParams(barParams);

        btnMode = new Button(this);
        btnMode.setText("Mode: Foto");
        btnMode.setTextSize(13);
        btnMode.setTextColor(Color.WHITE);
        btnMode.setBackgroundColor(Color.parseColor("#0284C7"));
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        modeParams.setMargins(0, 0, 4, 0);
        btnMode.setLayoutParams(modeParams);
        btnMode.setOnClickListener(v -> toggleCameraMode());

        btnSwitchCam = new Button(this);
        btnSwitchCam.setText("front".equals(cameraFacing) ? "Kamera: Depan" : "Kamera: Belakang");
        btnSwitchCam.setTextSize(13);
        btnSwitchCam.setTextColor(Color.WHITE);
        btnSwitchCam.setBackgroundColor(Color.parseColor("#4F46E5"));
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        switchParams.setMargins(2, 0, 2, 0);
        btnSwitchCam.setLayoutParams(switchParams);
        btnSwitchCam.setOnClickListener(v -> showCameraSelectionDialog());

        Button btnSettings = new Button(this);
        btnSettings.setText("Pengaturan");
        btnSettings.setTextSize(13);
        btnSettings.setTextColor(Color.WHITE);
        btnSettings.setBackgroundColor(Color.parseColor("#2563EB"));
        LinearLayout.LayoutParams setParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        setParams.setMargins(2, 0, 2, 0);
        btnSettings.setLayoutParams(setParams);
        btnSettings.setOnClickListener(v -> showSettingsMenu());

        Button btnClose = new Button(this);
        btnClose.setText("Kembali");
        btnClose.setTextSize(13);
        btnClose.setTextColor(Color.WHITE);
        btnClose.setBackgroundColor(Color.parseColor("#DC2626"));
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        closeParams.setMargins(4, 0, 0, 0);
        btnClose.setLayoutParams(closeParams);
        btnClose.setOnClickListener(v -> finish());

        bottomBar.addView(btnMode);
        bottomBar.addView(btnSwitchCam);
        bottomBar.addView(btnSettings);
        bottomBar.addView(btnClose);
        root.addView(bottomBar);

        setContentView(root);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        currentHolder = holder;
        startCameraPreview(holder);
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        currentHolder = null;
        releaseCamera();
    }

    private void initCameraIds() {
        if (cachedFrontCamId >= 0 && cachedBackCamId >= 0) return;
        int count = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < count; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT && cachedFrontCamId < 0) {
                cachedFrontCamId = i;
            } else if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK && cachedBackCamId < 0) {
                cachedBackCamId = i;
            }
        }
        if (cachedFrontCamId < 0) cachedFrontCamId = 0;
        if (cachedBackCamId < 0) cachedBackCamId = 0;
    }

    private int getCameraId(String facing) {
        initCameraIds();
        return "back".equals(facing) ? cachedBackCamId : cachedFrontCamId;
    }

    private void releaseCamera() {
        if (isRecordingVideo) stopVideoRecording();
        unregisterShakeListener();
        safeStopFaceDetection();
        if (cam != null) {
            try {
                cam.setFaceDetectionListener(null);
                cam.stopPreview();
                cam.release();
            } catch (Exception ignored) {}
            cam = null;
        }
    }

    private void startCameraPreview(SurfaceHolder holder) {
        if (holder == null || isCameraOpening) return;
        isCameraOpening = true;
        tvStatus.setText("Sedang menyiapkan kamera...");

        new Thread(() -> {
            releaseCamera();
            try { Thread.sleep(30); } catch (Exception ignored) {}

            int camId = getCameraId(cameraFacing);
            Camera newCam = null;
            try {
                newCam = Camera.open(camId);
            } catch (Exception e) {
                newCam = null;
            }

            final Camera finalCam = newCam;
            mainHandler.post(() -> {
                isCameraOpening = false;
                if (finalCam == null) {
                    tvStatus.setText("Gagal membuka kamera");
                    speakGuidance("Gagal membuka kamera.", true);
                    return;
                }
                cam = finalCam;
                try {
                    cam.setDisplayOrientation(90);
                    cam.setPreviewDisplay(holder);

                    Camera.Parameters params = cam.getParameters();
                    params.setRotation("front".equals(cameraFacing) ? 270 : 90);

                    List<String> focusModes = params.getSupportedFocusModes();
                    if (focusModes != null) {
                        if ("back".equals(cameraFacing) && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                            params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                        } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                            params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                        }
                    }

                    // Pilih picture size optimal
                    List<Camera.Size> picSizes = params.getSupportedPictureSizes();
                    if (picSizes != null && !picSizes.isEmpty()) {
                        Camera.Size optimal = picSizes.get(0);
                        for (Camera.Size s : picSizes) {
                            int pixels = s.width * s.height;
                            if (pixels <= 4000000 && pixels >= 1500000) {
                                optimal = s;
                                break;
                            }
                        }
                        params.setPictureSize(optimal.width, optimal.height);
                    }

                    cam.setParameters(params);
                    cam.startPreview();

                    if ("photo".equals(currentMode)) {
                        tvStatus.setText("Mode Foto: Arahkan ke wajah");
                        if (params.getMaxNumDetectedFaces() > 0) {
                            cam.setFaceDetectionListener((faces, camera) -> processFaces(faces));
                            mainHandler.postDelayed(this::safeStartFaceDetection, 350);
                            speakGuidance("Kamera " + ("front".equals(cameraFacing) ? "depan" : "belakang") + " siap, arahkan ke wajah.", true);
                        }
                    } else {
                        tvStatus.setText("Mode Video aktif. Siap merekam.");
                    }
                } catch (Exception e) {
                    tvStatus.setText("Error preview kamera");
                }
            });
        }).start();
    }

    private void safeStartFaceDetection() {
        if (cam == null || isSettingsOpen || !"photo".equals(currentMode) || isFaceDetectionActive) return;
        try {
            Camera.Parameters p = cam.getParameters();
            if (p != null && p.getMaxNumDetectedFaces() > 0) {
                cam.startFaceDetection();
                isFaceDetectionActive = true;
            }
        } catch (Exception ignored) {}
    }

    private void safeStopFaceDetection() {
        if (cam == null || !isFaceDetectionActive) return;
        isFaceDetectionActive = false;
        try { cam.stopFaceDetection(); } catch (Exception ignored) {}
    }

    private void processFaces(Camera.Face[] faces) {
        if (isCapturing || isSettingsOpen || !"photo".equals(currentMode) || faces == null) return;
        long now = System.currentTimeMillis();
        if (now - lastFaceProcessTime < 60) return;
        lastFaceProcessTime = now;

        if (faces.length == 0) {
            perfectStartTime = null;
            if (wasFaceDetected) {
                wasFaceDetected = false;
                lastNoFaceAlertTime = now;
                speakGuidance("Wajah terlepas", true);
            } else if (now - lastNoFaceAlertTime >= 2200) {
                lastNoFaceAlertTime = now;
                speakGuidance("Wajah belum terlihat", false);
            }
            return;
        }

        wasFaceDetected = true;
        Camera.Face face = faces[0];
        Rect r = face.rect;

        int cx = (r.left + r.right) / 2;
        int cy = (r.top + r.bottom) / 2;
        int faceW = r.right - r.left;
        int faceH = r.bottom - r.top;
        int faceSize = Math.max(faceW, faceH);

        int screenX = "front".equals(cameraFacing) ? -cy : cy;
        int screenY = -cx;

        String horizontalGuide = "";
        String verticalGuide = "";
        String distanceGuide = "";

        if (screenX < -250) horizontalGuide = "Kurang ke kanan";
        else if (screenX > 250) horizontalGuide = "Kurang ke kiri";

        if (screenY < -270) verticalGuide = "Kurang ke bawah";
        else if (screenY > 270) verticalGuide = "Kurang ke atas";

        if (faceSize < 400) distanceGuide = "Dekatkan ponsel";
        else if (faceSize > 1250) distanceGuide = "Jauhkan sedikit";

        boolean isCentered = horizontalGuide.isEmpty() && verticalGuide.isEmpty() && distanceGuide.isEmpty();

        if (!isCentered) {
            perfectStartTime = null;
            String instruction = !horizontalGuide.isEmpty() ? horizontalGuide : (!verticalGuide.isEmpty() ? verticalGuide : distanceGuide);
            speakGuidance(instruction, false);
        } else {
            if (perfectStartTime == null) {
                perfectStartTime = System.currentTimeMillis();
                speakGuidance("Pas, tahan", true);
                triggerVibrate(40);
            } else {
                long heldTime = System.currentTimeMillis() - perfectStartTime;
                if (heldTime >= holdDuration) {
                    takeSelfiePhoto();
                }
            }
        }
    }

    private void takeSelfiePhoto() {
        if (cam == null || isCapturing || isSettingsOpen || !"photo".equals(currentMode)) return;
        isCapturing = true;
        safeStopFaceDetection();
        triggerVibrate(80);
        playShutterSound();

        try {
            cam.takePicture(() -> triggerVibrate(60), null, (data, camera) -> {
                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File dir = new File(Environment.getExternalStorageDirectory(), "Kamera selfie by novan");
                if (!dir.exists()) dir.mkdirs();
                File photoFile = new File(dir, "Foto_" + cameraFacing + "_" + timeStamp + ".jpg");

                try (FileOutputStream fos = new FileOutputStream(photoFile)) {
                    fos.write(data);
                    fos.flush();
                    scanMedia(photoFile);
                    mainHandler.post(() -> {
                        triggerVibrate(120);
                        isCapturing = false;
                        showMediaResultDialog(photoFile, false);
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        isCapturing = false;
                        speakGuidance("Gagal menyimpan foto.", true);
                    });
                }
            });
        } catch (Exception e) {
            isCapturing = false;
        }
    }

    private void startVideoRecording() {
        if (isRecordingVideo || cam == null || isCapturing || isSettingsOpen) return;
        safeStopFaceDetection();

        try {
            cam.unlock();
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setCamera(cam);
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setOrientationHint("front".equals(cameraFacing) ? 270 : 90);

            if (selectedVideoWidth > 0 && selectedVideoHeight > 0) {
                mediaRecorder.setVideoSize(selectedVideoWidth, selectedVideoHeight);
            }

            File dir = new File(Environment.getExternalStorageDirectory(), "Kamera selfie by novan");
            if (!dir.exists()) dir.mkdirs();
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            lastVideoFile = new File(dir, "Video_" + cameraFacing + "_" + timeStamp + ".mp4");

            mediaRecorder.setOutputFile(lastVideoFile.getAbsolutePath());
            mediaRecorder.setPreviewDisplay(currentHolder.getSurface());
            mediaRecorder.prepare();
            mediaRecorder.start();

            isRecordingVideo = true;
            videoStartTime = System.currentTimeMillis();
            if (shakeStopEnabled) registerShakeListener();

            triggerVibrate(100);
            btnRecordVideo.setVisibility(View.GONE);
            btnStopRecord.setVisibility(View.VISIBLE);
            tvStatus.setText("Sedang merekam video... Tekan tombol Berhenti atau goyangkan HP");
            speakGuidance("Mulai merekam video.", true);
        } catch (Exception e) {
            if (mediaRecorder != null) {
                try { mediaRecorder.release(); } catch (Exception ignored) {}
                mediaRecorder = null;
            }
            try { cam.lock(); } catch (Exception ignored) {}
            speakGuidance("Gagal merekam video.", true);
        }
    }

    private void stopVideoRecording() {
        if (!isRecordingVideo) return;
        long duration = System.currentTimeMillis() - videoStartTime;
        if (duration < 1000) {
            mainHandler.postDelayed(this::stopVideoRecording, 1000 - duration);
            return;
        }

        isRecordingVideo = false;
        unregisterShakeListener();
        triggerVibrate(150);

        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.reset();
                mediaRecorder.release();
                mediaRecorder = null;
            }
        } catch (Exception ignored) {}

        try { if (cam != null) cam.lock(); } catch (Exception ignored) {}

        if (lastVideoFile != null) {
            scanMedia(lastVideoFile);
        }

        btnStopRecord.setVisibility(View.GONE);
        btnRecordVideo.setVisibility(View.VISIBLE);
        tvStatus.setText("Video berhasil disimpan");

        if (lastVideoFile != null) {
            showMediaResultDialog(lastVideoFile, true);
        }
    }

    private void toggleCameraMode() {
        if (isRecordingVideo) {
            speakGuidance("Hentikan rekaman terlebih dahulu.", true);
            return;
        }

        if ("photo".equals(currentMode)) {
            currentMode = "video";
            btnMode.setText("Mode: Video");
            btnMode.setBackgroundColor(Color.parseColor("#7C3AED"));
            btnRecordVideo.setVisibility(View.VISIBLE);
            btnStopRecord.setVisibility(View.GONE);
            tvStatus.setText("Mode Video aktif. Siap merekam.");
            safeStopFaceDetection();
            speakGuidance("Beralih ke mode video. Tekan tombol mulai rekam video.", true);
        } else {
            currentMode = "photo";
            btnMode.setText("Mode: Foto");
            btnMode.setBackgroundColor(Color.parseColor("#0284C7"));
            btnRecordVideo.setVisibility(View.GONE);
            btnStopRecord.setVisibility(View.GONE);
            tvStatus.setText("Mode Foto: Arahkan ke wajah");
            safeStartFaceDetection();
            speakGuidance("Beralih ke mode foto. Arahkan kamera ke wajah.", true);
        }
    }

    private void showCameraSelectionDialog() {
        String[] items = {"Kamera Depan (Selfie)", "Kamera Belakang"};
        int selected = "back".equals(cameraFacing) ? 1 : 0;

        new AlertDialog.Builder(this)
                .setTitle("Pilih Kamera")
                .setSingleChoiceItems(items, selected, (dlg, which) -> {
                    dlg.dismiss();
                    cameraFacing = (which == 0) ? "front" : "back";
                    sp.edit().putString("camera_facing", cameraFacing).apply();
                    btnSwitchCam.setText("front".equals(cameraFacing) ? "Kamera: Depan" : "Kamera: Belakang");
                    if (currentHolder != null) startCameraPreview(currentHolder);
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void showMediaResultDialog(File file, boolean isVideo) {
        String[] actions = {isVideo ? "Putar Video" : "Buka Foto", "Bagikan", "Hapus", "Ubah Nama", "Kembali"};
        new AlertDialog.Builder(this)
                .setTitle(file.getName())
                .setItems(actions, (dlg, which) -> {
                    dlg.dismiss();
                    if (which == 0) {
                        if (isVideo) playVideoDialog(file);
                        else showPhotoDialog(file);
                    } else if (which == 1) {
                        shareMedia(file, isVideo);
                    } else if (which == 2) {
                        if (file.delete()) {
                            speakGuidance("Berkas berhasil dihapus.", true);
                            resumePreview();
                        }
                    } else if (which == 3) {
                        renameMediaDialog(file, isVideo);
                    } else {
                        resumePreview();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void showPhotoDialog(File file) {
        Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        new AlertDialog.Builder(this)
                .setTitle("Pratinjau Foto")
                .setView(iv)
                .setPositiveButton("Kembali", (dlg, which) -> showMediaResultDialog(file, false))
                .show();
    }

    private void playVideoDialog(File file) {
        VideoView vv = new VideoView(this);
        vv.setVideoPath(file.getAbsolutePath());
        MediaController mc = new MediaController(this);
        vv.setMediaController(mc);
        new AlertDialog.Builder(this)
                .setTitle("Putar Video")
                .setView(vv)
                .setPositiveButton("Kembali", (dlg, which) -> {
                    vv.stopPlayback();
                    showMediaResultDialog(file, true);
                })
                .show();
        vv.start();
    }

    private void shareMedia(File file, boolean isVideo) {
        Uri uri = Uri.fromFile(file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(isVideo ? "video/mp4" : "image/jpeg");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        startActivity(Intent.createChooser(intent, "Bagikan via"));
    }

    private void renameMediaDialog(File file, boolean isVideo) {
        EditText et = new EditText(this);
        et.setText(file.getName());
        new AlertDialog.Builder(this)
                .setTitle("Ubah Nama")
                .setView(et)
                .setPositiveButton("Simpan", (dlg, which) -> {
                    String newName = et.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        File newFile = new File(file.getParentFile(), newName);
                        if (file.renameTo(newFile)) {
                            scanMedia(newFile);
                            showMediaResultDialog(newFile, isVideo);
                            return;
                        }
                    }
                    showMediaResultDialog(file, isVideo);
                })
                .setNegativeButton("Batal", (dlg, which) -> showMediaResultDialog(file, isVideo))
                .show();
    }

    private void resumePreview() {
        isCapturing = false;
        perfectStartTime = null;
        wasFaceDetected = false;
        if (cam != null && "photo".equals(currentMode)) {
            cam.startPreview();
            safeStartFaceDetection();
        }
    }

    private void showSettingsMenu() {
        isSettingsOpen = true;
        safeStopFaceDetection();
        String[] items = {
                "Pilihan Kamera (" + ("front".equals(cameraFacing) ? "Depan" : "Belakang") + ")",
                "Waktu Tahan Jepret (" + (holdDuration / 1000f) + "s)",
                "Getaran (" + (vibrationEnabled ? "Aktif" : "Mati") + ")",
                "Suara Jepret (" + (shutterSoundEnabled ? "Aktif" : "Mati") + ")",
                "Goyang Stop Video (" + (shakeStopEnabled ? "Aktif" : "Mati") + ")",
                "Tutup"
        };
        new AlertDialog.Builder(this)
                .setTitle("Pengaturan Kamera")
                .setItems(items, (dlg, which) -> {
                    if (which == 0) showCameraSelectionDialog();
                    else if (which == 1) {
                        holdDuration = (holdDuration == 1000) ? 1500 : 1000;
                        sp.edit().putInt("hold_duration", holdDuration).apply();
                    } else if (which == 2) {
                        vibrationEnabled = !vibrationEnabled;
                        sp.edit().putBoolean("vibration_enabled", vibrationEnabled).apply();
                    } else if (which == 3) {
                        shutterSoundEnabled = !shutterSoundEnabled;
                        sp.edit().putBoolean("shutter_sound_enabled", shutterSoundEnabled).apply();
                    } else if (which == 4) {
                        shakeStopEnabled = !shakeStopEnabled;
                        sp.edit().putBoolean("shake_stop_enabled", shakeStopEnabled).apply();
                    }
                    isSettingsOpen = false;
                    resumePreview();
                })
                .setOnDismissListener(dlg -> {
                    isSettingsOpen = false;
                    resumePreview();
                })
                .show();
    }

    private void registerShakeListener() {
        if (sensorManager != null && accelerometer != null && !isSensorRegistered) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            isSensorRegistered = true;
        }
    }

    private void unregisterShakeListener() {
        if (sensorManager != null && isSensorRegistered) {
            sensorManager.unregisterListener(this);
            isSensorRegistered = false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isRecordingVideo || !shakeStopEnabled) return;
        long curTime = System.currentTimeMillis();
        if (curTime - lastSensorUpdate > 70) {
            lastSensorUpdate = curTime;
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            float delta = Math.abs(x - lastSensorX) + Math.abs(y - lastSensorY) + Math.abs(z - lastSensorZ);
            lastSensorX = x; lastSensorY = y; lastSensorZ = z;

            if (delta > shakeSensitivity && curTime - lastShakeTriggerTime > 2000) {
                lastShakeTriggerTime = curTime;
                mainHandler.post(this::stopVideoRecording);
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void updateUiAndTransitions() {
        // Loop status UI jika diperlukan
    }

    private void scanMedia(File file) {
        if (file == null) return;
        MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, null, null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseCamera();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}