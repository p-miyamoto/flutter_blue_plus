// Copyright 2017, Paul DeMarco.
// All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.boskokg.flutter_blue_plus;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import java.lang.reflect.Method;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;

public class FlutterBluePlusPlugin implements FlutterPlugin, MethodCallHandler, RequestPermissionsResultListener, ActivityAware {

  private static final String TAG = "[FBP-Android]";
  private final Object initializationLock = new Object();
  private final Object tearDownLock = new Object();
  private Context context;
  private MethodChannel methodChannel;
  private static final String NAMESPACE = "flutter_blue_plus";

  private BluetoothManager mBluetoothManager;
  private BluetoothAdapter mBluetoothAdapter;

  private FlutterPluginBinding pluginBinding;
  private ActivityPluginBinding activityBinding;

  static final private UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
  private final Map<String, BluetoothGatt> mConnectedDevices = new ConcurrentHashMap<>();
  private final Map<String, BluetoothGatt> mCurrentlyConnectingDevices = new ConcurrentHashMap<>();
  private final Map<String, Integer> mConnectionState = new ConcurrentHashMap<>();
  private final Map<String, Integer> mMtu = new ConcurrentHashMap<>();
  private LogLevel logLevel = LogLevel.DEBUG;

  private interface OperationOnPermission {
    void op(boolean granted, String permission);
  }

  private int lastEventId = 1452;
  private final Map<Integer, OperationOnPermission> operationsOnPermission = new HashMap<>();

  private final ArrayList<String> macDeviceScanned = new ArrayList<>();
  private boolean allowDuplicates = false;

  private BeaconReceiver beaconReceiver;

  public FlutterBluePlusPlugin() {}

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
    Log.d(TAG, "onAttachedToEngine");
    pluginBinding = flutterPluginBinding;
    setup(pluginBinding.getBinaryMessenger(),
            (Application) pluginBinding.getApplicationContext());
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    Log.d(TAG, "onDetachedFromEngine");
    pluginBinding = null;
    tearDown();
  }

  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    Log.d(TAG, "onAttachedToActivity");
    activityBinding = binding;
    activityBinding.addRequestPermissionsResultListener(this);
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
    Log.d(TAG, "onDetachedFromActivityForConfigChanges");
    onDetachedFromActivity();
  }

  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    Log.d(TAG, "onReattachedToActivityForConfigChanges");
    onAttachedToActivity(binding);
  }

  @Override
  public void onDetachedFromActivity() {
    Log.d(TAG, "onDetachedFromActivity");
    activityBinding.removeRequestPermissionsResultListener(this);
    activityBinding = null;
  }

  private void setup(
          final BinaryMessenger messenger,
          final Application application) {
    synchronized (initializationLock) {
      Log.d(TAG, "setup");
      this.context = application;
      methodChannel = new MethodChannel(messenger, NAMESPACE + "/methods");
      methodChannel.setMethodCallHandler(this);
      mBluetoothManager = (BluetoothManager) application.getSystemService(Context.BLUETOOTH_SERVICE);
      mBluetoothAdapter = mBluetoothManager.getAdapter();
      
      IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
      context.registerReceiver(mBluetoothStateReceiver, filter);
    }
  }

  private void tearDown() {
    synchronized (tearDownLock) {
      Log.d(TAG, "teardown");
      /*
      for (BluetoothGatt gatt : mConnectedDevices.values()) {
          if(gatt != null) {
            String remoteId = gatt.getDevice().getAddress();
          Log.d(TAG, "calling disconnect() on device: " + remoteId);
          Log.d(TAG, "calling gatt.close() on device: " + remoteId);
          gatt.disconnect();
          gatt.close();
        }
      }*/

      disconnectAllDevices("tearDown");

      context.unregisterReceiver(mBluetoothStateReceiver);
      context = null;
      methodChannel.setMethodCallHandler(null);
      methodChannel = null;
      mBluetoothAdapter = null;
      mBluetoothManager = null;
    }
  }


  ////////////////////////////////////////////////////////////
  // ███    ███  ███████  ████████  ██   ██   ██████   ██████
  // ████  ████  ██          ██     ██   ██  ██    ██  ██   ██
  // ██ ████ ██  █████       ██     ███████  ██    ██  ██   ██
  // ██  ██  ██  ██          ██     ██   ██  ██    ██  ██   ██
  // ██      ██  ███████     ██     ██   ██   ██████   ██████
  //
  //  ██████   █████   ██       ██
  // ██       ██   ██  ██       ██
  // ██       ███████  ██       ██
  // ██       ██   ██  ██       ██
  //  ██████  ██   ██  ███████  ███████

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
    try {
      if(mBluetoothAdapter == null && !"isAvailable".equals(call.method)) {
        result.error("bluetooth_unavailable", "the device does not have bluetooth", null);
        return;
      }

      switch (call.method) {
        case "setLogLevel":
        {
          int logLevelIndex = (int)call.arguments;
          logLevel = LogLevel.values()[logLevelIndex];
          result.success(null);
          break;
        }

        case "state":
        {
            // get adapterState, if we can
            int adapterState = -1;
            try {
                adapterState = mBluetoothAdapter.getState();
            } catch (Exception e) {}

            int convertedState;
            switch (adapterState) {
                case BluetoothAdapter.STATE_OFF:          convertedState = 6;           break;
                case BluetoothAdapter.STATE_ON:           convertedState = 4;           break;
                case BluetoothAdapter.STATE_TURNING_OFF:  convertedState = 5;           break;
                case BluetoothAdapter.STATE_TURNING_ON:   convertedState = 3;           break;
                default:                                  convertedState = 0;           break;
            }

            // see: BmBluetoothAdapterState
            HashMap<String, Object> map = new HashMap<>();
            map.put("state", convertedState);

            result.success(map);
            break;
        }

        case "isAvailable":
        {
          result.success(mBluetoothAdapter != null);
          break;
        }

        case "isOn":
        {
          result.success(mBluetoothAdapter.isEnabled());
          break;
        }

        case "turnOn":
        {
          if (!mBluetoothAdapter.isEnabled()) {
            result.success(mBluetoothAdapter.enable());
          }
          break;
        }

        case "turnOff":
        {
          if (mBluetoothAdapter.isEnabled()) {
            result.success(mBluetoothAdapter.disable());
          }
          break;
        }

        case "startScan":
        {
          ArrayList<String> permissions = new ArrayList<>();

          // see: BmScanSettings
          HashMap<String, Object> data = call.arguments();
          List<ScanFilter> filters = fetchFilters(data);
          allowDuplicates =          (boolean) data.get("allow_duplicates");
          int scanMode =                 (int) data.get("android_scan_mode");

          // Android 12+
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              permissions.add(Manifest.permission.BLUETOOTH_SCAN);
              permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
              //不要？必要であれば足す
              //if (usesFineLocation) {
              //    permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
              //}
          }

          // Android 11 or lower
          if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
              permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
          }

          ensurePermissions(permissions, (granted, perm) -> {
              if (granted == false) {
                  result.error("startScan", String.format("FlutterBluePlus requires %s permission", perm), null);
                  return;
              }

              macDeviceScanned.clear();

              BluetoothLeScanner scanner = mBluetoothAdapter.getBluetoothLeScanner();
              if(scanner == null) {
                  result.error("startScan", String.format("getBluetoothLeScanner() is null. Is the Adapter on?"), null);
                  return;
              }

              ScanSettings settings;
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                  settings = new ScanSettings.Builder()
                      .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                      .setLegacy(false)
                      .setScanMode(scanMode)
                      .build();
              } else {
                  settings = new ScanSettings.Builder()
                      .setScanMode(scanMode).build();
              }

              scanner.startScan(filters, settings, getScanCallback());

              result.success(null);
          });
          break;
        }

        case "stopScan":
        {
          BluetoothLeScanner scanner = mBluetoothAdapter.getBluetoothLeScanner();

          if(scanner != null) {
              scanner.stopScan(getScanCallback());
          }

          result.success(null);
          break;
        }

        case "getConnectedDevices":
        {
          ArrayList<String> permissions = new ArrayList<>();

          // Android 12+
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
          }

          ensurePermissions(permissions, (granted, perm) -> {

              if (!granted) {
                  result.error("getConnectedDevices",
                      String.format("FlutterBluePlus requires %s permission", perm), null);
                  return;
              }

              // this includes devices connected by other apps
              List<BluetoothDevice> devices = mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT);

              List<HashMap<String, Object>> devList = new ArrayList<HashMap<String, Object>>();
              for (BluetoothDevice d : devices) {
                  devList.add(bmBluetoothDevice(d));
              }

              HashMap<String, Object> response = new HashMap<>();
              response.put("devices", devList);

              result.success(response);
          });
          break;
        }

        case "getBondedDevices":
        {
          final Set<BluetoothDevice> bondedDevices = mBluetoothAdapter.getBondedDevices();

          List<HashMap<String,Object>> devList = new ArrayList<HashMap<String,Object>>();
          for (BluetoothDevice d : bondedDevices) {
              devList.add(bmBluetoothDevice(d));
          }

          HashMap<String, Object> response = new HashMap<String, Object>();
          response.put("devices", devList);

          result.success(response);
          break;
        }

        case "connect":
        {
          ArrayList<String> permissions = new ArrayList<>();

          // Android 12+
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
          }

          ensurePermissions(permissions, (granted, perm) -> {

              if (!granted) {
                  result.error("connect",
                      String.format("FlutterBluePlus requires %s for new connection", perm), null);
                  return;
              }

              // see: BmConnectRequest
              HashMap<String, Object> args = call.arguments();
              String remoteId =  (String)  args.get("remote_id");
              boolean autoConnect = (boolean)  args.get("android_auto_connect");

              // already connecting?
              if (mCurrentlyConnectingDevices.get(remoteId) != null) {
                  log(LogLevel.DEBUG, "already connecting");
                  result.success(null);  // still work to do
                  return;
              }

              // already connected?
              if (mConnectedDevices.get(remoteId) != null) {
                  log(LogLevel.DEBUG, "already connected");
                  result.success(null);  // no work to do
                  return;
              }

              // connect with new gatt
              BluetoothGatt gatt;
              BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(remoteId);
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                  gatt = device.connectGatt(context, autoConnect, mGattCallback, BluetoothDevice.TRANSPORT_LE);
              } else {
                  gatt = device.connectGatt(context, autoConnect, mGattCallback);
              }

              // error check
              if (gatt == null) {
                  result.error("connect", String.format("device.connectGatt returned null"), null);
                  return;
              }

              // add to currently connecting peripherals
              mCurrentlyConnectingDevices.put(remoteId, gatt);

              result.success(null);
          });
          break;
        }

        case "disconnect":
        {
          String remoteId = (String) call.arguments;

          // already disconnected?
          BluetoothGatt gatt = null;
          if (gatt == null) {
              gatt = mCurrentlyConnectingDevices.get(remoteId);
              if (gatt != null) {
                  log(LogLevel.DEBUG, "disconnect: cancelling connection in progress");
              }
          }
          if (gatt == null) {
              gatt = mConnectedDevices.get(remoteId);;
          }
          if (gatt == null) {
              log(LogLevel.DEBUG, "already disconnected");
              result.success(null);  // no work to do
              return;
          }

          // disconnect
          gatt.disconnect();

          // was connecting?
          if (mCurrentlyConnectingDevices.get(remoteId) != null) {

            // remove
            mCurrentlyConnectingDevices.remove(remoteId);

            // cleanup
            gatt.close();

            // see: BmConnectionStateResponse
            HashMap<String, Object> response = new HashMap<>();
            response.put("remote_id", remoteId);
            response.put("state", bmConnectionStateEnum(BluetoothProfile.STATE_DISCONNECTED));

            invokeMethodUIThread("DeviceState", response);
          }

          result.success(null);
          break;
        }

        case "deviceState":
        {
          String remoteId = (String) call.arguments;

          // get the connection state of *our app*
          // We don't care if other apps are connected
          int cs = connectionStateOfThisApp(remoteId);

          // see: BmConnectionStateResponse
          HashMap<String, Object> response = new HashMap<>();
          response.put("state", bmConnectionStateEnum(cs));
          response.put("remote_id", remoteId);

          result.success(response);
          break;
        }

        case "discoverServices":
        {
          String remoteId = (String) call.arguments;

          BluetoothGatt gatt = locateGatt(remoteId);

          if(gatt.discoverServices() == false) {
              result.error("discover_services", "gatt.discoverServices() returned false", null);
              break;
          }

          result.success(null);
          break;
        }

        case "services":
        {
          //TODO: androidは未実装！！
          /*String deviceId = (String)call.arguments;
          try {
            BluetoothGatt gatt = locateGatt(deviceId);
            Protos.DiscoverServicesResult.Builder p = Protos.DiscoverServicesResult.newBuilder();
            p.setRemoteId(deviceId);
            for(BluetoothGattService s : gatt.getServices()){
              p.addServices(ProtoMaker.from(gatt.getDevice(), s, gatt));
            }
            result.success(p.build().toByteArray());
          } catch(Exception e) {
            result.error("get_services_error", e.getMessage(), e);
          }*/
          result.error("get_services_error", "not implements services only android.", null);
          break;
        }

        case "readCharacteristic":
        {
          // see: BmReadCharacteristicRequest
          HashMap<String, Object> data = call.arguments();
          String remoteId =             (String) data.get("remote_id");
          String serviceUuid =          (String) data.get("service_uuid");
          String secondaryServiceUuid = (String) data.get("secondary_service_uuid");
          String characteristicUuid =   (String) data.get("characteristic_uuid");

          BluetoothGatt gatt = locateGatt(remoteId);

          BluetoothGattCharacteristic characteristic = locateCharacteristic(gatt,
              serviceUuid, secondaryServiceUuid, characteristicUuid);

          if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
              result.error("read_characteristic_error",
                  "The READ property is not supported by this BLE characteristic", null);
              break;
          }

          if(gatt.readCharacteristic(characteristic) == false) {
              result.error("read_characteristic_error",
                  "gatt.readCharacteristic() returned false", null);
              break;
          }

          result.success(null);
          break;
        }

        case "readDescriptor":
        {
          // see: BmReadDescriptorRequest
          HashMap<String, Object> data = call.arguments();
          String remoteId =             (String) data.get("remote_id");
          String serviceUuid =          (String) data.get("service_uuid");
          String secondaryServiceUuid = (String) data.get("secondary_service_uuid");
          String characteristicUuid =   (String) data.get("characteristic_uuid");
          String descriptorUuid =       (String) data.get("descriptor_uuid");

          BluetoothGatt gatt = locateGatt(remoteId);

          BluetoothGattCharacteristic characteristic = locateCharacteristic(gatt,
              serviceUuid, secondaryServiceUuid, characteristicUuid);

          BluetoothGattDescriptor descriptor = locateDescriptor(characteristic, descriptorUuid);

          if(gatt.readDescriptor(descriptor) == false) {
              result.error("read_descriptor_error", "gatt.readDescriptor() returned false", null);
              break;
          }

          result.success(null);
          break;
        }

        case "writeCharacteristic":
        {
          // see: BmWriteCharacteristicRequest
          HashMap<String, Object> data = call.arguments();
          String remoteId =             (String) data.get("remote_id");
          String serviceUuid =          (String) data.get("service_uuid");
          String secondaryServiceUuid = (String) data.get("secondary_service_uuid");
          String characteristicUuid =   (String) data.get("characteristic_uuid");
          String value =                (String) data.get("value");
          int writeTypeInt =               (int) data.get("write_type");

          int writeType = writeTypeInt == 0 ?
              BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT :
              BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;

          BluetoothGatt gatt = locateGatt(remoteId);

          BluetoothGattCharacteristic characteristic = locateCharacteristic(gatt,
              serviceUuid, secondaryServiceUuid, characteristicUuid);

          // check writeable
          if(writeType == 1) {
              if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
                  result.error("write_characteristic_error",
                      "The WRITE_NO_RESPONSE property is not supported by this BLE characteristic", null);
                  break;
              }
          } else {
                if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
                  result.error("write_characteristic_error",
                      "The WRITE property is not supported by this BLE characteristic", null);
                  break;
              }
          }

          // check mtu
          Integer mtu = mMtu.get(remoteId);
          if (mtu != null && (mtu-3) < hexToBytes(value).length) {
              String s = "data longer than mtu allows. dataLength: " +
                  hexToBytes(value).length + "> max: " + (mtu-3);
              result.error("write_characteristic_error", s, null);
              break;
          }

          // Version 33
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

              int rv = gatt.writeCharacteristic(characteristic, hexToBytes(value), writeType);

              if (rv != BluetoothStatusCodes.SUCCESS) {
                  String s = "gatt.writeCharacteristic() returned " + rv + " : " + bluetoothStatusString(rv);
                  result.error("write_characteristic_error", s, null);
                  return;
              }

          } else {
              // set value
              if(!characteristic.setValue(hexToBytes(value))) {
                  result.error("write_characteristic_error", "characteristic.setValue() returned false", null);
                  break;
              }

              // Write type
              characteristic.setWriteType(writeType);

              // Write Char
              if(!gatt.writeCharacteristic(characteristic)){
                  result.error("write_characteristic_error", "gatt.writeCharacteristic() returned false", null);
                  break;
              }
          }

          result.success(null);
          break;
        }

        case "writeDescriptor":
        {
          // see: BmWriteDescriptorRequest
          HashMap<String, Object> data = call.arguments();
          String remoteId =             (String) data.get("remote_id");
          String serviceUuid =          (String) data.get("service_uuid");
          String secondaryServiceUuid = (String) data.get("secondary_service_uuid");
          String characteristicUuid =   (String) data.get("characteristic_uuid");
          String descriptorUuid =       (String) data.get("descriptor_uuid");
          String value =                (String) data.get("value");

          BluetoothGatt gatt = locateGatt(remoteId);

          BluetoothGattCharacteristic characteristic = locateCharacteristic(gatt,
              serviceUuid, secondaryServiceUuid, characteristicUuid);

          BluetoothGattDescriptor descriptor = locateDescriptor(characteristic, descriptorUuid);

          // check mtu
          Integer mtu = mMtu.get(remoteId);
          if (mtu != null && (mtu-3) < hexToBytes(value).length) {
              String s = "data longer than mtu allows. dataLength: " +
                  hexToBytes(value).length + "> max: " + (mtu-3);
              result.error("write_characteristic_error", s, null);
              break;
          }

          // Version 33
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

              int rv = gatt.writeDescriptor(descriptor, hexToBytes(value));

              if (rv != BluetoothStatusCodes.SUCCESS) {
                  String s = "gatt.writeDescriptor() returned " + rv + " : " + bluetoothStatusString(rv);
                  result.error("write_characteristic_error", s, null);
                  return;
              }

          } else {

              // Set descriptor
              if(!descriptor.setValue(hexToBytes(value))){
                  result.error("write_descriptor_error", "descriptor.setValue() returned false", null);
                  break;
              }

              // Write descriptor
              if(!gatt.writeDescriptor(descriptor)){
                  result.error("write_descriptor_error", "gatt.writeDescriptor() returned false", null);
                  break;
              }
          }

          result.success(null);
          break;
        }

        case "setNotification":
        {
          // see: BmSetNotificationRequest
          HashMap<String, Object> data = call.arguments();
          String remoteId =             (String) data.get("remote_id");
          String serviceUuid =          (String) data.get("service_uuid");
          String secondaryServiceUuid = (String) data.get("secondary_service_uuid");
          String characteristicUuid =   (String) data.get("characteristic_uuid");
          boolean enable =             (boolean) data.get("enable");

          BluetoothGatt gatt = locateGatt(remoteId);

          BluetoothGattCharacteristic characteristic = locateCharacteristic(gatt,
              serviceUuid, secondaryServiceUuid, characteristicUuid);

          // configure local Android device to listen for characteristic changes
          if(!gatt.setCharacteristicNotification(characteristic, enable)){
              result.error("set_notification_error",
                  "gatt.setCharacteristicNotification(" + enable + ") returned false", null);
              break;
          }

          BluetoothGattDescriptor cccDescriptor = characteristic.getDescriptor(CCCD_UUID);
          if(cccDescriptor == null) {
              // Some ble devices do not actually need their CCCD updated.
              // thus setCharacteristicNotification() is all that is required to enable notifications.
              // The arduino "bluno" devices are an example.
              String chr = characteristic.getUuid().toString();
              log(LogLevel.WARNING, "[FBP-Android] CCCD descriptor for characteristic not found: " + chr);
              result.success(null);
              return;
          }

          byte[] descriptorValue = null;

          // determine value
          if(enable) {

              boolean canNotify = (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0;
              boolean canIndicate = (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) > 0;

              if(!canIndicate && !canNotify) {
                  result.error("set_notification_error",
                      "neither NOTIFY nor INDICATE properties are supported by this BLE characteristic", null);
                  break;
              }

              // If a characteristic supports both notifications and indications,
              // we'll use notifications. This matches how CoreBluetooth works on iOS.
              if(canIndicate) {descriptorValue = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;}
              if(canNotify)   {descriptorValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;}

          } else {
              descriptorValue  = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
          }

          if (!cccDescriptor.setValue(descriptorValue)) {
              result.error("set_notification_error", "cccDescriptor.setValue() returned false", null);
              break;
          }

          // update notifications on remote BLE device
          if (!gatt.writeDescriptor(cccDescriptor)) {
              result.error("set_notification_error", "gatt.writeDescriptor() returned false", null);
              break;
          }

          result.success(null);
          break;
        }

        case "mtu":
        {
          String remoteId = (String) call.arguments;

          Integer mtu = mMtu.get(remoteId);
          if(mtu == null) {
              result.error("mtu", "no instance of BluetoothGatt, have you connected first?", null);
              break;
          }

          HashMap<String, Object> response = new HashMap<String, Object>();
          response.put("remote_id", remoteId);
          response.put("mtu", mtu);

          result.success(response);
          break;
        }

        case "requestMtu":
        {
          // see: BmMtuChangeRequest
          HashMap<String, Object> data = call.arguments();
          String remoteId = (String) data.get("remote_id");
          int mtu =            (int) data.get("mtu");

          BluetoothGatt gatt = locateGatt(remoteId);

          if(gatt.requestMtu(mtu) == false) {
              result.error("request_mtu", "gatt.requestMtu() returned false", null);
              break;
          }

          result.success(null);
          break;
        }

        case "readRssi":
        {
          String remoteId = (String) call.arguments;

          BluetoothGatt gatt = locateGatt(remoteId);

          if(gatt.readRemoteRssi() == false) {
              result.error("read_rssi", "gatt.readRemoteRssi() returned false", null);
              break;
          }

          result.success(null);
          break;
        }

        case "removeBond":
        {
            String remoteId = (String) call.arguments;

            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(remoteId);

            // already removed?
            if (device.getBondState() == BluetoothDevice.BOND_NONE) {
              log(LogLevel.WARNING, "[FBP-Android] already not bonded");
              result.success(false); // no work to do
              break;
            }
            // already disconnected?
            BluetoothGatt gatt = null;
            if (gatt == null) {
              gatt = mCurrentlyConnectingDevices.get(remoteId);
              if (gatt != null) {
                  log(LogLevel.DEBUG, "disconnect: cancelling connection in progress");
              }
            }
            if (gatt == null) {
              gatt = mConnectedDevices.get(remoteId);;
            }
            if (gatt != null) {
                // disconnect
                gatt.disconnect();

                // was connecting?
                if (mCurrentlyConnectingDevices.get(remoteId) != null) {
                    // remove
                    mCurrentlyConnectingDevices.remove(remoteId);

                    // cleanup
                    gatt.close();
                }
            }

          

            try {
                Method removeBondMethod = device.getClass().getMethod("removeBond");
                boolean rv = (boolean) removeBondMethod.invoke(device);
                if(rv == false) {
                    result.error("removeBond", "device.removeBond() returned false", null);
                    break;
                }
            } catch (Exception e) {
                result.error("removeBond", "device.removeBond() returned exception", e.toString());
                break;
            }
            
            result.success(true);
            break;
        }

        case "startBeaconReceiver":
        {
            beaconReceiver = new BeaconReceiver(activityBinding.getActivity(), context, FlutterBluePlusPlugin::scaningBeacon);
            boolean isStart = beaconReceiver.startBeaconReceiver();
            result.success(isStart);
            break;
        }

        case "stopBeaconReceiver":
        {
            boolean isStop = beaconReceiver.stopBeaconReceiver();
            result.success(isStop);
            break;
        }

        default:
        {
          result.notImplemented();
          break;
        }
      }
    } catch (Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        String stackTrace = sw.toString();
        result.error("androidException", e.toString(), stackTrace);
        return;
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////
  // ██████   ███████  ██████   ███    ███  ██  ███████  ███████  ██   ██████   ███    ██
  // ██   ██  ██       ██   ██  ████  ████  ██  ██       ██       ██  ██    ██  ████   ██
  // ██████   █████    ██████   ██ ████ ██  ██  ███████  ███████  ██  ██    ██  ██ ██  ██
  // ██       ██       ██   ██  ██  ██  ██  ██       ██       ██  ██  ██    ██  ██  ██ ██
  // ██       ███████  ██   ██  ██      ██  ██  ███████  ███████  ██   ██████   ██   ████

  @Override
  public boolean onRequestPermissionsResult(int requestCode,
                                        String[] permissions,
                                          int[] grantResults)
  {
      OperationOnPermission operation = operationsOnPermission.get(requestCode);

      if (operation != null && grantResults.length > 0) {
          operation.op(grantResults[0] == PackageManager.PERMISSION_GRANTED, permissions[0]);
          return true;
      } else {
          return false;
      }
  }

  private void ensurePermissions(List<String> permissions, OperationOnPermission operation)
  {
      // only request permission we don't already have
      List<String> permissionsNeeded = new ArrayList<>();
      for (String permission : permissions) {
          if (permission != null && ContextCompat.checkSelfPermission(context, permission)
                  != PackageManager.PERMISSION_GRANTED) {
              permissionsNeeded.add(permission);
          }
      }

      // no work to do?
      if (permissionsNeeded.isEmpty()) {
          operation.op(true, null);
          return;
      }

      askPermission(permissionsNeeded, operation);
  }

  private void askPermission(List<String> permissionsNeeded, OperationOnPermission operation)
  {
      // finished asking for permission? call callback
      if (permissionsNeeded.isEmpty()) {
          operation.op(true, null);
          return;
      }

      String nextPermission = permissionsNeeded.remove(0);

      operationsOnPermission.put(lastEventId, (granted, perm) -> {
          operationsOnPermission.remove(lastEventId);
          if (!granted) {
              operation.op(false, perm);
              return;
          }
          // recursively ask for next permission
          askPermission(permissionsNeeded, operation);
      });

      ActivityCompat.requestPermissions(
              activityBinding.getActivity(),
              new String[]{nextPermission},
              lastEventId);

      lastEventId++;
  }

  //////////////////////////////////////////////
  // ██████   ██       ███████
  // ██   ██  ██       ██
  // ██████   ██       █████
  // ██   ██  ██       ██
  // ██████   ███████  ███████
  //
  // ██    ██  ████████  ██  ██       ███████
  // ██    ██     ██     ██  ██       ██
  // ██    ██     ██     ██  ██       ███████
  // ██    ██     ██     ██  ██            ██
  //  ██████      ██     ██  ███████  ███████

  private int connectionStateOfThisApp(String remoteId)
  {
      if(mConnectionState.get(remoteId) == null) {
          return BluetoothProfile.STATE_DISCONNECTED;
      } else {
          return mConnectionState.get(remoteId);
      }
  }

  private BluetoothGatt locateGatt(String remoteId) throws Exception
  {
      BluetoothGatt gatt = mConnectedDevices.get(remoteId);
      if(gatt == null) {
          throw new Exception("locateGatt failed. have you connected first?");
      }
      return gatt;
  }

  private BluetoothGattCharacteristic locateCharacteristic(BluetoothGatt gatt,
                                                                    String serviceId,
                                                                    String secondaryServiceId,
                                                                    String characteristicId)
                                                                    throws Exception
  {
      BluetoothGattService primaryService = gatt.getService(UUID.fromString(serviceId));

      if(primaryService == null) {
          throw new Exception("service not found on this device \n" +
              "service: "+ serviceId);
      }

      BluetoothGattService secondaryService = null;

      if(secondaryServiceId != null && secondaryServiceId.length() > 0) {

          for(BluetoothGattService s : primaryService.getIncludedServices()){
              if(s.getUuid().equals(UUID.fromString(secondaryServiceId))){
                  secondaryService = s;
              }
          }

          if(secondaryService == null) {
              throw new Exception("secondaryService not found on this device \n" +
                  "secondaryService: " + secondaryServiceId);
          }
      }

      BluetoothGattService service = (secondaryService != null) ?
          secondaryService :
          primaryService;

      BluetoothGattCharacteristic characteristic =
          service.getCharacteristic(UUID.fromString(characteristicId));

      if(characteristic == null) {
          throw new Exception("characteristic not found in service \n" +
              "characteristic: " + characteristicId + " \n" +
              "service: "+ serviceId);
      }

      return characteristic;
  }

  private BluetoothGattDescriptor locateDescriptor(BluetoothGattCharacteristic characteristic,
                                                                          String descriptorId) throws Exception
  {
      BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString(descriptorId));

      if(descriptor == null) {
          throw new Exception("descriptor not found on this characteristic \n" +
              "descriptor: " + descriptorId + " \n" +
              "characteristic: " + characteristic.getUuid().toString());
      }

      return descriptor;
  }

  /////////////////////////////////////////////////////////////////////////////////////
  // ██████   ██████    ██████    █████   ██████    ██████   █████   ███████  ████████
  // ██   ██  ██   ██  ██    ██  ██   ██  ██   ██  ██       ██   ██  ██          ██
  // ██████   ██████   ██    ██  ███████  ██   ██  ██       ███████  ███████     ██
  // ██   ██  ██   ██  ██    ██  ██   ██  ██   ██  ██       ██   ██       ██     ██
  // ██████   ██   ██   ██████   ██   ██  ██████    ██████  ██   ██  ███████     ██
  //
  // ██████   ███████   ██████  ███████  ██  ██    ██  ███████  ██████
  // ██   ██  ██       ██       ██       ██  ██    ██  ██       ██   ██
  // ██████   █████    ██       █████    ██  ██    ██  █████    ██████
  // ██   ██  ██       ██       ██       ██   ██  ██   ██       ██   ██
  // ██   ██  ███████   ██████  ███████  ██    ████    ███████  ██   ██
    //TODO: 1.9.0時点
    private final BroadcastReceiver mBluetoothStateReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
       final String action = intent.getAction();
            log(LogLevel.DEBUG, "[FBP-Android] mBluetoothStateReceiver");
            // no change?
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action) == false) {
                log(LogLevel.DEBUG, "[FBP-Android] mBluetoothStateReceiver ACTION_STATE_CHANGED = action == false");
                return;
            }

            final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);

            // convert to Protobuf enum
            int convertedState;
            switch (state) {
                case BluetoothAdapter.STATE_OFF:          convertedState = 6;           break;
                case BluetoothAdapter.STATE_ON:           convertedState = 4;           break;
                case BluetoothAdapter.STATE_TURNING_OFF:  convertedState = 5;           break;
                case BluetoothAdapter.STATE_TURNING_ON:   convertedState = 3;           break;
                default:                                  convertedState = 0;           break;
            }
            log(LogLevel.INFO, "[FBP-Android] mBluetoothStateReceiver state = " + convertedState);

            // disconnect all devices
            if (state == BluetoothAdapter.STATE_TURNING_OFF || 
                state == BluetoothAdapter.STATE_OFF) {
                log(LogLevel.DEBUG, "[FBP-Android] mBluetoothStateReceiver adapterTurnOff");
                disconnectAllDevices("adapterTurnOff");
            }
            // see: BmBluetoothAdapterState
            HashMap<String, Object> map = new HashMap<>();
            map.put("state", convertedState);
            invokeMethodUIThread("adapterStateChanged", map);
        //}
      }
    };


  ////////////////////////////////////////////////////////////////
  // ███████  ███████  ████████   ██████  ██   ██
  // ██       ██          ██     ██       ██   ██
  // █████    █████       ██     ██       ███████
  // ██       ██          ██     ██       ██   ██
  // ██       ███████     ██      ██████  ██   ██
  //
  // ███████  ██  ██       ████████  ███████  ██████   ███████
  // ██       ██  ██          ██     ██       ██   ██  ██
  // █████    ██  ██          ██     █████    ██████   ███████
  // ██       ██  ██          ██     ██       ██   ██       ██
  // ██       ██  ███████     ██     ███████  ██   ██  ███████

  private List<ScanFilter> fetchFilters(HashMap<String, Object> scanSettings)
  {
      List<ScanFilter> filters;

      List<String> servicesUuids = (List<String>)scanSettings.get("service_uuids");
      int macCount = (int)scanSettings.getOrDefault("mac_count", 0);
      int serviceCount = servicesUuids.size();
      int count = macCount + serviceCount;

      filters = new ArrayList<>(count);

      List<String> noMacAddresses = new ArrayList<String>();
      List<String> macAddresses = (List<String>)scanSettings.getOrDefault("mac_addresses", noMacAddresses);

      for (int i = 0; i < macCount; i++) {
          String macAddress = macAddresses.get(i);
          ScanFilter f = new ScanFilter.Builder().setDeviceAddress(macAddress).build();
          filters.add(f);
      }

      for (int i = 0; i < serviceCount; i++) {
          String uuid = servicesUuids.get(i);
          ScanFilter f = new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(uuid)).build();
          filters.add(f);
      }

      return filters;
  }

  /////////////////////////////////////////////////////////////////////////////
  // ███████   ██████   █████   ███    ██
  // ██       ██       ██   ██  ████   ██
  // ███████  ██       ███████  ██ ██  ██
  //      ██  ██       ██   ██  ██  ██ ██
  // ███████   ██████  ██   ██  ██   ████
  //
  //  ██████   █████   ██       ██       ██████    █████    ██████  ██   ██
  // ██       ██   ██  ██       ██       ██   ██  ██   ██  ██       ██  ██
  // ██       ███████  ██       ██       ██████   ███████  ██       █████
  // ██       ██   ██  ██       ██       ██   ██  ██   ██  ██       ██  ██
  //  ██████  ██   ██  ███████  ███████  ██████   ██   ██   ██████  ██   ██

  private ScanCallback scanCallback;

  @TargetApi(21)
  private ScanCallback getScanCallback()
  {
      if(scanCallback == null) {

          scanCallback = new ScanCallback()
          {
              @Override
              public void onScanResult(int callbackType, ScanResult result)
              {
                  //log(LogLevel.DEBUG, "[FBP-Android] onScanResult");

                  super.onScanResult(callbackType, result);

                  BluetoothDevice device = result.getDevice();

                  if (!allowDuplicates && device.getAddress() != null) {

                      // duplicate?
                      if (macDeviceScanned.contains(device.getAddress())) {
                          return;
                      }

                      macDeviceScanned.add(device.getAddress());
                  }

                  // see BmScanResult
                  HashMap<String, Object> rr = bmScanResult(device, result);
                  invokeMethodUIThread("ScanResult", rr);
              }

              @Override
              public void onBatchScanResults(List<ScanResult> results)
              {
                  super.onBatchScanResults(results);
              }

              @Override
              public void onScanFailed(int errorCode)
              {
                  log(LogLevel.ERROR, "[FBP-Android] onScanFailed: " + scanFailedString(errorCode));

                  super.onScanFailed(errorCode);
                  //TODO: スキャン失敗時は特に何もしない
                  /*
                  // see: BmScanFailed
                  HashMap<String, Object> failed = new HashMap<>();
                  failed.put("success", 0);
                  failed.put("error_code", errorCode);
                  failed.put("error_string", scanFailedString(errorCode));

                  // see BmScanResponse
                  HashMap<String, Object> response = new HashMap<>();
                  response.put("failed", failed);

                  invokeMethodUIThread("ScanResult", response);
                  */
              }
          };
      }
      return scanCallback;
  }

  /////////////////////////////////////////////////////////////////////////////
  //  ██████    █████   ████████  ████████
  // ██        ██   ██     ██        ██
  // ██   ███  ███████     ██        ██
  // ██    ██  ██   ██     ██        ██
  //  ██████   ██   ██     ██        ██
  //
  //  ██████   █████   ██       ██       ██████    █████    ██████  ██   ██
  // ██       ██   ██  ██       ██       ██   ██  ██   ██  ██       ██  ██
  // ██       ███████  ██       ██       ██████   ███████  ██       █████
  // ██       ██   ██  ██       ██       ██   ██  ██   ██  ██       ██  ██
  //  ██████  ██   ██  ███████  ███████  ██████   ██   ██   ██████  ██   ██

  private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
      log(LogLevel.DEBUG, "[FBP-Android] onConnectionStateChange: status: " + status + " newState: " + newState);
      
      String remoteId = gatt.getDevice().getAddress();
      mConnectionState.put(remoteId, newState);

      // see: BmConnectionStateResponse
      //statusを渡す
      HashMap<String, Object> responseStatus = new HashMap<>();
      responseStatus.put("remote_id", remoteId);
      responseStatus.put("state", bmConnectionStateEnum(newState));
      responseStatus.put("status", status);
      //デバイス接続ステータス取得用
      invokeMethodUIThread("DeviceStatus", responseStatus);

      // android never uses this callback with enums values of CONNECTING or DISCONNECTING,
      // (theyre only used for gatt.getConnectionState()), but just to be
      // future proof, explicitly ignore anything else. CoreBluetooth is the same way.
      if(newState != BluetoothProfile.STATE_CONNECTED &&
          newState != BluetoothProfile.STATE_DISCONNECTED) {
          return;
      }


       // connected?
      if(newState == BluetoothProfile.STATE_CONNECTED) {
          // add to connected devices
          mConnectedDevices.put(remoteId, gatt);

          // remove from currently connecting devices
          mCurrentlyConnectingDevices.remove(remoteId);

          // default minimum mtu
          mMtu.put(remoteId, 23);
      }

      // disconnected?
      if(newState == BluetoothProfile.STATE_DISCONNECTED) {

          // remove from connected devices
          mConnectedDevices.remove(remoteId);

          // remove from currently connecting devices
          mCurrentlyConnectingDevices.remove(remoteId);

          //TODO: 自動接続を利用する場合はgatt.closeは呼ばないほうがいい
          // it is important to close after disconnection, otherwise we will 
          // quickly run out of bluetooth resources, preventing new connections
          gatt.close();
      }

      // see: BmConnectionStateResponse
      HashMap<String, Object> response = new HashMap<>();
      response.put("remote_id", remoteId);
      response.put("state", bmConnectionStateEnum(newState));

      invokeMethodUIThread("DeviceState", response);
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
      log(LogLevel.DEBUG, "[FBP-Android] onServicesDiscovered: count: " + gatt.getServices().size() + " status: " + status);

      List<Object> services = new ArrayList<Object>();
      for(BluetoothGattService s : gatt.getServices()) {
          services.add(bmBluetoothService(gatt.getDevice(), s, gatt));
      }

      // see: BmDiscoverServicesResult
      HashMap<String, Object> response = new HashMap<>();
      response.put("remote_id", gatt.getDevice().getAddress());
      response.put("services", services);
      response.put("success", status == BluetoothGatt.GATT_SUCCESS ? 1 : 0);
      response.put("error_code", status);
      response.put("error_string", gattErrorString(status));

      invokeMethodUIThread("DiscoverServicesResult", response);
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
      // this callback is only for explicit characteristic reads
      log(LogLevel.DEBUG, "[FBP-Android] onCharacteristicRead: uuid: " + characteristic.getUuid().toString() + " status: " + status);

      ServicePair pair = getServicePair(gatt, characteristic);

      // see: BmOnCharacteristicReceived
      HashMap<String, Object> response = new HashMap<>();
      response.put("remote_id", gatt.getDevice().getAddress());
      response.put("service_uuid", pair.primary);
      
      response.put("secondary_service_uuid", pair.secondary);
      response.put("characteristic_uuid", characteristic.getUuid().toString());
      response.put("characteristic", bmBluetoothCharacteristic(gatt.getDevice(), characteristic, gatt));
      response.put("value", bytesToHex(characteristic.getValue()));
      response.put("success", status == BluetoothGatt.GATT_SUCCESS ? 1 : 0);
      response.put("error_code", status);
      response.put("error_string", gattErrorString(status));

      invokeMethodUIThread("ReadCharacteristicResponse", response);
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
      log(LogLevel.DEBUG, "[FBP-Android] onCharacteristicWrite: uuid: " + characteristic.getUuid().toString() + " status: " + status);

      ServicePair pair = getServicePair(gatt, characteristic);

      HashMap<String, Object> request = new HashMap<>();
      request.put("remote_id", gatt.getDevice().getAddress());
      request.put("service_uuid", pair.primary);
      request.put("secondary_service_uuid", pair.secondary);
      request.put("characteristic_uuid", characteristic.getUuid().toString());
      request.put("write_type", characteristic.getWriteType() == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT ? 0 : 1);
      request.put("value", bytesToHex(characteristic.getValue()));
      // see: BmOnCharacteristicWritten
      HashMap<String, Object> response = new HashMap<>();
      response.put("request", request);
      response.put("success", status == BluetoothGatt.GATT_SUCCESS ? 1 : 0);
      response.put("error_code", status);
      response.put("error_string", gattErrorString(status));

      invokeMethodUIThread("WriteCharacteristicResponse", response);
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
      // this callback is only for notifications & indications
      log(LogLevel.DEBUG, "[FBP-Android] onCharacteristicChanged: uuid: " + characteristic.getUuid().toString());

      ServicePair pair = getServicePair(gatt, characteristic);

      // see: BmOnCharacteristicReceived
      HashMap<String, Object> response = new HashMap<>();
      response.put("remote_id", gatt.getDevice().getAddress());
      response.put("service_uuid", pair.primary);
      response.put("secondary_service_uuid", pair.secondary);
      response.put("characteristic_uuid", characteristic.getUuid().toString());
      response.put("characteristic", bmBluetoothCharacteristic(gatt.getDevice(), characteristic, gatt));
      response.put("value", bytesToHex(characteristic.getValue()));
      response.put("success", 1);
      response.put("error_code", 0);
      response.put("error_string", gattErrorString(0));

      invokeMethodUIThread("OnCharacteristicChanged", response);
    }

    @Override
    public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
      log(LogLevel.DEBUG, "[FBP-Android] onDescriptorRead: uuid: " + descriptor.getUuid().toString() + " status: " + status);

      ServicePair pair = getServicePair(gatt, descriptor.getCharacteristic());

      // see: BmOnDescriptorResponse
      HashMap<String, Object> request = new HashMap<>();
      request.put("remote_id", gatt.getDevice().getAddress());
      request.put("service_uuid", pair.primary);
      request.put("secondary_service_uuid", pair.secondary);
      request.put("characteristic_uuid", descriptor.getCharacteristic().getUuid().toString());
      request.put("descriptor_uuid", descriptor.getUuid().toString());
      
      // see: BmOnCharacteristicWritten
      HashMap<String, Object> response = new HashMap<>();
      response.put("request", request);
      response.put("value", bytesToHex(descriptor.getValue()));
      response.put("success", status == BluetoothGatt.GATT_SUCCESS ? 1 : 0);
      response.put("error_code", status);
      response.put("error_string", gattErrorString(status));

      invokeMethodUIThread("ReadDescriptorResponse", response);
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
     log(LogLevel.DEBUG, "[FBP-Android] onDescriptorWrite: uuid: " + descriptor.getUuid().toString() + " status: " + status);

      ServicePair pair = getServicePair(gatt, descriptor.getCharacteristic());
      
      // see: BmOnDescriptorResponse
      HashMap<String, Object> writeDescriptorRequest = new HashMap<>();
      writeDescriptorRequest.put("remote_id", gatt.getDevice().getAddress());
      writeDescriptorRequest.put("service_uuid", pair.primary);
      writeDescriptorRequest.put("secondary_service_uuid", pair.secondary);
      writeDescriptorRequest.put("characteristic_uuid", descriptor.getCharacteristic().getUuid().toString());
      writeDescriptorRequest.put("descriptor_uuid", descriptor.getUuid().toString());
      writeDescriptorRequest.put("value", bytesToHex(descriptor.getValue()));

      HashMap<String, Object> response = new HashMap<>();
      response.put("request", writeDescriptorRequest);
      response.put("success", status == BluetoothGatt.GATT_SUCCESS ? 1 : 0);
      invokeMethodUIThread("WriteDescriptorResponse", response);

      if(descriptor.getUuid().compareTo(CCCD_UUID) == 0) {
        // SetNotificationResponse
        HashMap<String, Object> setNotificationResponse = new HashMap<>();
        setNotificationResponse.put("remote_id", gatt.getDevice().getAddress());
        setNotificationResponse.put("characteristic", bmBluetoothCharacteristic(gatt.getDevice(), descriptor.getCharacteristic(), gatt));
        setNotificationResponse.put("success", status == BluetoothGatt.GATT_SUCCESS ? 1 : 0);
        invokeMethodUIThread("SetNotificationResponse", setNotificationResponse);
      }
    }

    @Override
    public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
      log(LogLevel.DEBUG, "[onReliableWriteCompleted] status: " + status);
    }

    @Override
    public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
      log(LogLevel.DEBUG, "[FBP-Android] onReadRemoteRssi: rssi: " + rssi + " status: " + status);

      // see: BmReadRssiResult
      HashMap<String, Object> response = new HashMap<>();
      response.put("remote_id", gatt.getDevice().getAddress());
      response.put("rssi", rssi);
      response.put("success", status == BluetoothGatt.GATT_SUCCESS ? 1 : 0);
      response.put("error_code", status);
      response.put("error_string", gattErrorString(status));

      invokeMethodUIThread("ReadRssiResult", response);
    }

    @Override
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
      log(LogLevel.DEBUG, "[FBP-Android] onMtuChanged: mtu: " + mtu + " status: " + status);

      String remoteId = gatt.getDevice().getAddress();

      // remember mtu
      mMtu.put(remoteId, mtu);

      // see: BmMtuChangedResponse
      HashMap<String, Object> response = new HashMap<>();
      response.put("remote_id", remoteId);
      response.put("mtu", mtu);
      response.put("success", status == BluetoothGatt.GATT_SUCCESS ? 1 : 0);
      response.put("error_code", status);
      response.put("error_string", gattErrorString(status));

      invokeMethodUIThread("MtuSize", response);
    }
  };

  //////////////////////////////////////////////////////////////
  // ██████    █████   ██████   ███████  ███████           
  // ██   ██  ██   ██  ██   ██  ██       ██                
  // ██████   ███████  ██████   ███████  █████             
  // ██       ██   ██  ██   ██       ██  ██                
  // ██       ██   ██  ██   ██  ███████  ███████           
  //                                                     
  //                                                     
  //  █████   ██████   ██    ██  ███████  ██████   ████████ 
  // ██   ██  ██   ██  ██    ██  ██       ██   ██     ██    
  // ███████  ██   ██  ██    ██  █████    ██████      ██    
  // ██   ██  ██   ██   ██  ██   ██       ██   ██     ██    
  // ██   ██  ██████     ████    ███████  ██   ██     ██   

  /**
  * Parses packet data into {@link HashMap<String, Object>} structure.
  *
  * @param rawData The scan record data.
  * @return An AdvertisementData proto object.
  * @throws ArrayIndexOutOfBoundsException if the input is truncated.
  */
  HashMap<String, Object> parseAdvertisementData(byte[] rawData) {
      ByteBuffer data = ByteBuffer.wrap(rawData).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
      HashMap<String, Object> response = new HashMap<>();
      boolean seenLongLocalName = false;
      HashMap<String, Object> serviceData = new HashMap<>();
      HashMap<String, Object> manufacturerData = new HashMap<>();
      do {
          int length = data.get() & 0xFF;
          if (length == 0) {
              break;
          }
          if (length > data.remaining()) {
              Log.w(TAG, "parseAdvertisementData: Not enough data.");
              return response;
          }

          int type = data.get() & 0xFF;
          length--;

          switch (type) {
              case 0x08: // Short local name.
              case 0x09: { // Long local name.
                  if (seenLongLocalName) {
                      // Prefer the long name over the short.
                      data.position(data.position() + length);
                      break;
                  }
                  byte[] localName = new byte[length];
                  data.get(localName);
                  try {
                      response.put("local_name", new String(localName, "UTF-8"));
                  } catch (UnsupportedEncodingException e) {}
                  if (type == 0x09) {
                      seenLongLocalName = true;
                  }
                  break;
              }
              case 0x0A: { // Power level.
                  response.put("tx_power_level", data.get());
                  break;
              }
              case 0x16: // Service Data with 16 bit UUID.
              case 0x20: // Service Data with 32 bit UUID.
              case 0x21: { // Service Data with 128 bit UUID.
                  UUID svcUuid;
                  int remainingDataLength = 0;
                  if (type == 0x16 || type == 0x20) {
                      long svcUuidInteger;
                      if (type == 0x16) {
                          svcUuidInteger = data.getShort() & 0xFFFF;
                          remainingDataLength = length - 2;
                      } else {
                          svcUuidInteger = data.getInt() & 0xFFFFFFFF;
                          remainingDataLength = length - 4;
                      }
                      svcUuid = UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", svcUuidInteger));
                  } else {
                      long msb = data.getLong();
                      long lsb = data.getLong();
                      svcUuid = new UUID(msb, lsb);
                      remainingDataLength = length - 16;
                  }
                  byte[] remainingData = new byte[remainingDataLength];
                  data.get(remainingData);

                  serviceData.put(svcUuid.toString(), remainingData);
                  response.put("service_data", serviceData);
                  break;
              }
              case 0xFF: {// Manufacturer specific data.
                  if(length < 2) {
                      Log.w(TAG, "parseAdvertisementData: Not enough data for Manufacturer specific data.");
                      break;
                  }
                  int manufacturerId = data.getShort();
                  if((length - 2) > 0) {
                      byte[] msd = new byte[length - 2];
                      data.get(msd);
                      manufacturerData.put(Integer.toString(manufacturerId), msd);
                      response.put("manufacturer_data", manufacturerId);
                  }
                  break;
              }
              default: {
                  data.position(data.position() + length);
                  break;
              }
          }
      } while (true);
      return response;
  } 

  //////////////////////////////////////////////////////////////////////
  // ███    ███  ███████   ██████      
  // ████  ████  ██       ██           
  // ██ ████ ██  ███████  ██   ███     
  // ██  ██  ██       ██  ██    ██     
  // ██      ██  ███████   ██████ 
  //     
  // ██   ██  ███████  ██       ██████   ███████  ██████   ███████ 
  // ██   ██  ██       ██       ██   ██  ██       ██   ██  ██      
  // ███████  █████    ██       ██████   █████    ██████   ███████ 
  // ██   ██  ██       ██       ██       ██       ██   ██       ██ 
  // ██   ██  ███████  ███████  ██       ███████  ██   ██  ███████ 


  HashMap<String, Object> bmAdvertisementData(BluetoothDevice device, byte[] advertisementData, int rssi) {
      HashMap<String, Object> map = new HashMap<>();
      map.put("device", bmBluetoothDevice(device));
      if(advertisementData != null && advertisementData.length > 0) {
          map.put("advertisement_data", parseAdvertisementData(advertisementData));
      }
      map.put("rssi", rssi);
      return map;
  }

  @TargetApi(21)
  HashMap<String, Object> bmScanResult(BluetoothDevice device, ScanResult result) {

      ScanRecord scanRecord = result.getScanRecord();

      HashMap<String, Object> advertisementData = new HashMap<>();
      
      // connectable
      if(Build.VERSION.SDK_INT >= 26) {
          advertisementData.put("connectable", result.isConnectable());
      } else if(scanRecord != null) {
          int flags = scanRecord.getAdvertiseFlags();
          advertisementData.put("connectable", (flags & 0x2) > 0);
      }

      if(scanRecord != null) {

          String localName = scanRecord.getDeviceName();

          int txPower = scanRecord.getTxPowerLevel();

          // Manufacturer Specific Data
          SparseArray<byte[]> msd = scanRecord.getManufacturerSpecificData();
          HashMap<Integer, String> msdMap = new HashMap<Integer, String>();
          if(msd != null) {
              for (int i = 0; i < msd.size(); i++) {
                  int key = msd.keyAt(i);
                  byte[] value = msd.valueAt(i);
                  msdMap.put(key, bytesToHex(value));
              }
          }

          // Service Data
          Map<ParcelUuid, byte[]> serviceData = scanRecord.getServiceData();
          HashMap<String, Object> serviceDataMap = new HashMap<>();
          if(serviceData != null) {
              for (Map.Entry<ParcelUuid, byte[]> entry : serviceData.entrySet()) {
                  ParcelUuid key = entry.getKey();
                  byte[] value = entry.getValue();
                  serviceDataMap.put(key.getUuid().toString(), bytesToHex(value));
              }
          }

          // Service UUIDs
          List<ParcelUuid> serviceUuids = scanRecord.getServiceUuids();
          List<String> serviceUuidList = new ArrayList<String>();
          if(serviceUuids != null) {
              for (ParcelUuid s : serviceUuids) {
                  serviceUuidList.add(s.getUuid().toString());
              }
          }

          // add to map
          if(localName != null) {
              advertisementData.put("local_name", localName);
          }
          if(txPower != Integer.MIN_VALUE) {
              advertisementData.put("tx_power_level", txPower);
          }
          if(msd != null) {
              advertisementData.put("manufacturer_data", msdMap);
          }
          if(serviceData != null) {
              advertisementData.put("service_data", serviceDataMap);
          }
          if(serviceUuids != null) {
              advertisementData.put("service_uuids", serviceUuidList);
          }
      }

      // connection state
      int cs = mBluetoothManager.getConnectionState(device, BluetoothProfile.GATT);

      HashMap<String, Object> map = new HashMap<>();
      map.put("device", bmBluetoothDevice(device));
      map.put("rssi", result.getRssi());
      map.put("advertisement_data", advertisementData);
      map.put("connection_state", bmConnectionStateEnum(cs));
      return map;
  }

  HashMap<String, Object> bmBluetoothDevice(BluetoothDevice device) {
      HashMap<String, Object> map = new HashMap<>();
      map.put("remote_id", device.getAddress());
      if(device.getName() != null) {
          map.put("name", device.getName());
      }
      map.put("type", device.getType());
      return map;
  }

  HashMap<String, Object> bmBluetoothService(BluetoothDevice device, BluetoothGattService service, BluetoothGatt gatt) {

      List<Object> characteristics = new ArrayList<Object>();
      for(BluetoothGattCharacteristic c : service.getCharacteristics()) {
          characteristics.add(bmBluetoothCharacteristic(device, c, gatt));
      }

      List<Object> includedServices = new ArrayList<Object>();
      for(BluetoothGattService s : service.getIncludedServices()) {
          includedServices.add(bmBluetoothService(device, s, gatt));
      }

      HashMap<String, Object> map = new HashMap<>();
      map.put("remote_id", device.getAddress());
      map.put("uuid", service.getUuid().toString());
      map.put("is_primary", service.getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY);
      map.put("characteristics", characteristics);
      map.put("included_services", includedServices);
      return map;
  }

  HashMap<String, Object> bmBluetoothCharacteristic(BluetoothDevice device, BluetoothGattCharacteristic characteristic, BluetoothGatt gatt) {

      ServicePair pair = getServicePair(gatt, characteristic);

      List<Object> descriptors = new ArrayList<Object>();
      for(BluetoothGattDescriptor d : characteristic.getDescriptors()) {
          descriptors.add(bmBluetoothDescriptor(device, d));
      }

      HashMap<String, Object> map = new HashMap<>();
      map.put("remote_id", device.getAddress());
      map.put("service_uuid", pair.primary);
      map.put("secondary_service_uuid", pair.secondary);
      map.put("uuid", characteristic.getUuid().toString());
      map.put("descriptors", descriptors);
      map.put("properties", bmCharacteristicProperties(characteristic.getProperties()));
      map.put("value", bytesToHex(characteristic.getValue()));
      return map;
  }

  HashMap<String, Object> bmBluetoothDescriptor(BluetoothDevice device, BluetoothGattDescriptor descriptor) {
      HashMap<String, Object> map = new HashMap<>();
      map.put("remote_id", device.getAddress());
      map.put("uuid", descriptor.getUuid().toString());
      map.put("characteristic_uuid", descriptor.getCharacteristic().getUuid().toString());
      map.put("service_uuid", descriptor.getCharacteristic().getService().getUuid().toString());
      map.put("value", bytesToHex(descriptor.getValue()));
      return map;
  }

  HashMap<String, Object> bmCharacteristicProperties(int properties) {
      HashMap<String, Object> props = new HashMap<>();
      props.put("broadcast",                      (properties & 1)   != 0 ? 1 : 0);
      props.put("read",                           (properties & 2)   != 0 ? 1 : 0);
      props.put("write_without_response",         (properties & 4)   != 0 ? 1 : 0);
      props.put("write",                          (properties & 8)   != 0 ? 1 : 0);
      props.put("notify",                         (properties & 16)  != 0 ? 1 : 0);
      props.put("indicate",                       (properties & 32)  != 0 ? 1 : 0);
      props.put("authenticated_signed_writes",    (properties & 64)  != 0 ? 1 : 0);
      props.put("extended_properties",            (properties & 128) != 0 ? 1 : 0);
      props.put("notify_encryption_required",     (properties & 256) != 0 ? 1 : 0);
      props.put("indicate_encryption_required",   (properties & 512) != 0 ? 1 : 0);
      return props;
  }

  static int bmConnectionStateEnum(int cs) {
      switch (cs) {
          case BluetoothProfile.STATE_DISCONNECTED:  return 0;
          case BluetoothProfile.STATE_CONNECTING:    return 1;
          case BluetoothProfile.STATE_CONNECTED:     return 2;
          case BluetoothProfile.STATE_DISCONNECTING: return 3;
          default: return 0;
      }
  }

  public static class ServicePair {
      public String primary;
      public String secondary;
  }

  static ServicePair getServicePair(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {

      ServicePair result = new ServicePair();

      BluetoothGattService service = characteristic.getService();

      // is this a primary service?
      if(service.getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY) {
          result.primary = service.getUuid().toString();
          return result;
      } 

      // Otherwise, iterate all services until we find the primary service
      for(BluetoothGattService primary : gatt.getServices()) {
          for(BluetoothGattService secondary : primary.getIncludedServices()) {
              if(secondary.getUuid().equals(service.getUuid())) {
                  result.primary = primary.getUuid().toString();
                  result.secondary = secondary.getUuid().toString();
                  return result;
              }
          }
      }

      return result;
  }

  //////////////////////////////////////////
  // ██    ██ ████████  ██  ██       ███████
  // ██    ██    ██     ██  ██       ██
  // ██    ██    ██     ██  ██       ███████
  // ██    ██    ██     ██  ██            ██
  //  ██████     ██     ██  ███████  ███████

  private void log(LogLevel level, String message) {
    if(level.ordinal() <= logLevel.ordinal()) {
      Log.d(TAG, message);
      HashMap<String, Object> response = new HashMap<>();
      response.put("message", message);
      //TODO: ログはFlutter側に送信する。
      invokeMethodUIThread("Logger", response);
    }
  }

   private void disconnectAllDevices(String func)
    {
        log(LogLevel.DEBUG, "disconnectAllDevices("+func+")");

        // request disconnections
        for (BluetoothGatt gatt : mConnectedDevices.values()) {

            if (func == "adapterTurnOff") {

                // Note: 
                //  - calling `disconnect` and `close` after the adapter
                //    is turned off is not necessary. It is implied.
                //    Calling them leads to a `DeadObjectException`.
                //  - But, we must make sure the disconnect callback is called.
                //    It's surprising but android does not invoke this callback itself.
                mGattCallback.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_DISCONNECTED);

            } else {

                String remoteId = gatt.getDevice().getAddress();
                
                // disconnect
                log(LogLevel.DEBUG, "calling disconnect: " + remoteId);
                gatt.disconnect();

                // it is important to close after disconnection, otherwise we will 
                // quickly run out of bluetooth resources, preventing new connections
                log(LogLevel.DEBUG, "calling close: " + remoteId);
                gatt.close();
            }
        }

        mConnectedDevices.clear();
        mCurrentlyConnectingDevices.clear();
        mMtu.clear();
    }

  private void invokeMethodUIThread(final String method, HashMap<String, Object> data)
  {
    new Handler(Looper.getMainLooper()).post(() -> {
      synchronized (tearDownLock) {
        //Could already be teared down at this moment
        if (methodChannel != null) {
          methodChannel.invokeMethod(method, data);
        } else {
          Log.w(TAG, "invokeMethodUIThread: tried to call method on closed channel: " + method);
        }
      }
    });
  }

  private static byte[] hexToBytes(String s) {
      if (s == null) {
          return new byte[0];
      }
      int len = s.length();
      byte[] data = new byte[len / 2];

      for (int i = 0; i < len; i += 2) {
          data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                              + Character.digit(s.charAt(i+1), 16));
      }

      return data;
  }

  private static String bytesToHex(byte[] bytes) {
      if (bytes == null) {
          return "";
      }
      StringBuilder sb = new StringBuilder();
      for (byte b : bytes) {
          sb.append(String.format("%02x", b));
      }
      return sb.toString();
  }

  private static String gattErrorString(int value) {
      switch(value) {
          case BluetoothGatt.GATT_SUCCESS                     : return "GATT_SUCCESS";
          case BluetoothGatt.GATT_CONNECTION_CONGESTED        : return "GATT_CONNECTION_CONGESTED";
          case BluetoothGatt.GATT_FAILURE                     : return "GATT_FAILURE";
          case BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION : return "GATT_INSUFFICIENT_AUTHENTICATION";
          case BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION  : return "GATT_INSUFFICIENT_AUTHORIZATION";
          case BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION     : return "GATT_INSUFFICIENT_ENCRYPTION";
          case BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH    : return "GATT_INVALID_ATTRIBUTE_LENGTH";
          case BluetoothGatt.GATT_INVALID_OFFSET              : return "GATT_INVALID_OFFSET";
          case BluetoothGatt.GATT_READ_NOT_PERMITTED          : return "GATT_READ_NOT_PERMITTED";
          case BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED       : return "GATT_REQUEST_NOT_SUPPORTED";
          case BluetoothGatt.GATT_WRITE_NOT_PERMITTED         : return "GATT_WRITE_NOT_PERMITTED";
          default: return "UNKNOWN_GATT_ERROR (" + value + ")";
      }
  }

  private static String bluetoothStatusString(int value) {
      switch(value) {
          case BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED                : return "ERROR_BLUETOOTH_NOT_ALLOWED";
          case BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED                : return "ERROR_BLUETOOTH_NOT_ENABLED";
          case BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED                    : return "ERROR_DEVICE_NOT_BONDED";
          case BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED               : return "ERROR_GATT_WRITE_NOT_ALLOWED";
          case BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY              : return "ERROR_GATT_WRITE_REQUEST_BUSY";
          case BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION : return "ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION";
          case BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND            : return "ERROR_PROFILE_SERVICE_NOT_BOUND";
          case BluetoothStatusCodes.ERROR_UNKNOWN                              : return "ERROR_UNKNOWN";
          //case BluetoothStatusCodes.FEATURE_NOT_CONFIGURED                     : return "FEATURE_NOT_CONFIGURED";
          case BluetoothStatusCodes.FEATURE_NOT_SUPPORTED                      : return "FEATURE_NOT_SUPPORTED";
          case BluetoothStatusCodes.FEATURE_SUPPORTED                          : return "FEATURE_SUPPORTED";
          case BluetoothStatusCodes.SUCCESS                                    : return "SUCCESS";
          default: return "UNKNOWN_BLE_ERROR (" + value + ")";
      }
  }

  private static String scanFailedString(int value) {
      switch(value) {
          case ScanCallback.SCAN_FAILED_ALREADY_STARTED                : return "SCAN_FAILED_ALREADY_STARTED";
          case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED: return "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED";
          case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED            : return "SCAN_FAILED_FEATURE_UNSUPPORTED";
          case ScanCallback.SCAN_FAILED_INTERNAL_ERROR                 : return "SCAN_FAILED_INTERNAL_ERROR";
          case ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES      : return "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES";
          case ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY        : return "SCAN_FAILED_SCANNING_TOO_FREQUENTLY";
          default: return "UNKNOWN_SCAN_ERROR (" + value + ")";
      }
  }

  private static Void scaningBeacon(List<Map<String, String>> result) {
    for(Map<String, String> beacon: result){
      System.out.println("-----------------------------------");
      System.out.println( "calling uuid    : " + beacon.get("uuid"));
      System.out.println( "calling major   : " + beacon.get("major"));
      System.out.println( "calling minor   : " + beacon.get("minor"));
      System.out.println( "calling Distance: " + beacon.get("Distance"));
      System.out.println( "calling RSSI    : " + beacon.get("RSSI"));
      System.out.println( "calling TxPower : " + beacon.get("TxPower"));
      System.out.println( "-----------------------------------");
    }
    return null;
  }

  enum LogLevel
  {
    EMERGENCY, ALERT, CRITICAL, ERROR, WARNING, NOTICE, INFO, DEBUG
  }

  // BluetoothDeviceCache contains any other cached information not stored in Android Bluetooth API
  // but still needed Dart side.
  static class BluetoothDeviceCache {
    final BluetoothGatt gatt;
    int mtu;

    BluetoothDeviceCache(BluetoothGatt gatt) {
      this.gatt = gatt;
      mtu = 20;
    }
  }
}
