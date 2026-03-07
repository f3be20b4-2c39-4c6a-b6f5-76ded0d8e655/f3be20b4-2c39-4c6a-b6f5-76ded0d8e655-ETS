package com.example.ussdwebview;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

public class SMSReceiver extends BroadcastReceiver {

    private MainActivity mainActivity;

    public SMSReceiver() {
    }

    public SMSReceiver(MainActivity mainActivity) {
        this.mainActivity = mainActivity;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals("android.provider.Telephony.SMS_RECEIVED")) {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                try {
                    Object[] pdus = (Object[]) bundle.get("pdus");
                    String format = bundle.getString("format");

                    if (pdus != null) {
                        for (Object pdu : pdus) {
                            SmsMessage smsMessage;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                smsMessage = SmsMessage.createFromPdu((byte[]) pdu, format);
                            } else {
                                smsMessage = SmsMessage.createFromPdu((byte[]) pdu);
                            }

                            String phoneNumber = smsMessage.getOriginatingAddress();
                            String messageBody = smsMessage.getMessageBody();

                            Log.d("SMSReceiver", "SMS from: " + phoneNumber + " Message: " + messageBody);

                            if (mainActivity != null) {
                                mainActivity.displaySMSOnWebView(phoneNumber, messageBody);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e("SMSReceiver", "Error receiving SMS: " + e.getMessage());
                }
            }
        }
    }
}
