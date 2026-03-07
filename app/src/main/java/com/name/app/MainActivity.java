package com.example.ussdwebview;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    public static WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = (WebView) findViewById(R.id.webview);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);

        webView.setWebViewClient(new WebViewClient());

        webView.addJavascriptInterface(new JSBridge(), "AndroidUSSD");

        webView.loadUrl("file:///android_asset/index.html");
    }

    // ---------- JAVASCRIPT BRIDGE ----------
    class JSBridge {

        @JavascriptInterface
        public void runUssd(String code) {

            String ussd = code.replace("#", Uri.encode("#"));

            Intent intent = new Intent(Intent.ACTION_CALL);
            intent.setData(Uri.parse("tel:" + ussd));
            startActivity(intent);
        }

        @JavascriptInterface
        public void sendSms(String phone, String message) {

            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(phone, null, message, null, null);
        }

        @JavascriptInterface
        public void reloadPage() {
            if (webView != null) {
                webView.post(new Runnable() {
                    @Override
                    public void run() {
                        webView.reload();
                    }
                });
            }
        }
    }

    // ---------- BOOT RECEIVER ----------
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

    // ---------- SMS RECEIVER ----------
    public static class SMSReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {

            if (webView != null) {

                webView.post(new Runnable() {
                    @Override
                    public void run() {
                        webView.reload();
                    }
                });

            }

        }
    }

    // ---------- BACK BUTTON ----------
    @Override
    public void onBackPressed() {

        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }

    }
}