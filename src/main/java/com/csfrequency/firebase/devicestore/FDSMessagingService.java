package com.csfrequency.firebase.devicestore;

import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;

import com.google.firebase.messaging.FirebaseMessagingService;

public class FDSMessagingService extends FirebaseMessagingService {
    public static final String NEW_TOKEN_INTENT = "onNewToken";
    public static final String TOKEN_EXTRA = "token";

    @Override
    public void onNewToken(String token) {
        Intent newTokenIntent = new Intent(NEW_TOKEN_INTENT);
        newTokenIntent.putExtra(TOKEN_EXTRA, token);
        LocalBroadcastManager.getInstance(this).sendBroadcast(newTokenIntent);
    }
}
