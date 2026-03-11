package com.example.ussdwebview;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;

public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                Object[] pdus = (Object[]) bundle.get("pdus");
                if (pdus != null && pdus.length > 0) {
                    StringBuilder smsBody = new StringBuilder();
                    for (Object pdu : pdus) {
                        SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu);
                        smsBody.append(sms.getMessageBody());
                    }

                    // Send SMS content to WebView if MainActivity is active
                    MainActivity mainActivity = MainActivity.getInstance();
                    if (mainActivity != null) {
                        mainActivity.displaySMS(smsBody.toString());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}