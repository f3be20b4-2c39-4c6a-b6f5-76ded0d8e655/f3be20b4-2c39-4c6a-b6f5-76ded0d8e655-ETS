package com.example.ussdwebview;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class USSDService extends AccessibilityService {

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            AccessibilityNodeInfo nodeInfo = event.getSource();
            if (nodeInfo != null) {
                CharSequence text = nodeInfo.getText();
                if (text != null && text.length() > 0) {
                    String msg = text.toString();
                    Log.d("USSDService", "USSD Response: " + msg);

                    // Send to WebView
                    if (MainActivity.instance != null) {
                        MainActivity.instance.sendResultToWeb(msg);
                    }

                    performGlobalAction(GLOBAL_ACTION_BACK); // dismiss USSD dialog
                }
            }
        }
    }

    @Override
    public void onInterrupt() {}
}