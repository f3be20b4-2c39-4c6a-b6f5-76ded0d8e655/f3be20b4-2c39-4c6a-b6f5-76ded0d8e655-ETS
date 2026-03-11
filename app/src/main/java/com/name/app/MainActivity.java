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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        // Setup WebView
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html"); // local HTML file

        // Request permissions
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.CALL_PHONE},
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

    private void initUSSD() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            tm.sendUssdRequest("*123#", new TelephonyManager.UssdResponseCallback() {
                @Override
                public void onReceiveUssdResponse(@NonNull TelephonyManager telephonyManager, @NonNull String request, @NonNull CharSequence response) {
                    // Send USSD response to WebView
                    runOnUiThread(() -> webView.evaluateJavascript("displayUSSD('" + response.toString() + "')", null));
                }

                @Override
                public void onReceiveUssdResponseFailed(@NonNull TelephonyManager telephonyManager, @NonNull String request, int failureCode) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "USSD Failed", Toast.LENGTH_SHORT).show());
                }
            }, null);
        }
    }

    // Permission result
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean granted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) granted = false;
            }
            if (granted) {
                initUSSD();
            } else {
                Toast.makeText(this, "Permissions denied!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // Called from SmsReceiver to display SMS in WebView
    public void displaySMS(String msg) {
        runOnUiThread(() -> webView.evaluateJavascript("displaySMS('" + msg + "')", null));
    }
}