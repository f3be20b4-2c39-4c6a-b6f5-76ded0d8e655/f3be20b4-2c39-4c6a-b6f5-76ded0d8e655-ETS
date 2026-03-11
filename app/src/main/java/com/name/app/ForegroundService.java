package com.example.ussdwebview;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.List;

public class ForegroundService extends Service {

    private WebView webView;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification();
        initHeadlessWebView();
    }

    private void startForegroundNotification() {
        String channelId = "foreground_service_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "Foreground Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Erandix Auto")
                .setContentText("Monitoring SMS & USSD...")
                .setSmallIcon(R.drawable.app_icon)
                .setOngoing(true);

        startForeground(1, builder.build());
    }

    private void initHeadlessWebView() {
        webView = new WebView(getApplicationContext());
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        // Load your dashboard HTML in memory
        webView.loadUrl("file:///android_asset/index.html");

        // Add JS bridge
        webView.addJavascriptInterface(new JSBridge(), "AndroidBridge");

        // Delay loading existing SMS
        new Handler(Looper.getMainLooper()).postDelayed(this::loadAllSMS, 2000);
    }

    class JSBridge {
        @JavascriptInterface
        public void runUssd(String code, int simSlot) {
            executeUSSD(code, simSlot);
        }

        @JavascriptInterface
        public void sendSMS(String number, String message, int simSlot) {
            sendSMSInternal(number, message, simSlot);
        }
    }

    // ---------------- USSD ----------------
    private void executeUSSD(String code, int simSlot) {
        if (Build.VERSION.SDK_INT < 26) return;

        try {
            SubscriptionManager sm = (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) return;

            List<SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
            if (list == null || list.isEmpty()) return;

            int subId = -1;
            for (SubscriptionInfo info : list) {
                if (info.getSimSlotIndex() == simSlot) {
                    subId = info.getSubscriptionId();
                    break;
                }
            }
            if (subId == -1) subId = list.get(0).getSubscriptionId();

            TelephonyManager tm = ((TelephonyManager) getSystemService(TELEPHONY_SERVICE)).createForSubscriptionId(subId);

            tm.sendUssdRequest(code, new TelephonyManager.UssdResponseCallback() {
                @Override
                public void onReceiveUssdResponse(TelephonyManager telephonyManager, String request, CharSequence response) {
                    postToWeb("showResult('" + escapeJS(response.toString()) + "')");
                }

                @Override
                public void onReceiveUssdResponseFailed(TelephonyManager telephonyManager, String request, int failureCode) {
                    postToWeb("showResult('USSD failed: " + failureCode + "')");
                }
            }, new Handler(Looper.getMainLooper()));

        } catch (Exception e) {
            postToWeb("showResult('USSD error: " + escapeJS(e.getMessage()) + "')");
        }
    }

    // ---------------- SMS Sending ----------------
    private void sendSMSInternal(String number, String message, int simSlot) {
        try {
            SubscriptionManager sm = (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) return;

            List<SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
            if (list == null || list.isEmpty()) return;

            int subId = -1;
            for (SubscriptionInfo info : list) {
                if (info.getSimSlotIndex() == simSlot) {
                    subId = info.getSubscriptionId();
                    break;
                }
            }
            if (subId == -1) subId = list.get(0).getSubscriptionId();

            SmsManager smsManager;
            if (Build.VERSION.SDK_INT >= 22) smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
            else smsManager = SmsManager.getDefault();

            smsManager.sendTextMessage(number, null, message, null, null);

            postToWeb("onSMSSent('" + escapeJS(number) + "','" + escapeJS(message) + "')");

        } catch (Exception e) {
            postToWeb("showResult('SMS error: " + escapeJS(e.getMessage()) + "')");
        }
    }

    // ---------------- SMS Reading ----------------
    private void loadAllSMS() {
        loadSMS("content://sms/inbox", "onSMSInbox");
        loadSMS("content://sms/sent", "onSMSSent");
    }

    private void loadSMS(String uriString, String jsCallback) {
        Cursor cursor = getContentResolver().query(Uri.parse(uriString), null, null, null, "date DESC");
        if (cursor != null) {
            int numIdx = cursor.getColumnIndex("address");
            int bodyIdx = cursor.getColumnIndex("body");
            while (cursor.moveToNext()) {
                String num = cursor.getString(numIdx);
                String msg = cursor.getString(bodyIdx);
                postToWeb(jsCallback + "('" + escapeJS(num) + "','" + escapeJS(msg) + "')");
            }
            cursor.close();
        }
    }

    // ---------------- Utilities ----------------
    private void postToWeb(String jsCode) {
        if (webView != null) {
            new Handler(Looper.getMainLooper()).post(() -> webView.evaluateJavascript(jsCode, null));
        }
    }

    private String escapeJS(String s) {
        return s.replace("\\","\\\\").replace("'","\\'").replace("\n","\\n");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // Keep service alive
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}