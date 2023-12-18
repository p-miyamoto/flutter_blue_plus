// Copyright 2017, Paul DeMarco.
// All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

part of flutter_blue_plus;

class BluetoothDevice {
  final DeviceIdentifier id;
  final String name;
  final BluetoothDeviceType type;

  BluetoothDevice.fromProto(BmBluetoothDevice p)
      : id = DeviceIdentifier(p.remoteId),
        name = p.name ?? "",
        type = bmToBluetoothDeviceType(p.type);

  /// Use on Android when the MAC address is known.
  ///
  /// This constructor enables the Android to connect to a specific device
  /// as soon as it becomes available on the bluetooth "network".
  BluetoothDevice.fromId(String id, {String? name, BluetoothDeviceType? type})
      : id = DeviceIdentifier(id),
        name = name ?? "Unknown name",
        type = type ?? BluetoothDeviceType.unknown;

  final BehaviorSubject<bool> _isDiscoveringServices =
      BehaviorSubject.seeded(false);
  Stream<bool> get isDiscoveringServices => _isDiscoveringServices.stream;

  /// Establishes a connection to the Bluetooth Device.
  Future<void> connect({
    Duration? timeout,
    bool autoConnect = true,
  }) async {
    final completer = Completer<void>();
    var request = BmConnectRequest(
      remoteId: id.toString(),
      androidAutoConnect: autoConnect,
    );

    Timer? timer;
    if (timeout != null) {
      timer = Timer(timeout, () {
        disconnect();
        completer.completeError(
            TimeoutException('Failed to connect in time.', timeout));
      });
    }

    await FlutterBluePlus.instance._channel
        .invokeMethod('connect', request.toJson());

    await state.firstWhere((s) => s == BluetoothDeviceState.connected);

    timer?.cancel();

    completer.complete();

    return completer.future;
  }

  /// Cancels connection to the Bluetooth Device
  Future disconnect() => FlutterBluePlus.instance._channel
      .invokeMethod('disconnect', id.toString());

  final BehaviorSubject<List<BluetoothService>> _services =
      BehaviorSubject.seeded([]);

  /// Discovers services offered by the remote device as well as their characteristics and descriptors
  Future<List<BluetoothService>> discoverServices() async {
    final s = await state.first;
    if (s != BluetoothDeviceState.connected) {
      return Future.error(Exception(
          'Cannot discoverServices while device is not connected. State == $s'));
    }
    var response = FlutterBluePlus.instance._methodStream
        .where((m) => m.method == "DiscoverServicesResult")
        .map((m) => m.arguments)
        .map((buffer) => BmDiscoverServicesResult.fromJson(buffer))
        .where((p) => p.remoteId == id.toString())
        .map((p) => p.services)
        .map((s) => s.map((p) => BluetoothService.fromProto(p)).toList())
        .first
        .then((list) {
      _services.add(list);
      _isDiscoveringServices.add(false);
      return list;
    });

    await FlutterBluePlus.instance._channel
        .invokeMethod('discoverServices', id.toString());

    _isDiscoveringServices.add(true);

    return response;
  }

  /// Returns a list of Bluetooth GATT services offered by the remote device
  /// This function requires that discoverServices has been completed for this device
  ///TODO: Androidでは使用不可！
  Stream<List<BluetoothService>> get services async* {
    yield await FlutterBluePlus.instance._channel
        .invokeMethod('services', id.toString())
        .then((buffer) => BmDiscoverServicesResult.fromJson(buffer).services)
        .then((i) => i.map((s) => BluetoothService.fromProto(s)).toList());
    yield* _services.stream;
  }

  /// The current connection state of the device
  Stream<BluetoothDeviceState> get state async* {
    yield await FlutterBluePlus.instance._channel
        .invokeMethod('deviceState', id.toString())
        .then((buffer) => BmConnectionStateResponse.fromJson(buffer))
        .then((p) => bmToBluetoothDeviceState(p.state));

    yield* FlutterBluePlus.instance._methodStream
        .where((m) => m.method == "DeviceState")
        .map((m) => m.arguments)
        .map((buffer) => BmConnectionStateResponse.fromJson(buffer))
        .where((p) => p.remoteId == id.toString())
        .map((p) => bmToBluetoothDeviceState(p.state));
  }

  /////デバイス接続ステータス取得用
  /// The current connection state and status of the device.
  Stream<DeviceConnectionStatus> get status async* {
    yield* FlutterBluePlus.instance._methodStream
        .where((m) => m.method == "DeviceStatus")
        .map((m) => m.arguments)
        .map((buffer) => BmConnectionStatusResponse.fromJson(buffer))
        .where((p) => p.remoteId == id.toString())
        .map((p) => DeviceConnectionStatus(
            state: bmToBluetoothDeviceState(p.state), status: p.status));
  }

  /// Refresh ble services & characteristics (Android Only)
  Future<void> clearGattCache() async {
    // check android
    if (Platform.isAndroid == false) {
      return Future.error(Exception('clearGattCache is android only.'));
    }
    // check connected
    final s = await state.first;
    if (s != BluetoothDeviceState.connected) {
      return Future.error(Exception(
          'Cannot clearGattCache while device is not connected. State == $s'));
    }
    final remoteId = id.toString();
    // invoke
    await FlutterBluePlus.instance._channel
        .invokeMethod('clearGattCache', remoteId);
  }

  /// The MTU size in bytes
  Stream<int> get mtu async* {
    yield await FlutterBluePlus.instance._channel
        .invokeMethod('mtu', id.toString())
        .then((buffer) => BmMtuSizeResponse.fromJson(buffer))
        .then((p) => p.mtu);

    yield* FlutterBluePlus.instance._methodStream
        .where((m) => m.method == "MtuSize")
        .map((m) => m.arguments)
        .map((buffer) => BmMtuSizeResponse.fromJson(buffer))
        .where((p) => p.remoteId == id.toString())
        .map((p) => p.mtu);
  }

  /// Request to change the MTU Size
  /// Throws error if request did not complete successfully
  /// Request to change the MTU Size and returns the response back
  /// Throws error if request did not complete successfully
  Future<int> requestMtu(int desiredMtu) async {
    var request = BmMtuSizeRequest(
      remoteId: id.toString(),
      mtu: desiredMtu,
    );

    var response = FlutterBluePlus.instance._methodStream
        .where((m) => m.method == "MtuSize")
        .map((m) => m.arguments)
        .map((buffer) => BmMtuSizeResponse.fromJson(buffer))
        .where((p) => p.remoteId == id.toString())
        .map((p) => p.mtu)
        .first;

    await FlutterBluePlus.instance._channel
        .invokeMethod('requestMtu', request.toJson());

    return response;
  }

  /// Indicates whether the Bluetooth Device can send a write without response
  Future<bool> get canSendWriteWithoutResponse =>
      Future.error(UnimplementedError());

  /// Read the RSSI for a connected remote device
  Future<int> readRssi() async {
    final remoteId = id.toString();
    await FlutterBluePlus.instance._channel.invokeMethod('readRssi', remoteId);

    return FlutterBluePlus.instance._methodStream
        .where((m) => m.method == "ReadRssiResult")
        .map((m) => m.arguments)
        .map((buffer) => BmReadRssiResult.fromJson(buffer))
        .where((p) => (p.remoteId == remoteId))
        .first
        .then((c) {
      return (c.rssi);
    });
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is BluetoothDevice &&
          runtimeType == other.runtimeType &&
          id == other.id;

  @override
  int get hashCode => id.hashCode;

  @override
  String toString() {
    return 'BluetoothDevice{id: $id, name: $name, type: $type, isDiscoveringServices: ${_isDiscoveringServices.value}, _services: ${_services.value}';
  }
}

enum BluetoothDeviceType { unknown, classic, le, dual }

BluetoothDeviceType bmToBluetoothDeviceType(BmBluetoothSpecEnum value) {
  switch (value) {
    case BmBluetoothSpecEnum.unknown:
      return BluetoothDeviceType.unknown;
    case BmBluetoothSpecEnum.classic:
      return BluetoothDeviceType.classic;
    case BmBluetoothSpecEnum.le:
      return BluetoothDeviceType.le;
    case BmBluetoothSpecEnum.dual:
      return BluetoothDeviceType.dual;
  }
}

enum BluetoothDeviceState { disconnected, connecting, connected, disconnecting }

BluetoothDeviceState bmToBluetoothDeviceState(BmConnectionStateEnum value) {
  switch (value) {
    case BmConnectionStateEnum.disconnected:
      return BluetoothDeviceState.disconnected;
    case BmConnectionStateEnum.connecting:
      return BluetoothDeviceState.connecting;
    case BmConnectionStateEnum.connected:
      return BluetoothDeviceState.connected;
    case BmConnectionStateEnum.disconnecting:
      return BluetoothDeviceState.disconnecting;
  }
}

class DeviceConnectionStatus {
  BluetoothDeviceState state;
  int status;
  DeviceConnectionStatus({required this.state, required this.status});
}
