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
import com.google.android.gms.common.internal.Preconditions;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldPath;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.iid.InstanceIdResult;
import java.util.HashMap;
import java.util.Map;

public class FirebaseDeviceStore {
  private static final String BLUETOOTH_PERMISSION = "android.permission.BLUETOOTH";
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

    LocalBroadcastManager.getInstance(context)
        .registerReceiver(
            new TokenReceiver(), new IntentFilter(FDSMessagingService.NEW_TOKEN_INTENT));
  }

  public Task<Void> signOut() {
    if (currentUser != null && currentToken != null) {
      // Store the UID before we clear the user
      String uid = currentUser.getUid();
      currentUser = null;
      return deleteDevice(uid);
    }

    // Clear the cached user
    currentUser = null;
    return Tasks.forResult(null);
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

    // Load the current FCM token and update Firestore if there is a logged in user
    instanceId
        .getInstanceId()
        .addOnCompleteListener(
            new OnCompleteListener<InstanceIdResult>() {
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

    // Listen to the auth state and update Firestore if there is a logged in user and FCM token
    authStateListener =
        new FirebaseAuth.AuthStateListener() {
          @Override
          public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
            FirebaseUser authUser = firebaseAuth.getCurrentUser();

            if (authUser != null && currentUser == null) {
              currentUser = authUser;

              if (currentToken != null) {
                updateDevice(currentUser.getUid(), currentToken);
              }
            } else if (authUser == null && currentUser != null) {
              Log.w(
                  TAG,
                  "You need to call the `logout` method on the DeviceStore before logging out the user");

              // Clear the cached user
              currentUser = authUser;
            }

            assert currentUser == authUser;
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

  private Task<Void> deleteDevice(final String userId) {
    final DocumentReference docRef = userRef(userId);
    return docRef.update(FieldPath.of(DEVICES_FIELD, getDeviceId()), FieldValue.delete());
  }

  private Task<Void> updateDevice(final String userId, final String token) {
    final DocumentReference docRef = userRef(userId);
    return docRef.set(createUserDevices(userId, token), SetOptions.merge());
  }

  private Map<String, String> createDevice(String deviceId, String token) {
    Map<String, String> device = new HashMap<>();
    device.put(DEVICE_ID_FIELD, deviceId);
    device.put(FCM_TOKEN_FIELD, token);
    device.put(NAME_FIELD, getDeviceName());
    device.put(OS_FIELD, getOS());
    device.put(TYPE_FIELD, "Android");

    return device;
  }

  private Map<String, Object> createUserDevices(String userId, String token) {
    Map<String, Map<String, String>> devices = new HashMap<>();
    String deviceId = getDeviceId();
    devices.put(deviceId, createDevice(deviceId, token));

    Map<String, Object> userDevices = new HashMap<>();
    userDevices.put(DEVICES_FIELD, devices);
    userDevices.put(USER_ID_FIELD, userId);

    return userDevices;
  }

  private String getDeviceId() {
    return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
  }

  private String getDeviceName() {
    int res = context.checkCallingOrSelfPermission(BLUETOOTH_PERMISSION);
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

  private DocumentReference userRef(String userId) {
    Preconditions.checkNotNull(userId);
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

      // If there's no current user, just update the cached token
      if (currentUser == null) {
        currentToken = token;
        return;
      }

      // If the token has changed, then update in Firestore and update the cached token
      if (token == null && currentToken != null || !token.equals(currentToken)) {
        updateDevice(currentUser.getUid(), token);
        currentToken = token;
      }
    }
  }
}
