package com.name.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private SMSReceiver smsReceiver;

    private static final int REQUEST_ALL_PERMISSIONS = 1;
    private static final String NOTIFICATION_CHANNEL_ID = "ETS_SMS_CHANNEL";
    private String pendingUSSDCode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        createNotificationChannel();
        registerSMSReceiver();
        startSMSListenerService();

        webView = findViewById(R.id.webview);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                view.loadUrl(request.getUrl().toString());
                return true;
            }
        });

        webView.addJavascriptInterface(new JSBridge(), "AndroidUSSD");

        // Load with delay to ensure bridge is ready
        webView.post(() -> {
            webView.loadUrl("file:///android_asset/index.html");
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "SMS Notifications",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void registerSMSReceiver() {
        smsReceiver = new SMSReceiver(this);
        IntentFilter filter = new IntentFilter("android.provider.Telephony.SMS_RECEIVED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(smsReceiver, filter);
        }
    }

    private void startSMSListenerService() {
        Intent serviceIntent = new Intent(this, SMSListenerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    public void displaySMSOnWebView(String sender, String message) {
        String smsData = "SMS from " + sender + ": " + message;
        sendResultToWeb(smsData);
    }

    private class JSBridge {

        @JavascriptInterface
        public void runUssd(String code) {
            runOnUiThread(() -> executeUSSD(code));
        }

        @JavascriptInterface
        public void sendSms(String phone, String message) {
            runOnUiThread(() -> executeSendSMS(phone, message));
        }

        @JavascriptInterface
        public void readSms() {
            runOnUiThread(() -> executeReadSMS());
        }

        @JavascriptInterface
        public void makeCall(String phoneNumber) {
            runOnUiThread(() -> executeMakeCall(phoneNumber));
        }
    }

    private void executeUSSD(String code) {

        if (!hasAllPermissions()) {
            pendingUSSDCode = code;
            requestAllPermissions();
            return;
        }

        // USSD only works on Android 8.0+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            sendResultToWeb("USSD not supported on this Android version");
            return;
        }

        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (tm == null) {
            sendResultToWeb("Telephony service unavailable");
            return;
        }

        tm.sendUssdRequest(code, new TelephonyManager.UssdResponseCallback() {
            @Override
            public void onReceiveUssdResponse(
                    TelephonyManager telephonyManager,
                    String request,
                    CharSequence response) {

                Log.d("USSD", "Success: " + response);
                sendResultToWeb(response.toString());
            }

            @Override
            public void onReceiveUssdResponseFailed(
                    TelephonyManager telephonyManager,
                    String request,
                    int failureCode) {

                Log.e("USSD", "Failed: " + failureCode);
                sendResultToWeb("USSD failed: " + failureCode);
            }
        }, new Handler(Looper.getMainLooper()));
    }

    private void executeSendSMS(String phone, String message) {

        if (!hasSmsPermissions()) {
            requestAllPermissions();
            return;
        }

        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(phone, null, message, null, null);
            sendResultToWeb("SMS sent successfully");
        } catch (Exception e) {
            sendResultToWeb("SMS failed: " + e.getMessage());
        }
    }

    private void executeReadSMS() {

        if (!hasSmsPermissions()) {
            requestAllPermissions();
            return;
        }

        Uri inboxUri = Uri.parse("content://sms/inbox");
        Cursor cursor = getContentResolver().query(
                inboxUri,
                null,
                null,
                null,
                "date DESC LIMIT 10"
        );

        if (cursor == null) {
            sendResultToWeb("Failed to read SMS");
            return;
        }

        StringBuilder smsList = new StringBuilder();

        while (cursor.moveToNext()) {
            String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
            String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));

            smsList.append("From: ")
                    .append(address)
                    .append("\n")
                    .append(body)
                    .append("\n\n");
        }

        cursor.close();
        sendResultToWeb(smsList.toString());
    }

    private boolean hasSmsPermissions() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
               ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private void executeMakeCall(String phoneNumber) {
        if (!hasCallPermissions()) {
            requestAllPermissions();
            return;
        }

        try {
            Intent callIntent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + phoneNumber));
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                startActivity(callIntent);
                sendResultToWeb("Call initiated to: " + phoneNumber);
            }
        } catch (Exception e) {
            sendResultToWeb("Call failed: " + e.getMessage());
        }
    }

    private boolean hasCallPermissions() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
               ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAllPermissions() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
               ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
               hasSmsPermissions();
    }

    private void requestAllPermissions() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.SEND_SMS,
                        Manifest.permission.READ_SMS,
                        Manifest.permission.RECEIVE_SMS
                },
                REQUEST_ALL_PERMISSIONS
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_ALL_PERMISSIONS) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    sendResultToWeb("Permission denied");
                    return;
                }
            }

            if (pendingUSSDCode != null) {
                executeUSSD(pendingUSSDCode);
                pendingUSSDCode = null;
            }
        }
    }

    private void sendResultToWeb(String message) {
        String safeMessage = message
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n");

        webView.post(() ->
                webView.evaluateJavascript(
                        "showResult('" + safeMessage + "')",
                        null
                )
        );
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (smsReceiver != null) {
            unregisterReceiver(smsReceiver);
        }
        super.onDestroy();
    }
}
