package com.example.ussdwebview;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private static final int REQUEST_CALL_PERMISSION = 100;
    private static final int REQUEST_SMS_PERMISSION = 101;

    private String pendingUSSDCode;

    private BroadcastReceiver smsReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        startForegroundServiceSafe();

        webView = findViewById(R.id.webview);

        setupWebView();

        webView.loadUrl("file:///android_asset/index.html");

        checkAndConnectWifi();

        requestSmsPermissionAndLoadInbox();
    }

    private void setupWebView() {

        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient());

        webView.addJavascriptInterface(new JSBridge(), "AndroidUSSD");
    }

    public class JSBridge {

        @JavascriptInterface
        public void runUssd(String code, int sim) {
            runOnUiThread(() -> executeUSSD(code, sim));
        }

        @JavascriptInterface
        public void sendSMS(String number, String msg, int sim) {
            runOnUiThread(() -> executeSMS(number, msg, sim));
        }

        @JavascriptInterface
        public void reloadApp() {
            runOnUiThread(MainActivity.this::restartApp);
        }

        @JavascriptInterface
        public void openExternal(String url) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(android.net.Uri.parse(url));
                startActivity(i);
            });
        }

    }

    private void restartApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        }
        finish();
    }

    // -------------------- USSD --------------------

    private void executeUSSD(String code, int simSlot) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            sendResultToWeb("USSD only supported Android 8+");
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {

            pendingUSSDCode = code + "|" + simSlot;

            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.READ_PHONE_STATE
                    },
                    REQUEST_CALL_PERMISSION);

            return;
        }

        try {

            SubscriptionManager manager =
                    (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);

            if (manager == null) {
                sendResultToWeb("No SIM service");
                return;
            }

            List<SubscriptionInfo> list = manager.getActiveSubscriptionInfoList();

            if (list == null || list.size() <= simSlot) {
                sendResultToWeb("SIM slot not available");
                return;
            }

            int subId = list.get(simSlot).getSubscriptionId();

            TelephonyManager telephonyManager =
                    ((TelephonyManager) getSystemService(TELEPHONY_SERVICE))
                            .createForSubscriptionId(subId);

            telephonyManager.sendUssdRequest(code,
                    new TelephonyManager.UssdResponseCallback() {

                        @Override
                        public void onReceiveUssdResponse(
                                TelephonyManager telephonyManager,
                                String request,
                                CharSequence response) {

                            sendResultToWeb(response.toString());
                        }

                        @Override
                        public void onReceiveUssdResponseFailed(
                                TelephonyManager telephonyManager,
                                String request,
                                int failureCode) {

                            sendResultToWeb("USSD failed: " + failureCode);
                        }

                    },
                    new Handler(Looper.getMainLooper()));

        } catch (Exception e) {
            sendResultToWeb("USSD error: " + e.getMessage());
        }

    }

    // -------------------- SMS SEND --------------------

    private void executeSMS(String number, String msg, int simSlot) {

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.SEND_SMS},
                    REQUEST_SMS_PERMISSION);

            return;
        }

        try {

            SubscriptionManager manager =
                    (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);

            if (manager == null) {
                sendResultToWeb("No SIM service");
                return;
            }

            List<SubscriptionInfo> list = manager.getActiveSubscriptionInfoList();

            if (list == null || list.size() <= simSlot) {
                sendResultToWeb("SIM not available");
                return;
            }

            int subId = list.get(simSlot).getSubscriptionId();

            android.telephony.SmsManager smsManager =
                    android.telephony.SmsManager.getSmsManagerForSubscriptionId(subId);

            smsManager.sendTextMessage(number, null, msg, null, null);

            sendResultToWeb("SMS sent");

        } catch (Exception e) {
            sendResultToWeb("SMS error: " + e.getMessage());
        }

    }

    // -------------------- SMS READ --------------------

    private void requestSmsPermissionAndLoadInbox() {

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS},
                    REQUEST_SMS_PERMISSION);

        } else {

            loadSmsInbox();
            registerSmsReceiver();

        }

    }

    private void loadSmsInbox() {

        try {

            Cursor cursor = getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    null,
                    null,
                    null,
                    Telephony.Sms.DEFAULT_SORT_ORDER
            );

            if (cursor == null) return;

            while (cursor.moveToNext()) {

                String id = cursor.getString(cursor.getColumnIndexOrThrow("_id"));
                String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                long date = cursor.getLong(cursor.getColumnIndexOrThrow("date"));

                sendSmsToWeb(id, address, body, date);
            }

            cursor.close();

        } catch (Exception e) {
            Log.e("SMS", e.getMessage());
        }

    }

    private void registerSmsReceiver() {

        smsReceiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {

                try {

                    Bundle bundle = intent.getExtras();

                    if (bundle == null) return;

                    Object[] pdus = (Object[]) bundle.get("pdus");

                    if (pdus == null) return;

                    for (Object pdu : pdus) {

                        SmsMessage sms;

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            String format = bundle.getString("format");
                            sms = SmsMessage.createFromPdu((byte[]) pdu, format);
                        } else {
                            sms = SmsMessage.createFromPdu((byte[]) pdu);
                        }

                        String address = sms.getOriginatingAddress();
                        String body = sms.getMessageBody();
                        long date = sms.getTimestampMillis();

                        sendSmsToWeb(
                                String.valueOf(System.currentTimeMillis()),
                                address,
                                body,
                                date
                        );
                    }

                } catch (Exception e) {
                    Log.e("SMS", e.getMessage());
                }

            }

        };

        registerReceiver(smsReceiver,
                new IntentFilter("android.provider.Telephony.SMS_RECEIVED"));

    }

    private void sendSmsToWeb(String id, String number, String msg, long date) {

        if (webView == null) return;

        String safe = msg.replace("'", "\\'");

        String js = "addSmsToIndexedDB('" + id + "','" + number + "','" + safe + "'," + date + ")";

        webView.post(() -> webView.evaluateJavascript(js, null));

    }

    private void sendResultToWeb(String msg) {

        if (webView == null) return;

        String safe = msg.replace("'", "\\'");

        webView.post(() ->
                webView.evaluateJavascript("showResult('" + safe + "')", null));

    }

    // -------------------- FOREGROUND SERVICE --------------------

    public static class USSDForegroundService extends Service {

        @Override
        public void onCreate() {
            super.onCreate();

            String channel = "ussd_service";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                NotificationChannel ch =
                        new NotificationChannel(channel, "USSD Service",
                                NotificationManager.IMPORTANCE_LOW);

                NotificationManager nm =
                        getSystemService(NotificationManager.class);

                nm.createNotificationChannel(ch);

            }

            NotificationCompat.Builder builder =
                    new NotificationCompat.Builder(this, channel)
                            .setContentTitle("App Running")
                            .setContentText("USSD SMS Service Active")
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setOngoing(true);

            startForeground(1, builder.build());

        }

        @Nullable
        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

    }

    private void startForegroundServiceSafe() {

        Intent i = new Intent(this, USSDForegroundService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(i);
        else
            startService(i);

    }

    // -------------------- WIFI --------------------

    private void checkAndConnectWifi() {

        try {

            WifiManager wifiManager =
                    (WifiManager) getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);

            if (wifiManager == null) return;

            if (!wifiManager.isWifiEnabled()) wifiManager.setWifiEnabled(true);

        } catch (Exception e) {
            Log.e("WIFI", e.getMessage());
        }

    }

    // -------------------- BOOT RECEIVER --------------------

    public static class BootReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {

            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {

                Intent i = new Intent(context, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);

            }

        }

    }

}