# Firebase Device Store (Android SDK)

Automatically store Device and FCM Token information for Firebase Auth Users in Cloud Firestore.

[![Download](https://api.bintray.com/packages/csfrequency/maven/firebase-device-store-android-sdk/images/download.svg) ](https://bintray.com/csfrequency/maven/firebase-device-store-android-sdk/_latestVersion)

> This library is a proof of concept, and very much a work in progress.

## Installation

Update the root level `build.gradle` with the CS Frequency Maven repository:

> This will not be necessary once the library has been accepted into JCenter

```
allprojects {
    repositories {
        mavenLocal()
        google()
        jcenter()
        // Add this section
        maven {
            url 'http://dl.bintray.com/csfrequency/maven'
        }
    }
}
```

Add the following dependency to your `app/build.gradle`:

```
implementation "com.csfrequency.firebase.devicestore:firebase-device-store:0.0.3"
```

## Example usage

```
import com.csfrequency.firebase.devicestore.FirebaseDeviceStore;

FirebaseDeviceStore deviceStore = new FirebaseDeviceStore(this.getApplicationContext(), FirebaseApp.getInstance(), "user-devices");
deviceStore.subscribe();
```

## Documentation

Firebase Device Store automatically stores device and FCM information for Firebase Auth users in Cloud Firestore.

### Data Model

A Document is created in the Cloud Firestore collection for each logged in user:

```
/user-devices
  - userId1: {},
  - userId2: {},
```

The structure of this Document is as follows:

```
{
  devices: Device[],
  userId: string,
}
```

A `Device` object contains the following:

```
{
  deviceId: string, // The browser name and version
  fcmToken: string, // The FCM token
  name: 'Unknown',  // Web browser's do not provide a name field
  os: string,       // The OS of the device
  type: 'Web'
}
```

### API

#### `FirebaseDeviceStore(context, app, collectionPath)`

Create a new DeviceStore.

Parameters:

- `context`: `Context` the application context
- `app`: `FirebaseApp` the Firebase App to use
- `collectionPath`: (Optional) `string` the Cloud Firestore collection where devices should be stored. Defaults to `user-devices`.

Returns a `FirebaseDeviceStore`.

#### `FirebaseDeviceStore.signOut(): void`

Indicate to the DeviceStore that the user is about to sign out, and the current device token should be removed.

This cannot be done automatically with `onAuthStateChanged` as the user won't have permission to remove the token from Firestore as they are already signed out by this point and the Cloud Firestore security rules will prevent the database deletion.

#### `FirebaseDeviceStore.subscribe(): void`

Subscribe a device store to the Firebase App. This will:

1. Subscribe to Firebase Auth and listen to changes in authentication state
2. Subscribe to Firebase Messaging and listen to changes in the FCM token
3. Automatically store device and FCM token information in the Cloud Firestore collection you specify

#### `FirebaseDeviceStore.unsubscribe(): void`

Unsubscribe the device store from the Firebase App.

### Security rules

You will need to add the following security rules for your Cloud Firestore collection:

```
service cloud.firestore {
  match /databases/{database}/documents {
    // Add this rule, replacing `user-devices` with the collection path you would like to use:
    match /user-devices/{userId} {
      allow create, read, update, delete: if request.auth.uid == userId;
    }
  }
}
```
