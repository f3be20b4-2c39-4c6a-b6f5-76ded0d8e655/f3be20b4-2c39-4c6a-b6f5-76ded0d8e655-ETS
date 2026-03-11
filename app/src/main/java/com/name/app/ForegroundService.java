package com.example.ussdwebview;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

public class ForegroundService extends Service {
    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification();
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
                .setContentText("Monitoring Server...")
                .setSmallIcon(android.R.drawable.app_icon)
                .setOngoing(true);

        startForeground(1, builder.build());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}