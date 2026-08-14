package com.gaode.map;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.content.pm.PackageManager;
import android.Manifest;
import android.util.Log;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.BridgeActivity;

/**
 * gaode地图主 Activity — 定位通过 @capacitor/geolocation 插件
 */
public class MainActivity extends BridgeActivity {

    private static final String TAG = "GaodeMap";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private WebView mWebView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "gaode地图 APP 启动");

        requestLocationPermission();
        configureWebView();
    }

    // ==================== 权限 ====================

    private void requestLocationPermission() {
        java.util.ArrayList<String> needed = new java.util.ArrayList<>();
        String[] perms = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        };
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                Log.i(TAG, "权限 " + permissions[i] + ": " +
                    (grantResults[i] == PackageManager.PERMISSION_GRANTED ? "✓" : "✗"));
            }
        }
    }

    // ==================== WebView 配置 ====================

    private void configureWebView() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                mWebView = getBridge().getWebView();
                if (mWebView == null) {
                    Log.w(TAG, "WebView 未就绪");
                    return;
                }

                WebSettings settings = mWebView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setDatabaseEnabled(true);
                settings.setAllowFileAccess(true);
                settings.setAllowContentAccess(true);
                settings.setGeolocationEnabled(true);
                settings.setJavaScriptCanOpenWindowsAutomatically(true);
                settings.setCacheMode(WebSettings.LOAD_DEFAULT);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                }

                mWebView.setWebChromeClient(new android.webkit.WebChromeClient() {
                    @Override
                    public void onGeolocationPermissionsShowPrompt(
                        String origin, android.webkit.GeolocationPermissions.Callback callback) {
                        callback.invoke(origin, true, false);
                    }
                    @Override
                    public void onPermissionRequest(android.webkit.PermissionRequest request) {
                        request.grant(request.getResources());
                    }
                });

                Log.i(TAG, "WebView 配置完成");

            } catch (Exception e) {
                Log.e(TAG, "WebView 配置失败: " + e.getMessage(), e);
            }
        }, 500);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
}
