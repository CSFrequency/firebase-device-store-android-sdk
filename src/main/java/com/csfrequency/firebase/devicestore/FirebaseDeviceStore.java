package com.csfrequency.firebase.devicestore;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Transaction;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.iid.InstanceIdResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FirebaseDeviceStore {
    private static final String DEFAULT_COLLECTION_PATH = "user-devices";
    private static final String TAG = "FirebaseDeviceStore";

    private final FirebaseAuth auth;
    private final String collectionPath;
    private final Context context;
    private final FirebaseFirestore firestore;
    private final FirebaseInstanceId instanceId;

    private FirebaseAuth.AuthStateListener authStateListener;
    private String currentToken = null;
    private FirebaseUser currentUser = null;
    private boolean subscribed = false;

    public FirebaseDeviceStore(Context context, FirebaseApp app) {
        this(context, app, DEFAULT_COLLECTION_PATH);
    }

    public FirebaseDeviceStore(Context context, FirebaseApp app, String collectionPath) {
        this.auth = FirebaseAuth.getInstance(app);
        this.collectionPath = collectionPath;
        this.context = context;
        this.firestore = FirebaseFirestore.getInstance(app);
        this.instanceId = FirebaseInstanceId.getInstance(app);

        LocalBroadcastManager.getInstance(context).registerReceiver(new TokenReceiver(), new IntentFilter(FDSMessagingService.NEW_TOKEN_INTENT));
    }

    public void signOut() {
        if (currentUser != null && currentToken != null) {
            deleteToken(currentUser.getUid(), currentToken);
        }

        // Clear the cached user
        currentUser = null;
    }

    public void subscribe() {
        // Prevent duplicate subscriptions
        if (subscribed) {
            return;
        }

        // Throw an error if notification permissions have not been granted
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            throw new RuntimeException("Notifications are not enabled");
        }

        subscribed = true;

        currentUser = auth.getCurrentUser();

        // Load the current FCM token
        instanceId.getInstanceId().addOnCompleteListener(new OnCompleteListener<InstanceIdResult>() {
            @Override
            public void onComplete(@NonNull Task<InstanceIdResult> task) {
                if (task.isSuccessful()) {
                    currentToken = task.getResult().getToken();

                    if (currentToken != null && currentUser != null) {
                        addToken(currentUser.getUid(), currentToken);
                    }
                } else {
                    Log.w(TAG, "Failed to load FCM token", task.getException());
                }
            }
        });

        authStateListener = new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                FirebaseUser authUser = firebaseAuth.getCurrentUser();

                if (authUser != null && currentUser == null && currentToken != null) {
                    currentUser = authUser;

                    addToken(currentUser.getUid(), currentToken);
                } else if (authUser == null && currentUser != null) {
                    Log.w(TAG, "You need to call the `logout` method on the DeviceStore before logging out the user");

                    // Clear the cached user
                    currentUser = authUser;
                }

            }
        };

        auth.addAuthStateListener(authStateListener);
    }

    public void unsubscribe() {
        if (authStateListener != null) {
            auth.removeAuthStateListener(authStateListener);
            authStateListener = null;
        }
        // Reset state
        currentToken = null;
        currentUser = null;
        // Clear subscription flag
        subscribed = false;
    }

    private void addToken(final String userId, final String token) {
        final DocumentReference docRef = userRef(userId);

        firestore.runTransaction(new Transaction.Function<Void>() {
           @Override
           public Void apply(Transaction transaction) throws FirebaseFirestoreException {
               DocumentSnapshot doc = transaction.get(docRef);

               if (doc.exists()) {
                   List<Map<String, String>> devices = getDevices(doc);
                   // Add the new device if it doesn't already exist
                   if (!containsDevice(devices, token)) {
                       devices.add(createDevice(token));
                   }
                   // Update the document
                   transaction.update(docRef, "devices", devices);
               } else {
                   Map<String, Object> data = new HashMap<>();
                   data.put("devices", createDevice(token));
                   data.put("userId", userId);

                   transaction.set(docRef, data);
               }
               return null;
           }
        });
    }

    private void deleteToken(final String userId, final String token) {
        final DocumentReference docRef = userRef(userId);

        firestore.runTransaction(new Transaction.Function<Void>() {
            @Override
            public Void apply(Transaction transaction) throws FirebaseFirestoreException {
                DocumentSnapshot doc = transaction.get(docRef);

                if (doc.exists()) {
                    List<Map<String, String>> devices = getDevices(doc);
                    // Remove the old device
                    devices = removeDevice(devices, token);
                    // Update the document
                    transaction.update(docRef, "devices", devices);
                } else {
                    Map<String, Object> data = new HashMap<>();
                    data.put("devices", new ArrayList<>());
                    data.put("userId", userId);

                    transaction.set(docRef, data);
                }
                return null;
            }
        });
    }

    private void updateToken(final String userId, final String oldToken, final String newToken) {
        final DocumentReference docRef = userRef(userId);

        firestore.runTransaction(new Transaction.Function<Void>() {
            @Override
            public Void apply(Transaction transaction) throws FirebaseFirestoreException {
                DocumentSnapshot doc = transaction.get(docRef);

                if (doc.exists()) {
                    List<Map<String, String>> devices = getDevices(doc);
                    // Remove the old device
                    if (oldToken != null) {
                        devices = removeDevice(devices, oldToken);
                    }
                    // Add the new device if it doesn't already exist
                    if (newToken != null) {
                        if (!containsDevice(devices, newToken)) {
                            devices.add(createDevice(newToken));
                        }
                    }
                    // Update the document
                    transaction.update(docRef, "devices", devices);
                } else if (newToken != null) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("devices", createDevice(newToken));
                    data.put("userId", userId);

                    transaction.set(docRef, data);
                } else {
                    Map<String, Object> data = new HashMap<>();
                    data.put("devices", new ArrayList<>());
                    data.put("userId", userId);

                    transaction.set(docRef, data);
                }
                return null;
            }
        });
    }

    private boolean containsDevice(List<Map<String, String>> devices, String token) {
        for (Map<String, String> device : devices) {
            if (token.equals(device.get("fcmToken"))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> createDevice(String token) {
        Map<String, String> device = new HashMap<>();
        device.put("deviceId", getDeviceId());
        device.put("fcmToken", token);
        device.put("name", getDeviceName());
        device.put("os", getOS());
        device.put("type", "Android");

        return device;
    }

    private String getDeviceId() {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    private List<Map<String, String>> getDevices(DocumentSnapshot snapshot) {
        List<Map<String, String>> devices = (List<Map<String, String>>) snapshot.get("devices");
        if (devices == null) {
            return new ArrayList<>();
        }
        return devices;
    }

    private String getDeviceName() {
        String permission = "android.permission.BLUETOOTH";
        int res = context.checkCallingOrSelfPermission(permission);
        if (res == PackageManager.PERMISSION_GRANTED) {
            try {
                BluetoothAdapter myDevice = BluetoothAdapter.getDefaultAdapter();
                if (myDevice != null) {
                    return myDevice.getName();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return "Unknown";
    }

    private String getOS() {
        return "Android " + Build.VERSION.RELEASE;
    }

    private List<Map<String, String>> removeDevice(List<Map<String, String>> devices, String token) {
        List<Map<String, String>> filteredDevices = new ArrayList<>();
        for (Map<String, String> device : devices) {
            if (!token.equals(device.get("fcmToken"))) {
                filteredDevices.add(device);
            }
        }
        return filteredDevices;
    }

    private DocumentReference userRef(String userId) {
        return firestore.collection(collectionPath).document(userId);
    }

    private class TokenReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Ignore token changes if the store isn't subscribed
            if (!subscribed) {
                return;
            }

            String token = intent.getStringExtra(FDSMessagingService.TOKEN_EXTRA);
            // If the token has changed, then update it
            if ((token == null && currentToken != null || !token.equals(currentToken)) && currentUser != null) {
                updateToken(currentUser.getUid(), currentToken, token);
            }
            // Update the cached token
            currentToken = token;
        }
    }
}
