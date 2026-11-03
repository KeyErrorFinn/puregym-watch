package com.finnl.gymaccess.wear;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Platform-only runner, independent of app/R8 class names and Kotlin runtime names. */
public class ReleaseProbe extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        String name = "gym-watch-code-v1";
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
            SecretKey key = (SecretKey) store.getKey(name, null);
            if (key == null) {
                KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
                key = generator.generateKey();
            }
            StringBuilder payload = new StringBuilder("synthetic-entry-");
            for (int i = 0; i < 8; i++) payload.append("aB012_-");
            JSONObject code = new JSONObject().put("payload", payload.toString()).put("refreshAt", System.currentTimeMillis() + 60000).put("expiresAt", System.currentTimeMillis() + 120000);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(code.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV(); byte[] bytes = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, bytes, 0, iv.length); System.arraycopy(encrypted, 0, bytes, iv.length, encrypted.length);
            context.getSharedPreferences(name, 0).edit().putString("value", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit();
            context.getSharedPreferences("scan-display", 0).edit().clear().commit();
            context.startActivity(new Intent().setClassName(context.getPackageName(), "com.finnl.gymaccess.wear.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            boolean rendered = false; long deadline = SystemClock.elapsedRealtime() + 25000;
            while (!rendered && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(200);
                Bitmap bitmap = getUiAutomation().takeScreenshot();
                int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
                bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
                int black = 0; for (int pixel : pixels) if ((pixel & 0xffffff) == 0) black++;
                if ((bitmap.getPixel(10, bitmap.getHeight() / 2) & 0xffffff) == 0xffffff && black > 10000) {
                    try (FileOutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), "optimized-release-qr.png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
                    rendered = true;
                }
            }
            if (!rendered) throw new IllegalStateException("Optimized release did not display the cached QR");
            result.putString("result", "PASS: optimized release displays encrypted cached QR");
            finish(0, result);
        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(1, result);
        } finally { context.getSharedPreferences(name, 0).edit().clear().commit(); }
    }
}
