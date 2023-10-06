// Copyright 2017, Paul DeMarco.
// All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

part of flutter_blue_plus;

class BluetoothCharacteristic {
  final Guid uuid;
  final DeviceIdentifier deviceId;
  final Guid serviceUuid;
  final Guid? secondaryServiceUuid;
  final CharacteristicProperties properties;
  final List<BluetoothDescriptor> descriptors;
  bool get isNotifying {
    try {
      var cccd =
          descriptors.singleWhere((d) => d.uuid == BluetoothDescriptor.cccd);
      return ((cccd.lastValue[0] & 0x01) > 0 || (cccd.lastValue[0] & 0x02) > 0);
    } catch (e) {
      return false;
    }
  }

  final BehaviorSubject<List<int>> _value;
  Stream<List<int>> get value => Rx.merge([
        _value.stream,
        onValueChangedStream,
      ]);

  List<int> get lastValue => _value.value;

  BluetoothCharacteristic.fromProto(BmBluetoothCharacteristic p)
      : uuid = Guid(p.uuid),
        deviceId = DeviceIdentifier(p.remoteId),
        serviceUuid = Guid(p.serviceUuid),
        secondaryServiceUuid = (p.secondaryServiceUuid != null)
            ? Guid(p.secondaryServiceUuid!)
            : null,
        descriptors =
            p.descriptors.map((d) => BluetoothDescriptor.fromProto(d)).toList(),
        properties = CharacteristicProperties.fromProto(p.properties),
        _value = BehaviorSubject.seeded(p.value);

  Stream<BluetoothCharacteristic> get _onCharacteristicChangedStream =>
      FlutterBluePlus.instance._methodStream
          .where((m) => m.method == "OnCharacteristicChanged")
          .map((m) => m.arguments)
          .map((buffer) => BmOnCharacteristicChanged.fromJson(buffer))
          .where((p) => p.remoteId == deviceId.toString())
          .map((p) => BluetoothCharacteristic.fromProto(p.characteristic))
          .where((c) => c.uuid == uuid)
          .map((c) {
        // Update the characteristic with the new values
        _updateDescriptors(c.descriptors);
        return c;
      });

  Stream<List<int>> get onValueChangedStream =>
      _onCharacteristicChangedStream.map((c) => c.lastValue);

  void _updateDescriptors(List<BluetoothDescriptor> newDescriptors) {
    for (var d in descriptors) {
      for (var newD in newDescriptors) {
        if (d.uuid == newD.uuid) {
          d._value.add(newD.lastValue);
        }
      }
    }
  }

  /// Retrieves the value of the characteristic
  Future<List<int>> read() async {
    var request = BmReadCharacteristicRequest(
      remoteId: deviceId.toString(),
      characteristicUuid: uuid.toString(),
      serviceUuid: serviceUuid.toString(),
      secondaryServiceUuid: null,
    );
    FlutterBluePlus.instance._log(LogLevel.info,
        'remoteId: ${deviceId.toString()} characteristicUuid: ${uuid.toString()} serviceUuid: ${serviceUuid.toString()}');

    await FlutterBluePlus.instance._channel
        .invokeMethod('readCharacteristic', request.toJson());

    return FlutterBluePlus.instance._methodStream
        .where((m) => m.method == "ReadCharacteristicResponse")
        .map((m) => m.arguments)
        .map((buffer) => BmReadCharacteristicResponse.fromJson(buffer))
        .where((p) =>
            (p.remoteId == request.remoteId) &&
            (p.characteristic.uuid == request.characteristicUuid) &&
            (p.characteristic.serviceUuid == request.serviceUuid))
        .map((p) => p.characteristic.value)
        .first
        .then((d) {
      _value.add(d);
      return d;
    });
  }

  /// Writes the value of a characteristic.
  /// [CharacteristicWriteType.withoutResponse]: the write is not
  /// guaranteed and will return immediately with success.
  /// [CharacteristicWriteType.withResponse]: the method will return after the
  /// write operation has either passed or failed.
  Future<Null> write(List<int> value, {bool withoutResponse = false}) async {
    final writeType = withoutResponse
        ? BmWriteType.withoutResponse
        : BmWriteType.withResponse;

    var request = BmWriteCharacteristicRequest(
      remoteId: deviceId.toString(),
      characteristicUuid: uuid.toString(),
      serviceUuid: serviceUuid.toString(),
      secondaryServiceUuid: null,
      writeType: writeType,
      value: value,
    );

    var result = await FlutterBluePlus.instance._channel
        .invokeMethod('writeCharacteristic', request.toJson());

    if (writeType == BmWriteType.withoutResponse) {
      return result;
    }

    return FlutterBluePlus.instance._methodStream
        .where((m) => m.method == "WriteCharacteristicResponse")
        .map((m) => m.arguments)
        .map((buffer) => BmWriteCharacteristicResponse.fromJson(buffer))
        .where((p) =>
            (p.request.remoteId == request.remoteId) &&
            (p.request.characteristicUuid == request.characteristicUuid) &&
            (p.request.serviceUuid == request.serviceUuid))
        .first
        .then((w) => w.success)
        .then((success) => (!success)
            ? throw Exception('Failed to write the characteristic')
            : null)
        .then((_) => null);
  }

  /// Sets notifications or indications for the value of a specified characteristic
  Future<bool> setNotifyValue(bool notify) async {
    var request = BmSetNotificationRequest(
      remoteId: deviceId.toString(),
      serviceUuid: serviceUuid.toString(),
      characteristicUuid: uuid.toString(),
      secondaryServiceUuid: null,
      enable: notify,
    );

    Stream<BmSetNotificationResponse> responseStream = FlutterBluePlus
        .instance._methodStream
        .where((m) => m.method == "SetNotificationResponse")
        .map((m) => m.arguments)
        .map((buffer) => BmSetNotificationResponse.fromJson(buffer))
        .where((p) =>
            (p.remoteId == request.remoteId) &&
            (p.characteristic.uuid == request.characteristicUuid) &&
            (p.characteristic.serviceUuid == request.serviceUuid));

    Future<BmSetNotificationResponse> futureResponse = responseStream.first;

    await FlutterBluePlus.instance._channel
        .invokeMethod('setNotification', request.toJson());

    BmSetNotificationResponse response = await futureResponse;
    /*if (!response.success) {
      throw Exception('setNotifyValue failed');
    }*/
    BluetoothCharacteristic c =
        BluetoothCharacteristic.fromProto(response.characteristic);
    _updateDescriptors(c.descriptors);
    return c.isNotifying == notify;
  }

  //TODO: androidのFlutterBluePlugin.javaにあるonDescriptorWrite内のsetNotificationResponseを実行する際に以下のコードを追記している。
  //TODO: q.setSuccess(status == BluetoothGatt.GATT_SUCCESS);
  /// Sets notifications or indications for the value of a specified characteristic
  /// notificationsかindicationsを設定する。(成功判定にステータスを含む)
  Future<bool> setNotifyValue2(bool notify) async {
    var request = BmSetNotificationRequest(
      remoteId: deviceId.toString(),
      serviceUuid: serviceUuid.toString(),
      characteristicUuid: uuid.toString(),
      secondaryServiceUuid: null,
      enable: notify,
    );

    Stream<BmSetNotificationResponse> responseStream = FlutterBluePlus
        .instance._methodStream
        .where((m) => m.method == "SetNotificationResponse")
        .map((m) => m.arguments)
        .map((buffer) => BmSetNotificationResponse.fromJson(buffer))
        .where((p) =>
            (p.remoteId == request.remoteId) &&
            (p.characteristic.uuid == request.characteristicUuid) &&
            (p.characteristic.serviceUuid == request.serviceUuid));

    Future<BmSetNotificationResponse> futureResponse = responseStream.first;

    await FlutterBluePlus.instance._channel
        .invokeMethod('setNotification', request.toJson());

    BmSetNotificationResponse response = await futureResponse;

    //success(StatusがGATT_SUCCESS)以外は失敗とする。
    if (!response.success) {
      return false;
    }
    //characteristicを更新
    BluetoothCharacteristic c =
        BluetoothCharacteristic.fromProto(response.characteristic);
    _updateDescriptors(c.descriptors);
    //isNotifyingが引数と一致しているなら変更成功しているのでtrue.
    return (c.isNotifying == notify);
  }

  @override
  String toString() {
    return 'BluetoothCharacteristic{uuid: $uuid, deviceId: $deviceId, serviceUuid: $serviceUuid, secondaryServiceUuid: $secondaryServiceUuid, properties: $properties, descriptors: $descriptors, value: ${_value.value}';
  }
}

@immutable
class CharacteristicProperties {
  final bool broadcast;
  final bool read;
  final bool writeWithoutResponse;
  final bool write;
  final bool notify;
  final bool indicate;
  final bool authenticatedSignedWrites;
  final bool extendedProperties;
  final bool notifyEncryptionRequired;
  final bool indicateEncryptionRequired;

  const CharacteristicProperties(
      {this.broadcast = false,
      this.read = false,
      this.writeWithoutResponse = false,
      this.write = false,
      this.notify = false,
      this.indicate = false,
      this.authenticatedSignedWrites = false,
      this.extendedProperties = false,
      this.notifyEncryptionRequired = false,
      this.indicateEncryptionRequired = false});

  CharacteristicProperties.fromProto(BmCharacteristicProperties p)
      : broadcast = p.broadcast,
        read = p.read,
        writeWithoutResponse = p.writeWithoutResponse,
        write = p.write,
        notify = p.notify,
        indicate = p.indicate,
        authenticatedSignedWrites = p.authenticatedSignedWrites,
        extendedProperties = p.extendedProperties,
        notifyEncryptionRequired = p.notifyEncryptionRequired,
        indicateEncryptionRequired = p.indicateEncryptionRequired;

  @override
  String toString() {
    return 'CharacteristicProperties{broadcast: $broadcast, read: $read, writeWithoutResponse: $writeWithoutResponse, write: $write, notify: $notify, indicate: $indicate, authenticatedSignedWrites: $authenticatedSignedWrites, extendedProperties: $extendedProperties, notifyEncryptionRequired: $notifyEncryptionRequired, indicateEncryptionRequired: $indicateEncryptionRequired}';
  }
}
