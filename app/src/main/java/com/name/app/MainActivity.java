package com.example.ussdwebview;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private final int PERMISSION_REQUEST_CODE = 100;

    // Singleton holder to reference MainActivity from SmsReceiver
    private static MainActivity instance;
    public static MainActivity getInstance() { return instance; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        // Initialize WebView
        webView = new WebView(this);
        setContentView(webView);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html"); // local HTML file

        // Request permissions if not granted
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.READ_SMS,
                            Manifest.permission.RECEIVE_SMS,
                            Manifest.permission.CALL_PHONE
                    },
                    PERMISSION_REQUEST_CODE);
        } else {
            initUSSD();
        }
    }

    private boolean hasPermissions() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
    }

    // Initialize USSD request
    private void initUSSD() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
                tm.sendUssdRequest("*123#", new TelephonyManager.UssdResponseCallback() {
                    @Override
                    public void onReceiveUssdResponse(@NonNull TelephonyManager telephonyManager, @NonNull String request, @NonNull CharSequence response) {
                        // Send USSD response to WebView
                        runOnUiThread(() -> webView.evaluateJavascript("displayUSSD('" + escapeJS(response.toString()) + "')", null));
                    }

                    @Override
                    public void onReceiveUssdResponseFailed(@NonNull TelephonyManager telephonyManager, @NonNull String request, int failureCode) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "USSD Failed", Toast.LENGTH_SHORT).show());
                    }
                }, null);
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "USSD Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    // Escape quotes for JS injection
    private String escapeJS(String input) {
        return input.replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
    }

    // Permission result callback
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean granted = true;
            for (int res : grantResults) if (res != PackageManager.PERMISSION_GRANTED) granted = false;

            if (granted) {
                initUSSD();
            } else {
                Toast.makeText(this, "Permissions denied!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // Method called from SmsReceiver to send SMS content to WebView
    public void displaySMS(String msg) {
        runOnUiThread(() -> webView.evaluateJavascript("displaySMS('" + escapeJS(msg) + "')", null));
    }

    // Optional: clean up WebView on destroy
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
        }
    }
}