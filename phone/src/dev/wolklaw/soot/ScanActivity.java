package dev.wolklaw.soot;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/**
 * Reads the pairing QR code from the PC's screen with the back camera. Frames are decoded on the phone
 * (ZXing); nothing is saved or sent. Returns the text of the code as the "link" extra.
 */
public final class ScanActivity extends Activity {
    private static final int W = 1280, H = 720;
    private TextureView preview;
    private TextView hint;
    private HandlerThread thread;
    private Handler bg;
    private CameraDevice camera;
    private ImageReader reader;
    private final QRCodeReader qr = new QRCodeReader();
    private final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
    private volatile boolean found;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(Ui.dp(this, 18), bars.top + Ui.dp(this, 14), Ui.dp(this, 18), bars.bottom + Ui.dp(this, 12));
            return insets;
        });
        root.addView(Ui.label(this, "● CAMERA  ·  LINK TO PC", Ui.MUTED));
        GlitchText title = new GlitchText(this, 32);
        title.setText("SCAN");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = -Ui.dp(this, 8);
        root.addView(title, tp);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(Ui.box(this, Ui.PANEL, Ui.LINE2));
        frame.setPadding(Ui.dp(this, 1), Ui.dp(this, 1), Ui.dp(this, 1), Ui.dp(this, 1));
        preview = new TextureView(this);
        frame.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addView(frame, Ui.fill(this, 10));
        // portrait: the sensor's 16:9 picture stands up as 9:16
        frame.post(() -> { ViewGroup.LayoutParams p = frame.getLayoutParams(); p.height = frame.getWidth() * W / H; frame.setLayoutParams(p); });
        hint = Ui.text(this, "Point the camera at the code in Spark Link on your PC.", 13, Ui.TEXT, false);
        root.addView(hint, Ui.fill(this, 12));
        TextView cancel = Ui.label(this, "CANCEL", Ui.TEXT);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));
        cancel.setBackground(Ui.box(this, Ui.PANEL, Ui.LINE2));
        cancel.setOnClickListener(v -> finish());
        root.addView(cancel, Ui.fill(this, 12));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.CAMERA }, 1);
            return;
        }
        begin();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) begin();
        else hint.setText("Soot needs the camera to read the code. You can paste the pairing link instead.");
    }

    @Override
    protected void onPause() {
        end();
        super.onPause();
    }

    private void begin() {
        if (thread != null) return;
        thread = new HandlerThread("scan");
        thread.start();
        bg = new Handler(thread.getLooper());
        if (preview.isAvailable()) openCamera();
        else preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
        });
    }

    private void end() {
        if (camera != null) { camera.close(); camera = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (thread != null) { thread.quitSafely(); thread = null; }
    }

    @SuppressWarnings("MissingPermission")
    private void openCamera() {
        try {
            CameraManager cm = getSystemService(CameraManager.class);
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { id = c; break; }
                if (id == null) id = c;
            }
            if (id == null) { hint.setText("This phone has no camera. Paste the pairing link instead."); return; }
            reader = ImageReader.newInstance(W, H, ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::decode, bg);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { camera = d; startPreview(); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); camera = null; }
                @Override public void onError(CameraDevice d, int e) { d.close(); camera = null; runOnUiThread(() -> hint.setText("The camera could not start (" + e + ").")); }
            }, bg);
        } catch (Exception e) {
            hint.setText("The camera could not start: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void startPreview() {
        try {
            SurfaceTexture tex = preview.getSurfaceTexture();
            tex.setDefaultBufferSize(W, H);
            Surface view = new Surface(tex);
            CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(view);
            req.addTarget(reader.getSurface());
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(Arrays.asList(view, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    try { s.setRepeatingRequest(req.build(), null, bg); } catch (Exception ignored) { }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) { runOnUiThread(() -> hint.setText("The camera could not start.")); }
            }, bg);
        } catch (Exception e) {
            runOnUiThread(() -> hint.setText("The camera could not start: " + e.getMessage()));
        }
    }

    /** The brightness plane of a frame is all a QR code needs. */
    private void decode(ImageReader r) {
        try (Image img = r.acquireLatestImage()) {
            if (img == null || found) return;
            Image.Plane y = img.getPlanes()[0];
            ByteBuffer buf = y.getBuffer();
            int stride = y.getRowStride(), w = img.getWidth(), h = img.getHeight();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            int rows = (data.length + stride - 1) / stride;
            if (rows < h) return;
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, stride, h, 0, 0, w, h, false);
            Result res = qr.decode(new BinaryBitmap(new HybridBinarizer(src)), hints);
            String text = res.getText();
            if (LinkState.parse(text) == null) {
                runOnUiThread(() -> hint.setText("That code is not a Spark Link pairing code."));
                return;
            }
            found = true;
            runOnUiThread(() -> { setResult(RESULT_OK, new Intent().putExtra("link", text)); finish(); });
        } catch (Exception notThisFrame) {
            qr.reset();
        }
    }
}
