package com.example.ussdwebview;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
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

    private String pendingUSSD;
    private String pendingSMS;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        setupWebView();

        webView.loadUrl("file:///android_asset/index.html");

        registerReceiver(smsReceiver,
                new IntentFilter("android.provider.Telephony.SMS_RECEIVED"));
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

            runOnUiThread(() -> sendSMS(number, message, simSlot));
        }

        @JavascriptInterface
        public void setSystemColor(String color) {

            runOnUiThread(() -> changeSystemColor(color));
        }
    }

    private void changeSystemColor(String colorString) {

        try {

            int color = Color.parseColor(colorString);

            Window window = getWindow();

            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {

                window.setStatusBarColor(color);
                window.setNavigationBarColor(color);
            }

        } catch (Exception e) {

            Log.e("COLOR","Invalid color");
        }
    }

    private void executeUSSD(String code, int simSlot) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {

            sendResult("USSD requires Android 8+");
            return;
        }

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this,
                        Manifest.permission.READ_PHONE_STATE)
                        != PackageManager.PERMISSION_GRANTED) {

            pendingUSSD = code + "|" + simSlot;

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.READ_PHONE_STATE
                    },
                    REQUEST_USSD_PERMISSION);

            return;
        }

        try {

            SubscriptionManager subscriptionManager =
                    (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);

            List<SubscriptionInfo> list =
                    subscriptionManager.getActiveSubscriptionInfoList();

            if (list == null || list.size() <= simSlot) {

                sendResult("SIM not available");
                return;
            }

            int subId = list.get(simSlot).getSubscriptionId();

            TelephonyManager telephonyManager =
                    ((TelephonyManager) getSystemService(TELEPHONY_SERVICE))
                            .createForSubscriptionId(subId);

            telephonyManager.sendUssdRequest(
                    code,
                    new TelephonyManager.UssdResponseCallback() {

                        @Override
                        public void onReceiveUssdResponse(
                                TelephonyManager telephonyManager,
                                String request,
                                CharSequence response) {

                            sendResult(response.toString());
                        }

                        @Override
                        public void onReceiveUssdResponseFailed(
                                TelephonyManager telephonyManager,
                                String request,
                                int failureCode) {

                            sendResult("USSD failed: "+failureCode);
                        }

                    },
                    new Handler(Looper.getMainLooper())
            );

        } catch (Exception e) {

            sendResult("USSD error: "+e.getMessage());
        }
    }

    private void sendSMS(String number,String message,int simSlot) {

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this,
                        Manifest.permission.READ_PHONE_STATE)
                        != PackageManager.PERMISSION_GRANTED) {

            pendingSMS = number+"|"+message+"|"+simSlot;

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.SEND_SMS,
                            Manifest.permission.READ_PHONE_STATE
                    },
                    REQUEST_SMS_PERMISSION);

            return;
        }

        try {

            SubscriptionManager subscriptionManager =
                    (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);

            List<SubscriptionInfo> list =
                    subscriptionManager.getActiveSubscriptionInfoList();

            if (list == null || list.size() <= simSlot) {

                sendResult("SIM not available");
                return;
            }

            int subId = list.get(simSlot).getSubscriptionId();

            SmsManager smsManager =
                    SmsManager.getSmsManagerForSubscriptionId(subId);

            smsManager.sendTextMessage(
                    number,
                    null,
                    message,
                    null,
                    null
            );

            sendResult("SMS sent");

        } catch (Exception e) {

            sendResult("SMS error: "+e.getMessage());
        }
    }

    private void sendResult(String message) {

        String safe = message
                .replace("\\","\\\\")
                .replace("'","\\'")
                .replace("\n","\\n");

        webView.post(() ->
                webView.evaluateJavascript(
                        "showResult('"+safe+"')",
                        null));
    }

    private void sendIncomingSMS(String number,String message){

        String safeNumber = number.replace("'","\\'");
        String safeMessage = message
                .replace("\\","\\\\")
                .replace("'","\\'")
                .replace("\n","\\n");

        webView.post(() ->
                webView.evaluateJavascript(
                        "onSMSReceived('"+safeNumber+"','"+safeMessage+"')",
                        null));
    }

    private BroadcastReceiver smsReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {

            Bundle bundle = intent.getExtras();

            if(bundle==null) return;

            Object[] pdus = (Object[]) bundle.get("pdus");

            if(pdus==null) return;

            for(Object pdu:pdus){

                SmsMessage sms;

                if(Build.VERSION.SDK_INT>=23){

                    String format = bundle.getString("format");

                    sms = SmsMessage.createFromPdu((byte[])pdu,format);

                }else{

                    sms = SmsMessage.createFromPdu((byte[])pdu);
                }

                String number = sms.getOriginatingAddress();
                String message = sms.getMessageBody();

                sendIncomingSMS(number,message);
            }
        }
    };

    @Override
    protected void onDestroy() {

        super.onDestroy();

        unregisterReceiver(smsReceiver);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {

        super.onRequestPermissionsResult(requestCode,permissions,grantResults);

        if(requestCode==REQUEST_USSD_PERMISSION){

            if(grantResults.length>0 &&
                    grantResults[0]==PackageManager.PERMISSION_GRANTED){

                if(pendingUSSD!=null){

                    String[] p=pendingUSSD.split("\\|");

                    executeUSSD(p[0],Integer.parseInt(p[1]));

                    pendingUSSD=null;
                }
            }
        }

        if(requestCode==REQUEST_SMS_PERMISSION){

            if(grantResults.length>0 &&
                    grantResults[0]==PackageManager.PERMISSION_GRANTED){

                if(pendingSMS!=null){

                    String[] p=pendingSMS.split("\\|");

                    sendSMS(p[0],p[1],Integer.parseInt(p[2]));

                    pendingSMS=null;
                }
            }
        }
    }
}