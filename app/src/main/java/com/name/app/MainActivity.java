package com.example.ussdwebview;

import android.Manifest;
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
import android.telephony.SmsMessage;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private static final int REQUEST_USSD_PERMISSION = 1;
    private static final int REQUEST_SMS_PERMISSION = 2;
    private static final int REQUEST_READ_SMS = 100;

    private String pendingUSSD;
    private String pendingSMS;

    private SmsReceiver smsReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep the screen on while this activity is running
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Initialize WebView
        webView = new WebView(this);
        setContentView(webView);
        setupWebView();
        webView.loadUrl("file:///android_asset/index.html");

        // Start Foreground Service (keeps app running in background)
        Intent serviceIntent = new Intent(this, ForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Register SMS receiver and load existing messages
        registerSMSReceiver();
        new Handler().postDelayed(this::loadAllSMS, 2000);
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new JSBridge(), "AndroidBridge");
    }

    class JSBridge {
        @JavascriptInterface
        public void runUssd(String code, int simSlot) {
            runOnUiThread(() -> executeUSSD(code, simSlot));
        }

        @JavascriptInterface
        public void sendSMS(String number, String message, int simSlot) {
            runOnUiThread(() -> sendSMSInternal(number, message, simSlot));
        }

        @JavascriptInterface
        public void setSystemColor(String color) {
            runOnUiThread(() -> changeSystemColor(color));
        }
    }

    private void changeSystemColor(String colorString) {
        try {
            int color = android.graphics.Color.parseColor(colorString);
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            if (Build.VERSION.SDK_INT >= 21) {
                window.setStatusBarColor(color);
                window.setNavigationBarColor(color);
            }
        } catch (Exception ignored) {}
    }

    // ---------------- USSD ----------------
    private void executeUSSD(String code, int simSlot) {
        if (Build.VERSION.SDK_INT < 26) {
            sendUSSDResult("USSD requires Android 8+");
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            pendingUSSD = code + "|" + simSlot;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE},
                    REQUEST_USSD_PERMISSION);
            return;
        }

        try {
            SubscriptionManager sm = (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) { sendUSSDResult("No subscription manager"); return; }

            List<SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
            if (list == null || simSlot >= list.size()) { sendUSSDResult("SIM not available"); return; }

            int subId = -1;
            for (SubscriptionInfo info : list) {
                if (info.getSimSlotIndex() == simSlot) { subId = info.getSubscriptionId(); break; }
            }
            if (subId == -1) subId = list.get(0).getSubscriptionId();

            TelephonyManager tm = ((TelephonyManager)getSystemService(TELEPHONY_SERVICE)).createForSubscriptionId(subId);

            tm.sendUssdRequest(code, new TelephonyManager.UssdResponseCallback() {
                @Override
                public void onReceiveUssdResponse(TelephonyManager telephonyManager, String request, CharSequence response) {
                    sendUSSDResult(response.toString());
                }
                @Override
                public void onReceiveUssdResponseFailed(TelephonyManager telephonyManager, String request, int failureCode) {
                    sendUSSDResult("USSD failed: " + failureCode);
                }
            }, new Handler(Looper.getMainLooper()));

        } catch (Exception e) { sendUSSDResult("USSD error: " + e.getMessage()); }
    }

    private void sendUSSDResult(String message) {
        final String safe = message.replace("\\","\\\\").replace("'","\\'").replace("\n","\\n");
        webView.post(() -> webView.evaluateJavascript("showResult('" + safe + "')", null));
    }

    // ---------------- SMS Sending ----------------
    private void sendSMSInternal(String number, String message, int simSlot) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            pendingSMS = number + "|" + message + "|" + simSlot;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE},
                    REQUEST_SMS_PERMISSION);
            return;
        }

        try {
            SubscriptionManager sm = (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) { sendResult("No subscription manager"); return; }

            List<SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
            if (list == null || list.size() == 0) { sendResult("No active SIM"); return; }

            int subId = -1;
            for (SubscriptionInfo info : list) {
                if (info.getSimSlotIndex() == simSlot) { subId = info.getSubscriptionId(); break; }
            }
            if (subId == -1) subId = list.get(0).getSubscriptionId();

            SmsManager smsManager;
            if (Build.VERSION.SDK_INT >= 22) smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
            else smsManager = SmsManager.getDefault();

            smsManager.sendTextMessage(number, null, message, null, null);
            sendResult("SMS sent to " + number);

            final String safeNumber = number.replace("'","\\'");
            final String safeMessage = message.replace("'","\\'");
            webView.post(() -> webView.evaluateJavascript("onSMSSent('" + safeNumber + "','" + safeMessage + "')", null));

        } catch (Exception e) { sendResult("SMS error: " + e.getMessage()); }
    }

    private void sendResult(String msg) {
        final String safe = msg.replace("\\","\\\\").replace("'","\\'").replace("\n","\\n");
        webView.post(() -> webView.evaluateJavascript("showResult('" + safe + "')", null));
    }

    // ---------------- SMS Reading ----------------
    private void registerSMSReceiver() {
        smsReceiver = new SmsReceiver();
        registerReceiver(smsReceiver, new IntentFilter("android.provider.Telephony.SMS_RECEIVED"));
    }

    private void loadAllSMS() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_SMS}, REQUEST_READ_SMS);
            return;
        }

        // Inbox
        Cursor inbox = getContentResolver().query(Uri.parse("content://sms/inbox"), null, null, null, "date DESC");
        if (inbox != null) {
            int numIdx = inbox.getColumnIndex("address");
            int bodyIdx = inbox.getColumnIndex("body");
            while (inbox.moveToNext()) {
                String num = inbox.getString(numIdx);
                String msg = inbox.getString(bodyIdx);
                final String number = num.replace("'","\\'");
                final String message = msg.replace("'","\\'");
                webView.post(() -> webView.evaluateJavascript("onSMSInbox('" + number + "','" + message + "')", null));
            }
            inbox.close();
        }

        // Sent
        Cursor sent = getContentResolver().query(Uri.parse("content://sms/sent"), null, null, null, "date DESC");
        if (sent != null) {
            int numIdx = sent.getColumnIndex("address");
            int bodyIdx = sent.getColumnIndex("body");
            while (sent.moveToNext()) {
                String num = sent.getString(numIdx);
                String msg = sent.getString(bodyIdx);
                final String number = num.replace("'","\\'");
                final String message = msg.replace("'","\\'");
                webView.post(() -> webView.evaluateJavascript("onSMSSent('" + number + "','" + message + "')", null));
            }
            sent.close();
        }
    }

    private class SmsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle bundle = intent.getExtras();
            if (bundle == null) return;
            Object[] pdus = (Object[]) bundle.get("pdus");
            if (pdus == null) return;
            for (Object pdu : pdus) {
                SmsMessage sms;
                if (Build.VERSION.SDK_INT >= 23) {
                    String format = bundle.getString("format");
                    sms = SmsMessage.createFromPdu((byte[]) pdu, format);
                } else sms = SmsMessage.createFromPdu((byte[]) pdu);

                final String number = sms.getOriginatingAddress().replace("'","\\'");
                final String message = sms.getMessageBody().replace("'","\\'");
                webView.post(() -> webView.evaluateJavascript("onSMSReceived('" + number + "','" + message + "')", null));
            }
        }
    }

    // ---------------- Permissions ----------------
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,@NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_USSD_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingUSSD != null) {
                String[] p = pendingUSSD.split("\\|");
                executeUSSD(p[0], Integer.parseInt(p[1]));
                pendingUSSD = null;
            }
        }

        if (requestCode == REQUEST_SMS_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingSMS != null) {
                String[] p = pendingSMS.split("\\|");
                sendSMSInternal(p[0], p[1], Integer.parseInt(p[2]));
                pendingSMS = null;
            }
        }

        if (requestCode == REQUEST_READ_SMS && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadAllSMS();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(smsReceiver);
    }
}