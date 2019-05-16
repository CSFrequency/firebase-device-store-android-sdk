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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FirebaseDeviceStore {
    private static final String DEFAULT_COLLECTION_PATH = "user-devices";
    private static final String DEVICE_ID_FIELD = "deviceId";
    private static final String DEVICES_FIELD = "devices";
    private static final String FCM_TOKEN_FIELD = "fcmToken";
    private static final String NAME_FIELD = "name";
    private static final String OS_FIELD = "os";
    private static final String TAG = "FirebaseDeviceStore";
    private static final String TYPE_FIELD = "type";
    private static final String USER_ID_FIELD = "userId";

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
            deleteDevice(currentUser.getUid());
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
                        updateDevice(currentUser.getUid(), currentToken);
                    }
                } else {
                    Log.w(TAG, "Failed to load FCM token", task.getException());
                }
            }
        });

        authStateListener = new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {

                // TODO: Are we guranteed that this callback fires before the InstanceId listener
                // above fires? If the instance ID fires before we assign the currentUserm then we
                // will never set the token.

                FirebaseUser authUser = firebaseAuth.getCurrentUser();

                if (authUser != null && currentUser == null && currentToken != null) {
                    currentUser = authUser;

                    updateDevice(currentUser.getUid(), currentToken);
                } else if (authUser == null && currentUser != null) {
                    Log.w(TAG, "You need to call the `logout` method on the DeviceStore before logging out the user");

                    // Clear the cached user
                    currentUser = authUser;
                }

                // TODO: Is authUser.equals(currentUser) always true at this point? Would it make
                // sense to add an assert?

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

    private void deleteDevice(final String userId) {
        final DocumentReference docRef = userRef(userId);

        // TODO: This code might never have a chance to run if a user calls "signOut" and then
        // exists out of the client. If this cleanup is required, then signOut() will have to return
        // a Task.
        firestore.runTransaction(new Transaction.Function<Void>() {
            @Override
            public Void apply(Transaction transaction) throws FirebaseFirestoreException {
                DocumentSnapshot doc = transaction.get(docRef);

                // TODO: Would it be possible to achieve the same with FieldValue.arrayRemove()?
                if (doc.exists()) {
                    List<Map<String, String>> devices = getDevices(doc);
                    // Remove the old device
                    devices = removeCurrentDevice(devices);
                    // Update the document
                    transaction.update(docRef, DEVICES_FIELD, devices);
                } else {
                    // TODO: Is it necessary to create this empty list if the document does not
                    // already exist?
                    Map<String, Object> userDevices = createUserDevices(userId, null);
                    transaction.set(docRef, userDevices);
                }
                return null;
            }
        });
    }

    private void updateDevice(final String userId, final String token) {
        final DocumentReference docRef = userRef(userId);

        // TODO: Same as above - this should return a Task and the callee should wait for it to
        // succeed.
        firestore.runTransaction(new Transaction.Function<Void>() {
            @Override
            public Void apply(Transaction transaction) throws FirebaseFirestoreException {
                DocumentSnapshot doc = transaction.get(docRef);

                // TODO: Explore replacing this with FieldValue.arrayUnion()
                if (doc.exists()) {
                    List<Map<String, String>> devices = getDevices(doc);
                    if (containsCurrentDevice(devices)) {
                        // Update the device token if it already exists
                        updateCurrentDevice(devices, token);
                    } else {
                        // Add the device if it doesn't already exist
                        devices.add(createCurrentDevice(token));
                    }
                    // Update the document
                    transaction.update(docRef, DEVICES_FIELD, devices);
                } else {
                    Map<String, Object> userDevices = createUserDevices(userId, token);
                    transaction.set(docRef, userDevices);
                }
                return null;
            }
        });
    }

    private boolean containsCurrentDevice(List<Map<String, String>> devices) {
        String deviceId = getDeviceId();
        for (Map<String, String> device : devices) {
            if (deviceId.equals(device.get(DEVICE_ID_FIELD))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> createCurrentDevice(String token) {
        Map<String, String> device = new HashMap<>();
        device.put(DEVICE_ID_FIELD, getDeviceId());
        device.put(FCM_TOKEN_FIELD, token);
        device.put(NAME_FIELD, getDeviceName());
        device.put(OS_FIELD, getOS());
        device.put(TYPE_FIELD, "Android");

        return device;
    }

    private Map<String, Object> createUserDevices(String userId, String token) {
        Map<String, Object> userDevices = new HashMap<>();
        userDevices.put(DEVICES_FIELD, token == null ? Arrays.asList() : Arrays.asList(createCurrentDevice(token)));
        userDevices.put(USER_ID_FIELD, userId);

        return userDevices;
    }

    private String getDeviceId() {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    private List<Map<String, String>> getDevices(DocumentSnapshot snapshot) {
        List<Map<String, String>> devices = (List<Map<String, String>>) snapshot.get(DEVICES_FIELD);
        if (devices == null) {
            return new ArrayList<>();
        }
        return devices;
    }

    private String getDeviceName() {
        // TODO: Move this to a class level constant.
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

    private List<Map<String, String>> removeCurrentDevice(List<Map<String, String>> devices) {
        String deviceId = getDeviceId();
        // TODO: Maybe initialize with initialCapacity of devices.size() - 1
        List<Map<String, String>> filteredDevices = new ArrayList<>();
        for (Map<String, String> device : devices) {
            if (!deviceId.equals(device.get(DEVICE_ID_FIELD))) {
                filteredDevices.add(device);
            }
        }
        return filteredDevices;
    }

    private void updateCurrentDevice(List<Map<String, String>> devices, String token) {
        String deviceId = getDeviceId();
        for (Map<String, String> device : devices) {
            if (deviceId.equals(device.get(DEVICE_ID_FIELD))) {
                device.put(FCM_TOKEN_FIELD, token);
            }
        }
    }

    private DocumentReference userRef(String userId) {
        // TODO: Add Preconditions to verify that the userId is not-null
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
            // TODO: Try to simiplify this statement, maybe by pulling out the currentUser check.
            // You might be able to add it to line 305.
            if ((token == null && currentToken != null || !token.equals(currentToken)) && currentUser != null) {
                updateDevice(currentUser.getUid(), token);
            }
            // Update the cached token
            currentToken = token;
        }
    }
}
