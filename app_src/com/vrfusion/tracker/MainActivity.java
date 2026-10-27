package com.vrfusion.tracker;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.view.KeyEvent;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;

public class MainActivity extends Activity implements SurfaceHolder.Callback, Runnable, View.OnClickListener {
    private static final String TAG = "VRFusionActivity";
    private static final int VIDEO_PORT = 6004;

    private SurfaceView surfaceView;
    private Surface renderSurface;
    private MediaCodec mediaCodec;
    private Thread videoReceiverThread;
    private volatile boolean isDecoding = false;
    private DatagramSocket videoSocket;

    // Dual-eye HUDs
    private TextView leftEyeHud;
    private TextView rightEyeHud;
    private TextView leftEyeStatus;
    private TextView rightEyeStatus;
    private FrameLayout leftEyeOverlay;
    private FrameLayout rightEyeOverlay;
    private LinearLayout leftEyeInfoBox;
    private LinearLayout rightEyeInfoBox;
    private TextView leftCrosshair;
    private TextView rightCrosshair;
    private FrameLayout rootLayout;
    public static volatile MainActivity activeMainActivity = null;

    private EditText ipInput;
    private Button startBtn;
    private Button centerBtn;
    private Button micBtn;
    private Button hudBtn;
    private Button flipBtn;
    private Button settingsBtn;
    private View centerDivider;
    private boolean isHudVisible = true;
    private boolean isFlippedLandscape = false;
    private VideoWorker currentVideoWorker;
    private LinearLayout bottomBar;
    private LinearLayout stereoContainer;

    // Eye and Display Tuning
    private float eyeScale = 1.0f;
    private float uiScale = 1.0f;
    private int eyeOffsetY = 0;
    private int dividerWidth = 0;
    private int eyeSepX = 0;
    public static volatile int camMode = 0; // 0: OFF, 1: RGB, 2: DEPTH, 3: IR, 4: PIP
    private Button camBtn = null;
    private boolean autoRecenterOnVideo = true;
    private boolean hasAutoRecenteredOnVideo = false;
    private long lastTapTime = 0;

    // Anti-OLED Burn-in Protection
    private static final int[][] ORBIT_OFFSETS = {
        {0, 0}, {2, 1}, {1, 2}, {-1, 2}, {-2, 0}, {-1, -2}, {1, -2}, {2, -1}
    };
    private int orbitIndex = 0;
    private long lastOrbitShiftTime = 0;
    private long videoActiveStartTime = 0;
    private long hudHideTime = 0;
    private float lastStillYaw = 0, lastStillPitch = 0;
    private long lastMoveTime = 0;
    private boolean isScreenDimmed = false;
    private boolean isBrightness100Set = false;
    private long appStartTime = 0;

    private Handler uiHandler;
    private long lastVideoPacketTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appStartTime = System.currentTimeMillis();
        hudHideTime = 0; // Always keep HUD and controls visible on startup until video begins!

        // 1. Force strict fixed Landscape (never flips upside down)
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // 2. Keep screen ON, Fullscreen, and Show When Locked (for VR Goggles)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        enableImmersiveMode();

        // 3. Force 120Hz display mode on Samsung S21 FE
        force120HzDisplayMode();

        // 4. Build Stereo VR UI
        buildStereoVRUI();

        // 5. Load custom display settings (Scale, Y Offset, IPD Divider)
        loadDisplaySettings();

        activeMainActivity = this;
        uiHandler = new Handler(Looper.getMainLooper());
    }

    private void enableImmersiveMode() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    private void force120HzDisplayMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Display display = getWindowManager().getDefaultDisplay();
                if (display != null) {
                    Display.Mode[] modes = display.getSupportedModes();
                    Display.Mode bestMode = null;
                    for (Display.Mode m : modes) {
                        if (m.getRefreshRate() >= 119.0f) {
                            if (bestMode == null || (m.getPhysicalWidth() * m.getPhysicalHeight() > bestMode.getPhysicalWidth() * bestMode.getPhysicalHeight())) {
                                bestMode = m;
                            }
                        }
                    }
                    if (bestMode != null) {
                        WindowManager.LayoutParams lp = getWindow().getAttributes();
                        lp.preferredDisplayModeId = bestMode.getModeId();
                        getWindow().setAttributes(lp);
                        Log.i(TAG, "Forced 120Hz mode: " + bestMode.getPhysicalWidth() + "x" + bestMode.getPhysicalHeight() + " @ " + bestMode.getRefreshRate() + "Hz");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error forcing 120Hz", e);
        }
    }

    private void buildStereoVRUI() {
        if (getWindow() != null) {
            getWindow().getDecorView().setBackgroundColor(Color.BLACK);
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        this.rootLayout = root;

        // 1. Hardware Video Surface (spans the entire landscape screen)
        surfaceView = new SurfaceView(this);
        surfaceView.setZOrderMediaOverlay(true);
        surfaceView.getHolder().addCallback(this);
        surfaceView.getHolder().setFormat(android.graphics.PixelFormat.OPAQUE);
        FrameLayout.LayoutParams surfParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(surfaceView, surfParams);

        // 2. Dual-Eye Container (Side-by-Side: 50% Left Eye, 50% Right Eye)
        stereoContainer = new LinearLayout(this);
        stereoContainer.setOrientation(LinearLayout.HORIZONTAL);
        stereoContainer.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout.LayoutParams stereoParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(stereoContainer, stereoParams);

        // Left Eye (50% width)
        leftEyeOverlay = createEyeView("ЛЕВЫЙ ГЛАЗ (L)");
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        stereoContainer.addView(leftEyeOverlay, leftParams);

        // Center Divider (adjustable width to isolate lenses)
        centerDivider = new View(this);
        centerDivider.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(dividerWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        stereoContainer.addView(centerDivider, divParams);

        // Right Eye (50% width)
        rightEyeOverlay = createEyeView("ПРАВЫЙ ГЛАЗ (R)");
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        stereoContainer.addView(rightEyeOverlay, rightParams);

        // 3. Floating Bottom Control Bar (semi-transparent)
        bottomBar = buildBottomControls();
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        barParams.bottomMargin = 20;
        root.addView(bottomBar, barParams);

        setContentView(root);
    }

    private FrameLayout createEyeView(String eyeName) {
        FrameLayout eye = new FrameLayout(this);
        eye.setBackgroundColor(Color.TRANSPARENT);

        // Optical Crosshair
        TextView crosshair = new TextView(this);
        crosshair.setText("+");
        crosshair.setTextSize(26);
        crosshair.setTextColor(Color.parseColor("#8000F2FE")); // clear cyan
        crosshair.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams crossParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crossParams.gravity = Gravity.CENTER;
        eye.addView(crosshair, crossParams);

        // Eye Info Card
        LinearLayout infoBox = new LinearLayout(this);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        infoBox.setPadding(25, 20, 25, 20);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#90000000")); // pure deep dark
        bg.setCornerRadius(16);
        bg.setStroke(1, Color.parseColor("#3000F2FE"));
        infoBox.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("VRFUSION HMD • " + eyeName);
        title.setTextSize(11);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextColor(Color.parseColor("#00F2FE"));
        infoBox.addView(title);

        TextView status = new TextView(this);
        status.setText("ВИДЕОПОТОК: ОЖИДАНИЕ (120 HZ)");
        status.setTextSize(10);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextColor(Color.parseColor("#FFB800"));
        infoBox.addView(status);

        TextView hud = new TextView(this);
        hud.setText("YAW: 0° | PITCH: 0° | ROLL: 0°\n120 HZ AMOLED | 2340x1080");
        hud.setTextSize(10);
        hud.setTypeface(Typeface.MONOSPACE);
        hud.setTextColor(Color.WHITE);
        infoBox.addView(hud);

        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        infoParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        infoParams.topMargin = 30;
        eye.addView(infoBox, infoParams);

        if ("ЛЕВЫЙ ГЛАЗ (L)".equals(eyeName)) {
            leftCrosshair = crosshair;
            leftEyeHud = hud;
            leftEyeStatus = status;
            leftEyeInfoBox = infoBox;
        } else {
            rightCrosshair = crosshair;
            rightEyeHud = hud;
            rightEyeStatus = status;
            rightEyeInfoBox = infoBox;
        }

        return eye;
    }

    private LinearLayout buildBottomControls() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(20, 10, 20, 10);

        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(Color.parseColor("#C00A0E17"));
        barBg.setCornerRadius(20);
        barBg.setStroke(1, Color.parseColor("#5000F2FE"));
        bar.setBackground(barBg);

        ipInput = new EditText(this);
        ipInput.setText("192.168.31.2");
        ipInput.setTextColor(Color.WHITE);
        ipInput.setTextSize(11);
        ipInput.setPadding(20, 10, 20, 10);
        ipInput.setTypeface(Typeface.MONOSPACE);
        bar.addView(ipInput);

        startBtn = createStyledButton("СТАРТ", Color.parseColor("#00C853"));
        startBtn.setOnClickListener(this);
        bar.addView(startBtn);

        centerBtn = createStyledButton("ЦЕНТР", Color.parseColor("#00B0FF"));
        centerBtn.setOnClickListener(this);
        bar.addView(centerBtn);

        micBtn = createStyledButton("МИКРОФОН", Color.parseColor("#37474F"));
        micBtn.setOnClickListener(this);
        bar.addView(micBtn);

        hudBtn = createStyledButton("HUD", Color.parseColor("#7C4DFF"));
        hudBtn.setOnClickListener(this);
        bar.addView(hudBtn);

        flipBtn = createStyledButton("180°", Color.parseColor("#E65100"));
        flipBtn.setOnClickListener(this);
        bar.addView(flipBtn);

        settingsBtn = createStyledButton("ГЛАЗА ⚙", Color.parseColor("#FF6D00"));
        settingsBtn.setOnClickListener(this);
        bar.addView(settingsBtn);

        camBtn = createStyledButton("📷 КАМЕРА", Color.parseColor("#00838F"));
        camBtn.setOnClickListener(this);
        bar.addView(camBtn);

        return bar;
    }

    private Button createStyledButton(String text, int color) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(10);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(25, 8, 25, 8);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(14);
        btn.setBackground(gd);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = 10;
        btn.setLayoutParams(lp);
        return btn;
    }

    @Override
    public void onClick(View v) {
        hudHideTime = System.currentTimeMillis() + 10000;
        if (v == startBtn) {
            toggleTrackingService();
        } else if (v == centerBtn) {
            VRFusionService.requestRecenter();
            showCenteredNotice("ВЗГЛЯД ОТЦЕНТРОВАН");
        } else if (v == micBtn) {
            Intent intent = new Intent(this, VRFusionService.class);
            intent.setAction(VRFusionService.ACTION_TOGGLE_MIC);
            startService(intent);
        } else if (v == hudBtn) {
            toggleHud();
        } else if (v == flipBtn) {
            VRFusionService.flipYaw180 = !VRFusionService.flipYaw180;
            VRFusionService.requestRecenter();
            saveDisplaySettings();
            showCenteredNotice(!VRFusionService.flipYaw180 ? "ВЗГЛЯД: СТАНДАРТ (0°)" : "ВЗГЛЯД: РАЗВЕРНУТ (180°)");
        } else if (v == settingsBtn) {
            showSettingsDialog();
        } else if (v == camBtn) {
            cycleCamMode();
        }
    }

    public void cycleCamMode() {
        camMode = (camMode + 1) % 5;
        updateCamButtonState();
        showCenteredNotice("КАМЕРА: " + getCamModeFullName(camMode));
        VRFusionService.sendUdpToDriver("SET_CAM_MODE " + camMode);
    }

    public void updateCamButtonState() {
        if (camBtn != null) {
            camBtn.setText("📷 " + getCamModeShortName(camMode));
        }
    }

    public static String getCamModeShortName(int mode) {
        switch (mode) {
            case 1: return "RGB ЦВЕТ";
            case 2: return "3D ГЛУБ";
            case 3: return "IR ИК";
            case 4: return "PIP ОКНО";
            default: return "КАМЕРА";
        }
    }

    public static String getCamModeFullName(int mode) {
        switch (mode) {
            case 1: return "RGB ЦВЕТ (PASSTHROUGH)";
            case 2: return "3D ГЛУБИНА (DEPTH MAP)";
            case 3: return "IR НОЧНОЕ ВИДЕНИЕ";
            case 4: return "PIP (КАРТИНКА В УГЛУ)";
            default: return "ВЫКЛЮЧЕНА (STEAMVR)";
        }
    }

    private void toggleHud() {
        isHudVisible = !isHudVisible;
        hudHideTime = isHudVisible ? (System.currentTimeMillis() + 10000) : 0;
        setHudVisibility(isHudVisible);
        enableImmersiveMode();
    }

    private void toggleTrackingService() {
        Intent intent = new Intent(this, VRFusionService.class);
        if (VRFusionService.isServiceActive) {
            intent.setAction(VRFusionService.ACTION_STOP);
            startService(intent);
            stopVideoReceiver();
            startBtn.setText("СТАРТ");
        } else {
            intent.setAction(VRFusionService.ACTION_START);
            intent.putExtra(VRFusionService.EXTRA_IP, ipInput.getText().toString().trim());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            startVideoReceiver();
            startBtn.setText("СТОП");
        }
    }

    @Override
    public void run() {
        updateVRHud();
        if (uiHandler != null) {
            uiHandler.postDelayed(this, 100);
        }
    }

    private void updateVRHud() {
        enableImmersiveMode();

        long now = System.currentTimeMillis();
        boolean hasVideo = (now - lastVideoPacketTime) < 1500;

        // 1. Anti-OLED Pixel Orbiting: Shift static HUD by +/- 2 pixels every 25 seconds
        if (now - lastOrbitShiftTime > 25000) {
            orbitIndex = (orbitIndex + 1) % ORBIT_OFFSETS.length;
            if (stereoContainer != null) {
                stereoContainer.setTranslationX(ORBIT_OFFSETS[orbitIndex][0]);
                stereoContainer.setTranslationY(ORBIT_OFFSETS[orbitIndex][1]);
            }
            lastOrbitShiftTime = now;
        }

        // 2. Anti-OLED Auto-Hide HUD & 100% Brightness on active video
        if (hasVideo) {
            if (!isBrightness100Set) {
                isBrightness100Set = true;
                setScreenBrightness(1.0f); // 100% brightness on video connection!
            }
            if (videoActiveStartTime == 0) {
                videoActiveStartTime = now;
                hudHideTime = now + 2500; // Auto-hide HUD 2.5 seconds after video begins
            }
        } else {
            isBrightness100Set = false;
            videoActiveStartTime = 0;
        }

        // Auto-hide only when video is actively streaming and timer has expired
        if (hasVideo && hudHideTime > 0 && now >= hudHideTime) {
            hudHideTime = 0;
            setHudVisibility(false);
        }

        // 3. Anti-OLED Stillness Dimmer (headset put on table)
        float dyaw = Math.abs(VRFusionService.yawDeg - lastStillYaw);
        float dpitch = Math.abs(VRFusionService.pitchDeg - lastStillPitch);
        if (dyaw > 1.0f || dpitch > 1.0f) {
            lastStillYaw = VRFusionService.yawDeg;
            lastStillPitch = VRFusionService.pitchDeg;
            lastMoveTime = now;
            if (isScreenDimmed) {
                isScreenDimmed = false;
                setScreenDim(false);
            }
        } else {
            if (!isScreenDimmed && lastMoveTime > 0 && (now - lastMoveTime > 45000)) {
                isScreenDimmed = true;
                setScreenDim(true);
            }
        }

        String hudText = "YAW: " + ((int) VRFusionService.yawDeg) + "° | PITCH: " + ((int) VRFusionService.pitchDeg) + "° | ROLL: " + ((int) VRFusionService.rollDeg) + "°\n"
                + "120 HZ AMOLED | АКБ: " + VRFusionService.batteryPercent + "% | ПАКЕТОВ: " + VRFusionService.activePackets;

        if (leftEyeHud != null) leftEyeHud.setText(hudText);
        if (rightEyeHud != null) rightEyeHud.setText(hudText);

        String statusText = hasVideo ? "ВИДЕОПОТОК: АКТИВЕН (120 HZ H.264)" : "ВИДЕОПОТОК: ОЖИДАНИЕ (120 HZ)";
        int statusColor = hasVideo ? Color.parseColor("#00E676") : Color.parseColor("#FFB800");

        if (leftEyeStatus != null) {
            leftEyeStatus.setText(statusText);
            leftEyeStatus.setTextColor(statusColor);
        }
        if (rightEyeStatus != null) {
            rightEyeStatus.setText(statusText);
            rightEyeStatus.setTextColor(statusColor);
        }

        if (startBtn != null) {
            startBtn.setText(VRFusionService.isServiceActive ? "СТОП" : "СТАРТ");
        }
    }

    private void setScreenBrightness(float brightness) {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = brightness;
            getWindow().setAttributes(lp);
        } catch (Throwable ignored) {}
    }

    private void setScreenDim(boolean dim) {
        setScreenBrightness(dim ? 0.02f : (isBrightness100Set ? 1.0f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE));
    }

    private void setHudVisibility(boolean visible) {
        isHudVisible = visible;
        if (stereoContainer != null) stereoContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (bottomBar != null) bottomBar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
            if (isScreenDimmed) {
                isScreenDimmed = false;
                setScreenDim(false);
            }
            setScreenBrightness(1.0f);

            long now = System.currentTimeMillis();
            if (now - lastTapTime < 350) {
                VRFusionService.requestRecenter();
                showCenteredNotice("ВЗГЛЯД ОТЦЕНТРОВАН");
                lastTapTime = 0;
                enableImmersiveMode();
                return true;
            }
            lastTapTime = now;

            // If click landed on bottomBar, let the buttons handle it directly
            if (bottomBar != null && bottomBar.getVisibility() == View.VISIBLE) {
                int[] loc = new int[2];
                bottomBar.getLocationOnScreen(loc);
                float x = event.getRawX();
                float y = event.getRawY();
                if (x >= loc[0] && x <= (loc[0] + bottomBar.getWidth()) &&
                    y >= loc[1] && y <= (loc[1] + bottomBar.getHeight())) {
                    return super.onTouchEvent(event);
                }
            }

            toggleHud();
            enableImmersiveMode();
            return true;
        }
        return super.onTouchEvent(event);
    }

    // ── SurfaceHolder & MediaCodec Low-Latency H.264 Decoder ────────────────
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        renderSurface = holder.getSurface();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && renderSurface != null) {
            try {
                renderSurface.setFrameRate(120.0f, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
            } catch (Throwable ignored) {}
        }
        initMediaCodec();
        startVideoReceiver();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopVideoReceiver();
        releaseMediaCodec();
    }

    private void initMediaCodec() {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 120);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                format.setInteger(MediaFormat.KEY_MAX_WIDTH, 2560);
                format.setInteger(MediaFormat.KEY_MAX_HEIGHT, 1440);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, 120);
                format.setInteger(MediaFormat.KEY_PRIORITY, 0); // Realtime priority
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try { format.setInteger(MediaFormat.KEY_COLOR_RANGE, 1); } catch (Throwable ignored) {} // COLOR_RANGE_FULL = 1
            }
            if (Build.VERSION.SDK_INT >= 30) {
                try { format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1); } catch (Throwable ignored) {}
            }

            mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mediaCodec.configure(format, renderSurface, null, 0);
            mediaCodec.start();
            Log.i(TAG, "Hardware H.264 MediaCodec started for 120Hz stereo display (adaptive up to 2560x1440)");
        } catch (Exception e) {
            Log.e(TAG, "MediaCodec init failed", e);
        }
    }

    private void releaseMediaCodec() {
        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception ignored) {}
            mediaCodec = null;
        }
    }

    private synchronized void startVideoReceiver() {
        if (currentVideoWorker != null && videoReceiverThread != null && videoReceiverThread.isAlive()) {
            Log.i(TAG, "VideoReceiver is already running, skipping duplicate start");
            return;
        }
        stopVideoReceiver();
        isDecoding = true;
        currentVideoWorker = new VideoWorker(this, VIDEO_PORT);
        videoReceiverThread = new Thread(currentVideoWorker, "VRFusion-VideoWorker");
        videoReceiverThread.setPriority(Thread.MAX_PRIORITY);
        videoReceiverThread.start();
    }

    private synchronized void stopVideoReceiver() {
        isDecoding = false;
        if (currentVideoWorker != null) {
            currentVideoWorker.close();
            currentVideoWorker = null;
        }
        if (videoReceiverThread != null) {
            try { videoReceiverThread.join(300); } catch (Exception ignored) {}
            videoReceiverThread = null;
        }
    }

    public boolean isDecodingActive() { return isDecoding; }
    public MediaCodec getMediaCodec() { return mediaCodec; }
    public void setVideoSocket(DatagramSocket sock) { this.videoSocket = sock; }

    public void markVideoPacketReceived() {
        long now = System.currentTimeMillis();
        if (lastVideoPacketTime == 0 && autoRecenterOnVideo && !hasAutoRecenteredOnVideo) {
            hasAutoRecenteredOnVideo = true;
            if (uiHandler != null) {
                uiHandler.postDelayed(new AutoRecenterTask(this), 1500);
            }
        }
        this.lastVideoPacketTime = now;
    }

    static class AutoRecenterTask implements Runnable {
        private final MainActivity activity;
        AutoRecenterTask(MainActivity activity) { this.activity = activity; }
        @Override
        public void run() {
            VRFusionService.requestRecenter();
            activity.showCenteredNotice("АВТО-ЦЕНТРОВКА ВЗГЛЯДА");
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            VRFusionService.requestRecenter();
            showCenteredNotice("ВЗГЛЯД ОТЦЕНТРОВАН");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showCenteredNotice(final String text) {
        runOnUiThread(new NoticeTask(this, text));
    }

    static class NoticeTask implements Runnable {
        private final MainActivity activity;
        private final String text;
        NoticeTask(MainActivity activity, String text) {
            this.activity = activity;
            this.text = text;
        }
        @Override
        public void run() {
            if (activity.leftEyeStatus != null) {
                activity.leftEyeStatus.setText("✓ " + text);
                activity.leftEyeStatus.setTextColor(Color.parseColor("#00E676"));
            }
            if (activity.rightEyeStatus != null) {
                activity.rightEyeStatus.setText("✓ " + text);
                activity.rightEyeStatus.setTextColor(Color.parseColor("#00E676"));
            }
            if (activity.uiHandler != null) {
                activity.uiHandler.postDelayed(new ResetStatusTask(activity), 2500);
            }
        }
    }

    static class ResetStatusTask implements Runnable {
        private final MainActivity activity;
        ResetStatusTask(MainActivity activity) { this.activity = activity; }
        @Override
        public void run() {
            boolean hasVideo = (System.currentTimeMillis() - activity.lastVideoPacketTime) < 1500;
            String statusText = hasVideo ? "ВИДЕОПОТОК: АКТИВЕН (120 HZ H.264)" : "ВИДЕОПОТОК: ОЖИДАНИЕ (120 HZ)";
            int statusColor = hasVideo ? Color.parseColor("#00E676") : Color.parseColor("#FFB800");
            if (activity.leftEyeStatus != null) {
                activity.leftEyeStatus.setText(statusText);
                activity.leftEyeStatus.setTextColor(statusColor);
            }
            if (activity.rightEyeStatus != null) {
                activity.rightEyeStatus.setText(statusText);
                activity.rightEyeStatus.setTextColor(statusColor);
            }
        }
    }

    private void loadDisplaySettings() {
        try {
            SharedPreferences prefs = getSharedPreferences("vr_settings", MODE_PRIVATE);
            eyeScale = prefs.getFloat("eye_scale", 1.0f);
            uiScale = prefs.getFloat("ui_scale", 1.0f);
            eyeOffsetY = prefs.getInt("eye_offset_y", 0);
            dividerWidth = prefs.getInt("divider_width", 0);
            eyeSepX = prefs.getInt("eye_sep_x", 0);
            VRFusionService.invertRoll = prefs.getBoolean("invert_roll", true);
            autoRecenterOnVideo = prefs.getBoolean("auto_recenter_video", true);
            applyDisplaySettings();
        } catch (Throwable ignored) {}
    }

    private void saveDisplaySettings() {
        try {
            SharedPreferences prefs = getSharedPreferences("vr_settings", MODE_PRIVATE);
            prefs.edit()
                .putFloat("eye_scale", eyeScale)
                .putFloat("ui_scale", uiScale)
                .putInt("eye_offset_y", eyeOffsetY)
                .putInt("divider_width", dividerWidth)
                .putInt("eye_sep_x", eyeSepX)
                .putBoolean("invert_roll", VRFusionService.invertRoll)
                .putBoolean("auto_recenter_video", autoRecenterOnVideo)
                .apply();
        } catch (Throwable ignored) {}
    }

    private void applyDisplaySettings() {
        if (surfaceView != null) {
            surfaceView.setScaleX(eyeScale);
            surfaceView.setScaleY(eyeScale);
            surfaceView.setTranslationY(eyeOffsetY);
        }
        if (bottomBar != null) {
            bottomBar.setPivotX(bottomBar.getWidth() / 2.0f);
            bottomBar.setPivotY(bottomBar.getHeight());
            bottomBar.setScaleX(uiScale);
            bottomBar.setScaleY(uiScale);
        }
        if (centerDivider != null) {
            if (dividerWidth <= 0) {
                centerDivider.setVisibility(View.GONE);
            } else {
                centerDivider.setVisibility(View.VISIBLE);
                centerDivider.setBackgroundColor(Color.BLACK);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) centerDivider.getLayoutParams();
                if (lp != null) {
                    lp.width = dividerWidth;
                    centerDivider.setLayoutParams(lp);
                }
            }
        }

        int screenW = (rootLayout != null && rootLayout.getWidth() > 0) ? rootLayout.getWidth() : 2340;
        int screenH = (rootLayout != null && rootLayout.getHeight() > 0) ? rootLayout.getHeight() : 1080;

        float leftEyeVideoCenterX = screenW * 0.5f - (screenW * 0.25f) * eyeScale - eyeSepX;
        float rightEyeVideoCenterX = screenW * 0.5f + (screenW * 0.25f) * eyeScale + eyeSepX;

        int leftW = (leftEyeOverlay != null && leftEyeOverlay.getWidth() > 0) ? leftEyeOverlay.getWidth() : (screenW / 2);
        int rightW = (rightEyeOverlay != null && rightEyeOverlay.getWidth() > 0) ? rightEyeOverlay.getWidth() : (screenW / 2);

        float leftOverlayCenterX = leftW * 0.5f;
        float rightOverlayCenterX = (screenW - rightW) + rightW * 0.5f;

        float leftShiftX = leftEyeVideoCenterX - leftOverlayCenterX;
        float rightShiftX = rightEyeVideoCenterX - rightOverlayCenterX;

        if (leftCrosshair != null) {
            leftCrosshair.setTranslationX(leftShiftX);
            leftCrosshair.setTranslationY(eyeOffsetY);
            leftCrosshair.setScaleX(1.0f);
            leftCrosshair.setScaleY(1.0f);
        }
        if (rightCrosshair != null) {
            rightCrosshair.setTranslationX(rightShiftX);
            rightCrosshair.setTranslationY(eyeOffsetY);
            rightCrosshair.setScaleX(1.0f);
            rightCrosshair.setScaleY(1.0f);
        }

        if (leftEyeInfoBox != null) {
            leftEyeInfoBox.setTranslationX(leftShiftX);
            leftEyeInfoBox.setPivotX(leftEyeInfoBox.getWidth() / 2.0f);
            leftEyeInfoBox.setPivotY(0.0f);
            leftEyeInfoBox.setScaleX(uiScale);
            leftEyeInfoBox.setScaleY(uiScale);
        }
        if (rightEyeInfoBox != null) {
            rightEyeInfoBox.setTranslationX(rightShiftX);
            rightEyeInfoBox.setPivotX(rightEyeInfoBox.getWidth() / 2.0f);
            rightEyeInfoBox.setPivotY(0.0f);
            rightEyeInfoBox.setScaleX(uiScale);
            rightEyeInfoBox.setScaleY(uiScale);
        }
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        final ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(35, 25, 35, 25);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F20A0E17"));
        bg.setCornerRadius(20);
        bg.setStroke(2, Color.parseColor("#00F2FE"));
        layout.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("⚙ НАСТРОЙКИ ЭКРАНА И ГЛАЗ");
        title.setTextColor(Color.parseColor("#00F2FE"));
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 15);
        layout.addView(title);

        // 1. Scale Row
        TextView scaleLabel = new TextView(this);
        scaleLabel.setTextColor(Color.WHITE);
        scaleLabel.setTextSize(11);
        scaleLabel.setTypeface(Typeface.MONOSPACE);
        scaleLabel.setText("МАСШТАБ ЭКРАНА: " + Math.round(eyeScale * 100) + "%");
        layout.addView(scaleLabel);

        LinearLayout scaleRow = new LinearLayout(this);
        scaleRow.setOrientation(LinearLayout.HORIZONTAL);
        scaleRow.setPadding(0, 4, 0, 10);

        Button btnScaleMinus = createStyledButton("– 5%", Color.parseColor("#37474F"));
        Button btnScalePlus = createStyledButton("+ 5%", Color.parseColor("#37474F"));
        scaleRow.addView(btnScaleMinus);
        scaleRow.addView(btnScalePlus);
        layout.addView(scaleRow);

        // 2. UI Scale Row (Interface size)
        TextView uiLabel = new TextView(this);
        uiLabel.setTextColor(Color.WHITE);
        uiLabel.setTextSize(11);
        uiLabel.setTypeface(Typeface.MONOSPACE);
        uiLabel.setText("МАСШТАБ ИНТЕРФЕЙСА: " + Math.round(uiScale * 100) + "%");
        layout.addView(uiLabel);

        LinearLayout uiRow = new LinearLayout(this);
        uiRow.setOrientation(LinearLayout.HORIZONTAL);
        uiRow.setPadding(0, 4, 0, 10);

        Button btnUiMinus = createStyledButton("– 10%", Color.parseColor("#37474F"));
        Button btnUiPlus = createStyledButton("+ 10%", Color.parseColor("#37474F"));
        uiRow.addView(btnUiMinus);
        uiRow.addView(btnUiPlus);
        layout.addView(uiRow);

        // 3. Vertical Offset (Y)
        TextView yLabel = new TextView(this);
        yLabel.setTextColor(Color.WHITE);
        yLabel.setTextSize(11);
        yLabel.setTypeface(Typeface.MONOSPACE);
        yLabel.setText("СМЕЩЕНИЕ Y (ВЫСОТА): " + eyeOffsetY + " px");
        layout.addView(yLabel);

        LinearLayout yRow = new LinearLayout(this);
        yRow.setOrientation(LinearLayout.HORIZONTAL);
        yRow.setPadding(0, 4, 0, 10);

        Button btnYUp = createStyledButton("▲ ВВЕРХ (–10)", Color.parseColor("#37474F"));
        Button btnYDown = createStyledButton("▼ ВНИЗ (+10)", Color.parseColor("#37474F"));
        yRow.addView(btnYUp);
        yRow.addView(btnYDown);
        layout.addView(yRow);

        // 4. IPD Separation (True Eye Optical Center Distance)
        TextView ipdLabel = new TextView(this);
        ipdLabel.setTextColor(Color.WHITE);
        ipdLabel.setTextSize(11);
        ipdLabel.setTypeface(Typeface.MONOSPACE);
        float currentIpdMm = 63.0f + (eyeSepX / 12.0f);
        ipdLabel.setText(String.format(java.util.Locale.US, "МЕЖГЛАЗНОЕ IPD: %.1f мм (%+d px)", currentIpdMm, eyeSepX));
        layout.addView(ipdLabel);

        LinearLayout ipdRow = new LinearLayout(this);
        ipdRow.setOrientation(LinearLayout.HORIZONTAL);
        ipdRow.setPadding(0, 4, 0, 10);

        Button btnIpdMinus = createStyledButton("– 0.5 мм (–6px)", Color.parseColor("#37474F"));
        Button btnIpdPlus = createStyledButton("+ 0.5 мм (+6px)", Color.parseColor("#37474F"));
        ipdRow.addView(btnIpdMinus);
        ipdRow.addView(btnIpdPlus);
        layout.addView(ipdRow);

        // 5. Divider Width (Center Barrier Line)
        TextView divLabel = new TextView(this);
        divLabel.setTextColor(Color.WHITE);
        divLabel.setTextSize(11);
        divLabel.setTypeface(Typeface.MONOSPACE);
        divLabel.setText("РАЗДЕЛИТЕЛЬ ЭКРАНА: " + dividerWidth + " px" + (dividerWidth == 0 ? " (ВЫКЛ)" : ""));
        layout.addView(divLabel);

        LinearLayout divRow = new LinearLayout(this);
        divRow.setOrientation(LinearLayout.HORIZONTAL);
        divRow.setPadding(0, 4, 0, 10);

        Button btnDivMinus = createStyledButton("– 2 px", Color.parseColor("#37474F"));
        Button btnDivPlus = createStyledButton("+ 2 px", Color.parseColor("#37474F"));
        divRow.addView(btnDivMinus);
        divRow.addView(btnDivPlus);
        layout.addView(divRow);

        // 6. Kinect Camera Mode
        Button btnCamMode = createStyledButton("КАМЕРА KINECT: " + getCamModeShortName(camMode), Color.parseColor("#00838F"));
        LinearLayout.LayoutParams camLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        camLp.topMargin = 4;
        camLp.bottomMargin = 8;
        btnCamMode.setLayoutParams(camLp);
        layout.addView(btnCamMode);

        // 7. Roll Inversion
        Button btnRoll = createStyledButton("НАКЛОН (ROLL): " + (!VRFusionService.invertRoll ? "РЕЖИМ 1 (СТАНДАРТ)" : "РЕЖИМ 2 (ИНВЕРСИЯ)"), 
                !VRFusionService.invertRoll ? Color.parseColor("#2E7D32") : Color.parseColor("#E65100"));
        LinearLayout.LayoutParams rollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rollLp.topMargin = 4;
        rollLp.bottomMargin = 8;
        btnRoll.setLayoutParams(rollLp);
        layout.addView(btnRoll);

        // 8. Auto-Recenter on Video Start Toggle
        Button btnAutoRecenter = createStyledButton("АВТО-ЦЕНТР ПОТОКА: " + (autoRecenterOnVideo ? "ВКЛ (1.5 сек)" : "ВЫКЛ"),
                autoRecenterOnVideo ? Color.parseColor("#0288D1") : Color.parseColor("#546E7A"));
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        autoLp.bottomMargin = 14;
        btnAutoRecenter.setLayoutParams(autoLp);
        layout.addView(btnAutoRecenter);

        // 9. Action buttons: Recenter Now, Reset, Close
        LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.setGravity(Gravity.CENTER);

        Button btnRecenterNow = createStyledButton("ЦЕНТР", Color.parseColor("#00B0FF"));
        Button btnReset = createStyledButton("СБРОС", Color.parseColor("#D32F2F"));
        Button btnClose = createStyledButton("ЗАКРЫТЬ", Color.parseColor("#455A64"));

        actRow.addView(btnRecenterNow);
        actRow.addView(btnReset);
        actRow.addView(btnClose);
        layout.addView(actRow);

        scroll.addView(layout);
        builder.setView(scroll);

        final AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        new SettingsDialogController(this, dialog, scroll, scaleLabel, uiLabel, yLabel, ipdLabel, divLabel,
                btnRoll, btnAutoRecenter, btnCamMode, btnScaleMinus, btnScalePlus,
                btnUiMinus, btnUiPlus, btnYUp, btnYDown, btnIpdMinus, btnIpdPlus,
                btnDivMinus, btnDivPlus, btnRecenterNow, btnReset, btnClose);

        dialog.show();
        updateDialogSize(dialog, scroll);
    }

    public void updateDialogSize(AlertDialog dialog, View scroll) {
        if (dialog == null || dialog.getWindow() == null) return;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int baseW = Math.min((int)(screenW * 0.60f), 1300);
        int targetW = (int)(baseW * Math.min(uiScale, 1.3f));
        dialog.getWindow().setLayout(targetW, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (scroll != null) {
            scroll.setScaleX(uiScale);
            scroll.setScaleY(uiScale);
            scroll.setPivotX(targetW / 2.0f);
            scroll.setPivotY(0.0f);
        }
    }

    static class SettingsDialogController implements View.OnClickListener {
        final MainActivity act;
        final AlertDialog dialog;
        final ScrollView scroll;
        final TextView scaleLabel;
        final TextView uiLabel;
        final TextView yLabel;
        final TextView ipdLabel;
        final TextView divLabel;
        final Button btnRoll;
        final Button btnAutoRecenter;
        final Button btnCamMode;
        final Button btnScaleMinus;
        final Button btnScalePlus;
        final Button btnUiMinus;
        final Button btnUiPlus;
        final Button btnYUp;
        final Button btnYDown;
        final Button btnIpdMinus;
        final Button btnIpdPlus;
        final Button btnDivMinus;
        final Button btnDivPlus;
        final Button btnRecenterNow;
        final Button btnReset;
        final Button btnClose;

        SettingsDialogController(MainActivity act, AlertDialog dialog, ScrollView scroll,
                                 TextView scaleLabel, TextView uiLabel, TextView yLabel, TextView ipdLabel, TextView divLabel,
                                 Button btnRoll, Button btnAutoRecenter, Button btnCamMode,
                                 Button btnScaleMinus, Button btnScalePlus,
                                 Button btnUiMinus, Button btnUiPlus,
                                 Button btnYUp, Button btnYDown,
                                 Button btnIpdMinus, Button btnIpdPlus,
                                 Button btnDivMinus, Button btnDivPlus,
                                 Button btnRecenterNow, Button btnReset, Button btnClose) {
            this.act = act;
            this.dialog = dialog;
            this.scroll = scroll;
            this.scaleLabel = scaleLabel;
            this.uiLabel = uiLabel;
            this.yLabel = yLabel;
            this.ipdLabel = ipdLabel;
            this.divLabel = divLabel;
            this.btnRoll = btnRoll;
            this.btnAutoRecenter = btnAutoRecenter;
            this.btnCamMode = btnCamMode;
            this.btnScaleMinus = btnScaleMinus;
            this.btnScalePlus = btnScalePlus;
            this.btnUiMinus = btnUiMinus;
            this.btnUiPlus = btnUiPlus;
            this.btnYUp = btnYUp;
            this.btnYDown = btnYDown;
            this.btnIpdMinus = btnIpdMinus;
            this.btnIpdPlus = btnIpdPlus;
            this.btnDivMinus = btnDivMinus;
            this.btnDivPlus = btnDivPlus;
            this.btnRecenterNow = btnRecenterNow;
            this.btnReset = btnReset;
            this.btnClose = btnClose;

            btnScaleMinus.setOnClickListener(this);
            btnScalePlus.setOnClickListener(this);
            btnUiMinus.setOnClickListener(this);
            btnUiPlus.setOnClickListener(this);
            btnYUp.setOnClickListener(this);
            btnYDown.setOnClickListener(this);
            btnIpdMinus.setOnClickListener(this);
            btnIpdPlus.setOnClickListener(this);
            btnDivMinus.setOnClickListener(this);
            btnDivPlus.setOnClickListener(this);
            btnCamMode.setOnClickListener(this);
            btnRoll.setOnClickListener(this);
            btnAutoRecenter.setOnClickListener(this);
            btnRecenterNow.setOnClickListener(this);
            btnReset.setOnClickListener(this);
            btnClose.setOnClickListener(this);
        }

        @Override
        public void onClick(View v) {
            if (v == btnScaleMinus) {
                act.eyeScale = Math.max(0.70f, Math.round((act.eyeScale - 0.05f) * 100.0f) / 100.0f);
                scaleLabel.setText("МАСШТАБ ЭКРАНА: " + Math.round(act.eyeScale * 100) + "%");
                act.applyDisplaySettings();
                act.saveDisplaySettings();
            } else if (v == btnScalePlus) {
                act.eyeScale = Math.min(1.50f, Math.round((act.eyeScale + 0.05f) * 100.0f) / 100.0f);
                scaleLabel.setText("МАСШТАБ ЭКРАНА: " + Math.round(act.eyeScale * 100) + "%");
                act.applyDisplaySettings();
                act.saveDisplaySettings();
            } else if (v == btnUiMinus) {
                act.uiScale = Math.max(0.60f, Math.round((act.uiScale - 0.10f) * 100.0f) / 100.0f);
                uiLabel.setText("МАСШТАБ ИНТЕРФЕЙСА: " + Math.round(act.uiScale * 100) + "%");
                act.updateDialogSize(dialog, scroll);
                act.applyDisplaySettings();
                act.saveDisplaySettings();
            } else if (v == btnUiPlus) {
                act.uiScale = Math.min(1.60f, Math.round((act.uiScale + 0.10f) * 100.0f) / 100.0f);
                uiLabel.setText("МАСШТАБ ИНТЕРФЕЙСА: " + Math.round(act.uiScale * 100) + "%");
                act.updateDialogSize(dialog, scroll);
                act.applyDisplaySettings();
                act.saveDisplaySettings();
            } else if (v == btnYUp) {
                act.eyeOffsetY = Math.max(-300, act.eyeOffsetY - 10);
                yLabel.setText("СМЕЩЕНИЕ Y (ВЫСОТА): " + act.eyeOffsetY + " px");
                act.applyDisplaySettings();
                act.saveDisplaySettings();
            } else if (v == btnYDown) {
                act.eyeOffsetY = Math.min(300, act.eyeOffsetY + 10);
                yLabel.setText("СМЕЩЕНИЕ Y (ВЫСОТА): " + act.eyeOffsetY + " px");
                act.applyDisplaySettings();
                act.saveDisplaySettings();
            } else if (v == btnIpdMinus) {
                act.eyeSepX = Math.max(-100, act.eyeSepX - 6);
                float ipdMm = 63.0f + (act.eyeSepX / 12.0f);
                ipdLabel.setText(String.format(java.util.Locale.US, "МЕЖГЛАЗНОЕ IPD: %.1f мм (%+d px)", ipdMm, act.eyeSepX));
                act.applyDisplaySettings();
                act.saveDisplaySettings();
                VRFusionService.sendUdpToDriver("SET_IPD_SHIFT " + act.eyeSepX);
            } else if (v == btnIpdPlus) {
                act.eyeSepX = Math.min(100, act.eyeSepX + 6);
                float ipdMm = 63.0f + (act.eyeSepX / 12.0f);
                ipdLabel.setText(String.format(java.util.Locale.US, "МЕЖГЛАЗНОЕ IPD: %.1f мм (%+d px)", ipdMm, act.eyeSepX));
                act.applyDisplaySettings();
                act.saveDisplaySettings();
                VRFusionService.sendUdpToDriver("SET_IPD_SHIFT " + act.eyeSepX);
            } else if (v == btnDivMinus) {
                act.dividerWidth = Math.max(0, act.dividerWidth - 2);
                divLabel.setText("РАЗДЕЛИТЕЛЬ ЭКРАНА: " + act.dividerWidth + " px" + (act.dividerWidth == 0 ? " (ВЫКЛ)" : ""));
                act.applyDisplaySettings();
                act.saveDisplaySettings();
            } else if (v == btnDivPlus) {
                act.dividerWidth = Math.min(100, act.dividerWidth + 2);
                divLabel.setText("РАЗДЕЛИТЕЛЬ ЭКРАНА: " + act.dividerWidth + " px" + (act.dividerWidth == 0 ? " (ВЫКЛ)" : ""));
                act.applyDisplaySettings();
                act.saveDisplaySettings();
            } else if (v == btnCamMode) {
                act.cycleCamMode();
                btnCamMode.setText("КАМЕРА KINECT: " + getCamModeShortName(camMode));
            } else if (v == btnRoll) {
                VRFusionService.invertRoll = !VRFusionService.invertRoll;
                btnRoll.setText("НАКЛОН (ROLL): " + (VRFusionService.invertRoll ? "РЕЖИМ 1 (СТАНДАРТ)" : "РЕЖИМ 2 (ИНВЕРСИЯ)"));
                GradientDrawable gd = new GradientDrawable();
                gd.setColor(VRFusionService.invertRoll ? Color.parseColor("#2E7D32") : Color.parseColor("#E65100"));
                gd.setCornerRadius(14);
                btnRoll.setBackground(gd);
                act.saveDisplaySettings();
            } else if (v == btnAutoRecenter) {
                act.autoRecenterOnVideo = !act.autoRecenterOnVideo;
                btnAutoRecenter.setText("АВТО-ЦЕНТР ПОТОКА: " + (act.autoRecenterOnVideo ? "ВКЛ (1.5 сек)" : "ВЫКЛ"));
                GradientDrawable gd = new GradientDrawable();
                gd.setColor(act.autoRecenterOnVideo ? Color.parseColor("#0288D1") : Color.parseColor("#546E7A"));
                gd.setCornerRadius(14);
                btnAutoRecenter.setBackground(gd);
                act.saveDisplaySettings();
            } else if (v == btnRecenterNow) {
                VRFusionService.requestRecenter();
                act.showCenteredNotice("ВЗГЛЯД ОТЦЕНТРОВАН");
            } else if (v == btnReset) {
                act.eyeScale = 1.0f;
                act.uiScale = 1.0f;
                act.eyeOffsetY = 0;
                act.dividerWidth = 0;
                act.eyeSepX = 0;
                camMode = 0;
                VRFusionService.invertRoll = true;
                act.autoRecenterOnVideo = true;
                scaleLabel.setText("МАСШТАБ ЭКРАНА: 100%");
                uiLabel.setText("МАСШТАБ ИНТЕРФЕЙСА: 100%");
                yLabel.setText("СМЕЩЕНИЕ Y (ВЫСОТА): 0 px");
                ipdLabel.setText("МЕЖГЛАЗНОЕ IPD: 63.0 мм (+0 px)");
                divLabel.setText("РАЗДЕЛИТЕЛЬ ЭКРАНА: 0 px (ВЫКЛ)");
                btnCamMode.setText("КАМЕРА KINECT: " + getCamModeShortName(camMode));
                btnRoll.setText("НАКЛОН (ROLL): РЕЖИМ 1 (СТАНДАРТ)");
                GradientDrawable gdRoll = new GradientDrawable();
                gdRoll.setColor(Color.parseColor("#2E7D32"));
                gdRoll.setCornerRadius(14);
                btnRoll.setBackground(gdRoll);
                btnAutoRecenter.setText("АВТО-ЦЕНТР ПОТОКА: ВКЛ (1.5 сек)");
                GradientDrawable gdAuto = new GradientDrawable();
                gdAuto.setColor(Color.parseColor("#0288D1"));
                gdAuto.setCornerRadius(14);
                btnAutoRecenter.setBackground(gdAuto);
                act.updateDialogSize(dialog, scroll);
                act.applyDisplaySettings();
                act.saveDisplaySettings();
                act.updateCamButtonState();
                VRFusionService.sendUdpToDriver("SET_IPD_SHIFT 0");
                VRFusionService.sendUdpToDriver("SET_CAM_MODE 0");
            } else if (v == btnClose) {
                dialog.dismiss();
                act.enableImmersiveMode();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        enableImmersiveMode();
        if (uiHandler != null) uiHandler.post(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        hasAutoRecenteredOnVideo = false;
        if (uiHandler != null) uiHandler.removeCallbacks(this);
    }

    @Override
    protected void onDestroy() {
        if (activeMainActivity == this) activeMainActivity = null;
        stopVideoReceiver();
        super.onDestroy();
    }

    public static float getRemoteEyeScale() {
        return activeMainActivity != null ? activeMainActivity.eyeScale : 1.0f;
    }
    public static int getRemoteOffsetY() {
        return activeMainActivity != null ? activeMainActivity.eyeOffsetY : 0;
    }
    public static float getRemoteUiScale() {
        return activeMainActivity != null ? activeMainActivity.uiScale : 1.0f;
    }
    public static int getRemoteDivider() {
        return activeMainActivity != null ? activeMainActivity.dividerWidth : 0;
    }
    public static int getRemoteIpdShift() {
        return activeMainActivity != null ? activeMainActivity.eyeSepX : 0;
    }
    public static int getRemoteCamMode() {
        return camMode;
    }

    static class SetEyeScaleTask implements Runnable {
        private final MainActivity act;
        private final float scale;
        SetEyeScaleTask(MainActivity act, float scale) { this.act = act; this.scale = scale; }
        @Override
        public void run() {
            act.eyeScale = Math.max(0.5f, Math.min(1.5f, scale));
            act.applyDisplaySettings();
            act.saveDisplaySettings();
        }
    }

    static class SetOffsetYTask implements Runnable {
        private final MainActivity act;
        private final int offY;
        SetOffsetYTask(MainActivity act, int offY) { this.act = act; this.offY = offY; }
        @Override
        public void run() {
            act.eyeOffsetY = Math.max(-400, Math.min(400, offY));
            act.applyDisplaySettings();
            act.saveDisplaySettings();
        }
    }

    static class SetUiScaleTask implements Runnable {
        private final MainActivity act;
        private final float ui;
        SetUiScaleTask(MainActivity act, float ui) { this.act = act; this.ui = ui; }
        @Override
        public void run() {
            act.uiScale = Math.max(0.5f, Math.min(1.6f, ui));
            act.applyDisplaySettings();
            act.saveDisplaySettings();
        }
    }

    static class SetDividerTask implements Runnable {
        private final MainActivity act;
        private final int div;
        SetDividerTask(MainActivity act, int div) { this.act = act; this.div = div; }
        @Override
        public void run() {
            act.dividerWidth = Math.max(0, Math.min(50, div));
            act.applyDisplaySettings();
            act.saveDisplaySettings();
        }
    }

    static class SetIpdShiftTask implements Runnable {
        private final MainActivity act;
        private final int shift;
        SetIpdShiftTask(MainActivity act, int shift) { this.act = act; this.shift = shift; }
        @Override
        public void run() {
            act.eyeSepX = Math.max(-100, Math.min(100, shift));
            act.applyDisplaySettings();
            act.saveDisplaySettings();
        }
    }

    static class ToggleHudTask implements Runnable {
        private final MainActivity act;
        ToggleHudTask(MainActivity act) { this.act = act; }
        @Override
        public void run() {
            act.toggleHud();
        }
    }

    static class ResetDefaultsTask implements Runnable {
        private final MainActivity act;
        ResetDefaultsTask(MainActivity act) { this.act = act; }
        @Override
        public void run() {
            act.eyeScale = 1.0f;
            act.uiScale = 1.0f;
            act.eyeOffsetY = 0;
            act.dividerWidth = 0;
            act.eyeSepX = 0;
            camMode = 0;
            VRFusionService.invertRoll = true;
            VRFusionService.flipYaw180 = false;
            VRFusionService.requestRecenter();
            act.applyDisplaySettings();
            act.saveDisplaySettings();
            act.updateCamButtonState();
            VRFusionService.sendUdpToDriver("SET_IPD_SHIFT 0");
            VRFusionService.sendUdpToDriver("SET_CAM_MODE 0");
            act.showCenteredNotice("СБРОШЕНО ПО УМОЛЧАНИЮ");
        }
    }

    public static void setRemoteEyeScale(float scale) {
        if (activeMainActivity != null) {
            activeMainActivity.runOnUiThread(new SetEyeScaleTask(activeMainActivity, scale));
        } else if (VRFusionService.activeService != null) {
            try {
                VRFusionService.activeService.getSharedPreferences("vr_settings", MODE_PRIVATE)
                    .edit().putFloat("eye_scale", Math.max(0.5f, Math.min(1.5f, scale))).apply();
            } catch (Throwable ignored) {}
        }
    }

    public static void setRemoteOffsetY(int offY) {
        if (activeMainActivity != null) {
            activeMainActivity.runOnUiThread(new SetOffsetYTask(activeMainActivity, offY));
        } else if (VRFusionService.activeService != null) {
            try {
                VRFusionService.activeService.getSharedPreferences("vr_settings", MODE_PRIVATE)
                    .edit().putInt("eye_offset_y", Math.max(-400, Math.min(400, offY))).apply();
            } catch (Throwable ignored) {}
        }
    }

    public static void setRemoteUiScale(float ui) {
        if (activeMainActivity != null) {
            activeMainActivity.runOnUiThread(new SetUiScaleTask(activeMainActivity, ui));
        } else if (VRFusionService.activeService != null) {
            try {
                VRFusionService.activeService.getSharedPreferences("vr_settings", MODE_PRIVATE)
                    .edit().putFloat("ui_scale", Math.max(0.5f, Math.min(1.6f, ui))).apply();
            } catch (Throwable ignored) {}
        }
    }

    public static void setRemoteDivider(int div) {
        if (activeMainActivity != null) {
            activeMainActivity.runOnUiThread(new SetDividerTask(activeMainActivity, div));
        } else if (VRFusionService.activeService != null) {
            try {
                VRFusionService.activeService.getSharedPreferences("vr_settings", MODE_PRIVATE)
                    .edit().putInt("divider_width", Math.max(0, Math.min(50, div))).apply();
            } catch (Throwable ignored) {}
        }
    }

    public static void setRemoteIpdShift(int shift) {
        if (activeMainActivity != null) {
            activeMainActivity.runOnUiThread(new SetIpdShiftTask(activeMainActivity, shift));
        } else if (VRFusionService.activeService != null) {
            try {
                VRFusionService.activeService.getSharedPreferences("vr_settings", MODE_PRIVATE)
                    .edit().putInt("eye_sep_x", Math.max(-100, Math.min(100, shift))).apply();
            } catch (Throwable ignored) {}
        }
    }

    public static void setRemoteCamMode(final int mode) {
        camMode = mode;
        if (activeMainActivity != null) {
            activeMainActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    activeMainActivity.updateCamButtonState();
                    activeMainActivity.showCenteredNotice("КАМЕРА: " + getCamModeFullName(camMode));
                }
            });
        }
    }

    public static void toggleRemoteCamMode() {
        if (activeMainActivity != null) {
            activeMainActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    activeMainActivity.cycleCamMode();
                }
            });
        } else {
            camMode = (camMode + 1) % 5;
            VRFusionService.sendUdpToDriver("SET_CAM_MODE " + camMode);
        }
    }

    public static void toggleRemoteHud() {
        if (activeMainActivity != null) {
            activeMainActivity.runOnUiThread(new ToggleHudTask(activeMainActivity));
        }
    }

    public static void resetToDefaults() {
        if (activeMainActivity != null) {
            activeMainActivity.runOnUiThread(new ResetDefaultsTask(activeMainActivity));
        }
    }
}
