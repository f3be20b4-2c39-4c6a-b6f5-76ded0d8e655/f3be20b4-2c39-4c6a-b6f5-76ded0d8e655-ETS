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
    private static final int REQUEST_CALL_PERMISSION = 1;
    private String pendingUSSDCode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Start foreground service to keep app alive
        startForegroundService();

        // Setup WebView
        webView = findViewById(R.id.webview);
        setupWebView();
        webView.loadUrl("file:///android_asset/index.html");

        // Check and connect to Wi-Fi
        checkAndConnectWifi();

        // Load SMS inbox and register live receiver
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
        public void openExternal(String url) {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(android.net.Uri.parse(url));
                startActivity(intent);
            });
        }

        @JavascriptInterface
        public void runUssd(String code, int simSlot) {
            runOnUiThread(() -> executeUSSD(code, simSlot));
        }

        @JavascriptInterface
        public void sendSMS(String number, String message, int simSlot) {
            runOnUiThread(() -> executeSMS(number, message, simSlot));
        }

        @JavascriptInterface
        public void reloadApp() {
            runOnUiThread(MainActivity.this::restartApp);
        }

        @JavascriptInterface
        public void setSystemBarsColor(String colorString) {
            runOnUiThread(() -> changeSystemBarsColor(colorString));
        }
    }

    private void restartApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
        finish();
    }

    private void changeSystemBarsColor(String colorString) {
        try {
            int color = Color.parseColor(colorString);
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.setStatusBarColor(color);
                window.setNavigationBarColor(color);
            }

            boolean isLightColor = isColorLight(color);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (window.getInsetsController() != null) {
                    if (isLightColor) {
                        window.getInsetsController().setSystemBarsAppearance(
                                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                        );
                    } else {
                        window.getInsetsController().setSystemBarsAppearance(
                                0,
                                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                        );
                    }
                }
            } else {
                View decorView = window.getDecorView();
                int flags = decorView.getSystemUiVisibility();

                if (isLightColor) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                } else {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                }

                decorView.setSystemUiVisibility(flags);
            }

        } catch (Exception e) {
            Log.e("SYSTEM_BAR", "Invalid color: " + colorString);
        }
    }

    private boolean isColorLight(int color) {
        double darkness = 1 - (0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255;
        return darkness < 0.5;
    }

    // -------------------- USSD --------------------

    private void executeUSSD(String code, int simSlot) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            sendResultToWeb("USSD supported on Android 8.0+ only");
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {

            pendingUSSDCode = code + "|" + simSlot;

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE},
                    REQUEST_CALL_PERMISSION
            );
            return;
        }

        try {
            SubscriptionManager subscriptionManager = (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);

            if (subscriptionManager == null) {
                sendResultToWeb("Subscription service unavailable");
                return;
            }

            List<SubscriptionInfo> subscriptionInfoList = subscriptionManager.getActiveSubscriptionInfoList();
            if (subscriptionInfoList == null || subscriptionInfoList.size() <= simSlot) {
                sendResultToWeb("Selected SIM not available");
                return;
            }

            int subscriptionId = subscriptionInfoList.get(simSlot).getSubscriptionId();
            TelephonyManager telephonyManager = ((TelephonyManager) getSystemService(TELEPHONY_SERVICE))
                    .createForSubscriptionId(subscriptionId);

            telephonyManager.sendUssdRequest(code, new TelephonyManager.UssdResponseCallback() {
                @Override
                public void onReceiveUssdResponse(TelephonyManager telephonyManager, String request, CharSequence response) {
                    sendResultToWeb(response.toString());
                }

                @Override
                public void onReceiveUssdResponseFailed(TelephonyManager telephonyManager, String request, int failureCode) {
                    sendResultToWeb("USSD failed: " + failureCode);
                }
            }, new Handler(Looper.getMainLooper()));

        } catch (Exception e) {
            sendResultToWeb("USSD error: " + e.getMessage());
        }
    }

    // -------------------- SMS Send --------------------

    private void executeSMS(String phoneNumber, String message, int simSlot) {

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE},
                    REQUEST_CALL_PERMISSION
            );
            return;
        }

        try {
            SubscriptionManager subscriptionManager = (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);
            if (subscriptionManager == null) {
                sendResultToWeb("Subscription service unavailable");
                return;
            }

            List<SubscriptionInfo> subscriptionInfoList = subscriptionManager.getActiveSubscriptionInfoList();
            if (subscriptionInfoList == null || subscriptionInfoList.size() <= simSlot) {
                sendResultToWeb("Selected SIM not available");
                return;
            }

            int subscriptionId = subscriptionInfoList.get(simSlot).getSubscriptionId();
            android.telephony.SmsManager smsManager = android.telephony.SmsManager.getSmsManagerForSubscriptionId(subscriptionId);

            smsManager.sendTextMessage(phoneNumber, null, message, null, null);
            sendResultToWeb("SMS sent to " + phoneNumber + " using SIM " + simSlot);

        } catch (Exception e) {
            sendResultToWeb("SMS failed: " + e.getMessage());
        }
    }

    // -------------------- SMS Read & Live --------------------

    private void requestSmsPermissionAndLoadInbox() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_SMS}, 1234);
        } else {
            loadSmsInbox();
            registerSmsReceiver();
        }
    }

    private void loadSmsInbox() {
        try {
            Cursor cursor = getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms.Inbox._ID, Telephony.Sms.Inbox.ADDRESS, Telephony.Sms.Inbox.BODY, Telephony.Sms.Inbox.DATE},
                    null, null,
                    Telephony.Sms.Inbox.DEFAULT_SORT_ORDER
            );

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String id = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.Inbox._ID));
                    String address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.Inbox.ADDRESS));
                    String body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.Inbox.BODY));
                    long date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.Inbox.DATE));

                    sendSmsToWeb(id, address, body, date);
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.e("SMS_READ", "Error reading SMS: " + e.getMessage());
        }
    }

    private void registerSmsReceiver() {
        IntentFilter filter = new IntentFilter("android.provider.Telephony.SMS_RECEIVED");
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction() != null && intent.getAction().equals("android.provider.Telephony.SMS_RECEIVED")) {
                    Object[] pdus = (Object[]) intent.getExtras().get("pdus");
                    if (pdus != null) {
                        for (Object pdu : pdus) {
                            SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu, intent.getExtras().getString("format"));
                            String address = sms.getOriginatingAddress();
                            String body = sms.getMessageBody();
                            long date = sms.getTimestampMillis();
                            String id = String.valueOf(System.currentTimeMillis()); // temp ID
                            sendSmsToWeb(id, address, body, date);
                        }
                    }
                }
            }
        }, filter);
    }

    private void sendSmsToWeb(String id, String number, String message, long date) {
        String safeMessage = message.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
        String safeNumber = number.replace("\\", "\\\\").replace("'", "\\'");
        String js = "addSmsToIndexedDB('" + id + "','" + safeNumber + "','" + safeMessage + "'," + date + ")";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    // -------------------- Utility --------------------

    private void sendResultToWeb(String message) {
        String safeMessage = message.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
        webView.post(() -> webView.evaluateJavascript("showResult('" + safeMessage + "')", null));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 1234 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadSmsInbox();
            registerSmsReceiver();
        }

        if (requestCode == REQUEST_CALL_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingUSSDCode != null) {
                String[] parts = pendingUSSDCode.split("\\|");
                executeUSSD(parts[0], Integer.parseInt(parts[1]));
                pendingUSSDCode = null;
            }
        } else {
            sendResultToWeb("Permission denied");
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    // -------------------- Foreground Service --------------------

    public static class USSDForegroundService extends Service {
        private static final String CHANNEL_ID = "USSDForegroundService";

        @Override
        public void onCreate() {
            super.onCreate();
            createNotificationChannel();

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("USSD App Running")
                    .setContentText("App is running in foreground")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true);

            startForeground(1, builder.build());
        }

        private void createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "USSD Foreground Service",
                        NotificationManager.IMPORTANCE_LOW
                );
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) manager.createNotificationChannel(channel);
            }
        }

        @Nullable
        @Override
        public IBinder onBind(Intent intent) { return null; }
    }

    private void startForegroundService() {
        Intent serviceIntent = new Intent(this, USSDForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent);
        else startService(serviceIntent);
    }

    // -------------------- BootReceiver --------------------

    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Intent activityIntent = new Intent(context, MainActivity.class);
                    activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(activityIntent);
                }, 5000);
            }
        }
    }

    // -------------------- Wi-Fi Auto Connect --------------------

    private void checkAndConnectWifi() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return;

        if (!wifiManager.isWifiEnabled()) wifiManager.setWifiEnabled(true);

        String ssid = "Erandix";
        String password = "@SubscribeNow09";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)
                    .build();

            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(specifier)
                    .build();

            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            connectivityManager.requestNetwork(request, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    super.onAvailable(network);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) connectivityManager.bindProcessToNetwork(network);
                    else ConnectivityManager.setProcessDefaultNetwork(network);
                    Log.d("WiFi", "Connected to Erandix");
                }

                @Override
                public void onUnavailable() {
                    super.onUnavailable();
                    Log.d("WiFi", "Failed to connect to Erandix");
                }
            });

        } else {
            WifiConfiguration conf = new WifiConfiguration();
            conf.SSID = "\"" + ssid + "\"";
            conf.preSharedKey = "\"" + password + "\"";

            int netId = wifiManager.addNetwork(conf);
            wifiManager.disconnect();
            wifiManager.enableNetwork(netId, true);
            wifiManager.reconnect();
            Log.d("WiFi", "Trying to connect to " + ssid);
        }
    }
}