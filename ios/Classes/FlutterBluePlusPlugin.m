// Copyright 2017, Paul DeMarco.
// All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

#import "FlutterBluePlusPlugin.h"

@interface CBUUID (CBUUIDAdditionsFlutterBluePlus)
- (NSString *)fullUUIDString;
@end

@implementation CBUUID (CBUUIDAdditionsFlutterBluePlus)
- (NSString *)fullUUIDString {
  if(self.UUIDString.length == 4) {
    return [[NSString stringWithFormat:@"0000%@-0000-1000-8000-00805F9B34FB", self.UUIDString] lowercaseString];
  }
  return [self.UUIDString lowercaseString];
}
@end

typedef NS_ENUM(NSUInteger, LogLevel) {
  emergency = 0,
  alert = 1,
  critical = 2,
  error = 3,
  warning = 4,
  notice = 5,
  info = 6,
  debug = 7
};

@interface FlutterBluePlusPlugin ()
@property(nonatomic, retain) NSObject<FlutterPluginRegistrar> *registrar;
@property(nonatomic, retain) FlutterMethodChannel *methodChannel;
@property(nonatomic, retain) CBCentralManager *centralManager;
@property(nonatomic) NSMutableDictionary *scannedPeripherals;
@property(nonatomic) NSMutableArray *servicesThatNeedDiscovered;
@property(nonatomic) NSMutableArray *characteristicsThatNeedDiscovered;
@property(nonatomic) LogLevel logLevel;
@end

@implementation FlutterBluePlusPlugin
+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar>*)registrar {
  FlutterMethodChannel* methodChannel = [FlutterMethodChannel
                                   methodChannelWithName:NAMESPACE @"/methods"
                                   binaryMessenger:[registrar messenger]];
  FlutterBluePlusPlugin* instance = [[FlutterBluePlusPlugin alloc] init];
  instance.methodChannel = methodChannel;
  instance.scannedPeripherals = [NSMutableDictionary new];
  instance.servicesThatNeedDiscovered = [NSMutableArray new];
  instance.characteristicsThatNeedDiscovered = [NSMutableArray new];
  instance.logLevel = emergency;

  [registrar addMethodCallDelegate:instance channel:methodChannel];
}

////////////////////////////////////////////////////////////
// ██   ██   █████   ███    ██  ██████   ██       ███████    
// ██   ██  ██   ██  ████   ██  ██   ██  ██       ██         
// ███████  ███████  ██ ██  ██  ██   ██  ██       █████      
// ██   ██  ██   ██  ██  ██ ██  ██   ██  ██       ██         
// ██   ██  ██   ██  ██   ████  ██████   ███████  ███████                                                       
//                                                      
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

- (void)handleMethodCall:(FlutterMethodCall*)call result:(FlutterResult)result {
    if (_logLevel >= debug) {
        NSLog(@"[FBP-iOS] handleMethodCall: %@", call.method);
    }
  
  if ([@"setLogLevel" isEqualToString:call.method]) {
    NSNumber *logLevelIndex = [call arguments];
    _logLevel = (LogLevel)[logLevelIndex integerValue];
    result(@(true));
    return;
  }
  if (self.centralManager == nil) {
    self.centralManager = [[CBCentralManager alloc] initWithDelegate:self queue:nil];
  }
  if ([@"state" isEqualToString:call.method]) {
    NSDictionary *data = [self toBluetoothStateProto:self->_centralManager.state];
    result(data);
  } else if([@"isAvailable" isEqualToString:call.method]) {
    if(self.centralManager.state != CBManagerStateUnsupported && self.centralManager.state != CBManagerStateUnknown) {
      result(@(YES));
    } else {
      result(@(NO));
    }
  } else if([@"isOn" isEqualToString:call.method]) {
    if(self.centralManager.state == CBManagerStatePoweredOn) {
      result(@(YES));
    } else {
      result(@(NO));
    }
  } else if([@"startScan" isEqualToString:call.method]) {
    // Clear any existing scan results
    [self.scannedPeripherals removeAllObjects];
    // See BmScanSettings
    NSDictionary *args = (NSDictionary*)call.arguments;
    NSArray   *serviceUuids    = args[@"service_uuids"];
    NSNumber  *allowDuplicates = args[@"allow_duplicates"];
    // UUID Service filter
    NSArray *uuids = [NSArray array];
    for (int i = 0; i < [serviceUuids count]; i++) {
      NSString *u = serviceUuids[i];
      uuids = [uuids arrayByAddingObject:[CBUUID UUIDWithString:u]];
    }
    NSMutableDictionary<NSString *, id> *scanOpts = [NSMutableDictionary new];
    if ([allowDuplicates boolValue]) {
        [scanOpts setObject:[NSNumber numberWithBool:YES] forKey:CBCentralManagerScanOptionAllowDuplicatesKey];
    }
    // Start scanning
    [self->_centralManager scanForPeripheralsWithServices:uuids options:scanOpts];
    result(@(true));
  } else if([@"stopScan" isEqualToString:call.method]) {
    [self->_centralManager stopScan];
    result(@(true));
  } else if([@"getConnectedDevices" isEqualToString:call.method]) {
    // Cannot pass blank UUID list for security reasons. Assume all devices have the Generic Access service 0x1800
    NSArray *periphs = [self->_centralManager retrieveConnectedPeripheralsWithServices:@[ [CBUUID UUIDWithString:@"1800"] ]];
    NSLog(@"getConnectedDevices periphs size: %lu", (unsigned long)[periphs count]);
    [self log:debug format:[NSString stringWithFormat:@"getConnectedDevices periphs size: %lu", [periphs count]]];
    result([self toConnectedDeviceResponseProto:periphs]);
  } else if([@"connect" isEqualToString:call.method]) {
    // See BmConnectRequest
    NSDictionary* args = (NSDictionary*)call.arguments;
    NSString  *remoteId           = args[@"remote_id"];

    @try {
      CBPeripheral *peripheral = [_scannedPeripherals objectForKey:remoteId];
      if(peripheral == nil) {
        //FlutterErrorではなく、NSExceptionを返す
        [NSException raise:@"connect" format:@"Peripheral not found"];
      }
      // TODO: Implement Connect options (#36)
      [_centralManager connectPeripheral:peripheral options:nil];
      result(@(true));
    } @catch(NSException *e) {
      //NSExceptionをFlutterErrorに変換して返す
      result([FlutterError errorWithCode:[e name] message:[e reason] details:NULL]);
    }
  } else if([@"disconnect" isEqualToString:call.method]) {
    // remoteId is passed raw, not in a NSDictionary
    NSString *remoteId = [call arguments];
    @try {
      CBPeripheral *peripheral = [self findPeripheral:remoteId];
      [_centralManager cancelPeripheralConnection:peripheral];
      result(@(true));
    } @catch(NSException *e) {
      //NSExceptionをFlutterErrorに変換して返す
      result([FlutterError errorWithCode:[e name] message:[e reason] details:NULL]);
    }
  } else if([@"deviceState" isEqualToString:call.method]) {
    // remoteId is passed raw, not in a NSDictionary
    NSString *remoteId = [call arguments];
    @try {
      CBPeripheral *peripheral = [self findPeripheral:remoteId];
      result([self toDeviceStateProto:peripheral state:peripheral.state]);
    } @catch(NSException *e) {
      //NSExceptionをFlutterErrorに変換して返す
      result([FlutterError errorWithCode:[e name] message:[e reason] details:NULL]);
    }
  } else if([@"discoverServices" isEqualToString:call.method]) {
    // remoteId is passed raw, not in a NSDictionary
    NSString *remoteId = [call arguments];
    @try {
      CBPeripheral *peripheral = [self findPeripheral:remoteId];
      // Clear helper arrays
      [_servicesThatNeedDiscovered removeAllObjects];
      [_characteristicsThatNeedDiscovered removeAllObjects ];
      [peripheral discoverServices:nil];
      result(@(true));
    } @catch(NSException *e) {
      //NSExceptionをFlutterErrorに変換して返す
      result([FlutterError errorWithCode:[e name] message:[e reason] details:NULL]);
    }
  } else if([@"services" isEqualToString:call.method]) {
    // remoteId is passed raw, not in a NSDictionary
    NSString *remoteId = [call arguments];
    @try {
      CBPeripheral *peripheral = [self findPeripheral:remoteId];
      result([self toServicesResultProto:peripheral]);
    } @catch(NSException *e) {
      //NSExceptionをFlutterErrorに変換して返す
      result([FlutterError errorWithCode:[e name] message:[e reason] details:NULL]);
    }
  } else if([@"readCharacteristic" isEqualToString:call.method]) {
    // See BmReadCharacteristicRequest
    NSDictionary *args = (NSDictionary*)call.arguments;
    NSString  *remoteId             = args[@"remote_id"];
    NSString  *characteristicUuid   = args[@"characteristic_uuid"];
    NSString  *serviceUuid          = args[@"service_uuid"];
    NSString  *secondaryServiceUuid = args[@"secondary_service_uuid"];

    @try {
      // Find peripheral
      CBPeripheral *peripheral = [self findPeripheral:remoteId];
      // Find characteristic
      CBCharacteristic *characteristic = [self locateCharacteristic:characteristicUuid
                                                               peripheral:peripheral
                                                                serviceId:serviceUuid
                                                       secondaryServiceId:secondaryServiceUuid];
      // Trigger a read
      [peripheral readValueForCharacteristic:characteristic];
      result(nil);
    } @catch(NSException *e) {
      //NSExceptionをFlutterErrorに変換して返す
      result([FlutterError errorWithCode:[e name] message:[e reason] details:NULL]);
    }
  } else if([@"readDescriptor" isEqualToString:call.method]) {
    // See BmReadDescriptorRequest
    NSDictionary *args = (NSDictionary*)call.arguments;
    NSString  *remoteId             = args[@"remote_id"];
    NSString  *descriptorUuid       = args[@"descriptor_uuid"];
    NSString  *serviceUuid          = args[@"service_uuid"];
    NSString  *secondaryServiceUuid = args[@"secondary_service_uuid"];
    NSString  *characteristicUuid   = args[@"characteristic_uuid"];
    @try {
      // Find peripheral
      CBPeripheral *peripheral = [self findPeripheral:remoteId];
      // Find characteristic
      CBCharacteristic *characteristic = [self locateCharacteristic:characteristicUuid
                                                               peripheral:peripheral
                                                                serviceId:serviceUuid
                                                       secondaryServiceId:secondaryServiceUuid];
      // Find descriptor
      CBDescriptor *descriptor = [self locateDescriptor:descriptorUuid characteristic:characteristic];
      [peripheral readValueForDescriptor:descriptor];
      result(nil);
    } @catch(NSException *e) {
      //NSExceptionをFlutterErrorに変換して返す
      result([FlutterError errorWithCode:[e name] message:[e reason] details:NULL]);
    }
  } else if([@"writeCharacteristic" isEqualToString:call.method]) {
    // See BmWriteCharacteristicRequest
    NSDictionary *args = (NSDictionary*)call.arguments;
    NSString  *remoteId             = args[@"remote_id"];
    NSString  *characteristicUuid   = args[@"characteristic_uuid"];
    NSString  *serviceUuid          = args[@"service_uuid"];
    NSString  *secondaryServiceUuid = args[@"secondary_service_uuid"];
    NSNumber  *writeType            = args[@"write_type"];
    NSString   *value               = args[@"value"];
    @try {
      // Find peripheral
      CBPeripheral *peripheral = [self findPeripheral:remoteId];
      // Find characteristic
      CBCharacteristic *characteristic = [self locateCharacteristic:characteristicUuid peripheral:peripheral serviceId:serviceUuid secondaryServiceId:secondaryServiceUuid];
      // Get correct write type
      CBCharacteristicWriteType type = ([writeType intValue] == 0
                    ? CBCharacteristicWriteWithResponse
                    : CBCharacteristicWriteWithoutResponse);
      // Write to characteristic
      [peripheral writeValue:[self convertHexToData:value] forCharacteristic:characteristic type:type];
      result(nil);
    } @catch(NSException *e) {
      //NSExceptionをFlutterErrorに変換して返す
      result([FlutterError errorWithCode:[e name] message:[e reason] details:NULL]);
    }
  } else if([@"writeDescriptor" isEqualToString:call.method]) {
    // See BmWriteDescriptorRequest
    NSDictionary *args = (NSDictionary*)call.arguments;
    NSString  *remoteId             = args[@"remote_id"];
    NSString  *descriptorUuid       = args[@"descriptor_uuid"];
    NSString  *serviceUuid          = args[@"service_uuid"];
    NSString  *secondaryServiceUuid = args[@"secondary_service_uuid"];
    NSString  *characteristicUuid   = args[@"characteristic_uuid"];
    NSString  *value                = args[@"value"];
    @try {
      // Find peripheral
      CBPeripheral *peripheral = [self findPeripheral:remoteId];
      // Find characteristic
      CBCharacteristic *characteristic = [self locateCharacteristic:characteristicUuid peripheral:peripheral serviceId:serviceUuid secondaryServiceId:secondaryServiceUuid];
      // Find descriptor
      CBDescriptor *descriptor = [self locateDescriptor:descriptorUuid characteristic:characteristic];
      // Write descriptor
      [peripheral writeValue:[self convertHexToData:value] forDescriptor:descriptor];
      result(nil);
    } @catch(NSException *e) {
      //NSExceptionをFlutterErrorに変換して返す
      result([FlutterError errorWithCode:[e name] message:[e reason] details:NULL]);
    }
  } else if([@"setNotification" isEqualToString:call.method]) {
    // See BmSetNotificationRequest
    NSDictionary *args = (NSDictionary*)call.arguments;
    NSString   *remoteId              = args[@"remote_id"];
    NSString   *serviceUuid           = args[@"service_uuid"];
    NSString   *secondaryServiceUuid  = args[@"secondary_service_uuid"];
    NSString   *characteristicUuid    = args[@"characteristic_uuid"];
    NSNumber   *enable                = args[@"enable"];
    @try {
      // Find peripheral
      CBPeripheral *peripheral = [self findPeripheral:remoteId];
      // Find characteristic
      CBCharacteristic *characteristic = [self locateCharacteristic:characteristicUuid peripheral:peripheral serviceId:serviceUuid secondaryServiceId:secondaryServiceUuid];
      // Set notification value
      [peripheral setNotifyValue:[enable boolValue] forCharacteristic:characteristic];
      result(@(true));
    } @catch(NSException *e) {
      //NSExceptionをFlutterErrorに変換して返す
      result([FlutterError errorWithCode:[e name] message:[e reason] details:NULL]);
    }
  } else if([@"mtu" isEqualToString:call.method]) {
    // remoteId is passed raw, not in a NSDictionary
    NSString *remoteId = [call arguments];
    @try {
      CBPeripheral *peripheral = [self findPeripheral:remoteId];
      uint32_t mtu = [self getMtu:peripheral];
      result([self toMtuSizeResponseProto:peripheral mtu:mtu]);
    } @catch(NSException *e) {
      //NSExceptionをFlutterErrorに変換して返す
      result([FlutterError errorWithCode:[e name] message:[e reason] details:NULL]);
    }
  } else if([@"requestMtu" isEqualToString:call.method]) {
    result([FlutterError errorWithCode:@"requestMtu" message:@"iOS does not allow mtu requests to the peripheral" details:NULL]);
  } else if([@"readRssi" isEqualToString:call.method]) {
    // remoteId is passed raw, not in a NSDictionary
    NSString *remoteId = [call arguments];
    @try {
      CBPeripheral *peripheral = [self findPeripheral:remoteId];
      [peripheral readRSSI];
      result(@(true));
    } @catch(NSException *e) {
      //NSExceptionをFlutterErrorに変換して返す
      result([FlutterError errorWithCode:[e name] message:[e reason] details:NULL]);
    }
  } else {
    result(FlutterMethodNotImplemented);
  }
}

//////////////////////////////////////////////////////////////////////
// ██████   ██████   ██  ██    ██   █████   ████████  ███████ 
// ██   ██  ██   ██  ██  ██    ██  ██   ██     ██     ██      
// ██████   ██████   ██  ██    ██  ███████     ██     █████   
// ██       ██   ██  ██   ██  ██   ██   ██     ██     ██      
// ██       ██   ██  ██    ████    ██   ██     ██     ███████
//
// ██    ██  ████████  ██  ██       ███████ 
// ██    ██     ██     ██  ██       ██      
// ██    ██     ██     ██  ██       ███████ 
// ██    ██     ██     ██  ██            ██ 
//  ██████      ██     ██  ███████  ███████ 

- (NSData *)convertHexToData:(NSString *)hexString
{
    if (hexString.length % 2 != 0) {
        return nil;
    }

    NSMutableData *data = [NSMutableData new];

    for (NSInteger i = 0; i < hexString.length; i += 2) {
        unsigned int byte = 0;
        NSRange range = NSMakeRange(i, 2);
        [[NSScanner scannerWithString:[hexString substringWithRange:range]] scanHexInt:&byte];
        [data appendBytes:&byte length:1];
    }

    return [data copy];
}

- (CBPeripheral*)findPeripheral:(NSString*)remoteId {
  NSArray<CBPeripheral*> *peripherals = [_centralManager retrievePeripheralsWithIdentifiers:@[[[NSUUID alloc] initWithUUIDString:remoteId]]];
  CBPeripheral *peripheral;
  for(CBPeripheral *p in peripherals) {
    if([[p.identifier UUIDString] isEqualToString:remoteId]) {
      peripheral = p;
      break;
    }
  }
  if(peripheral == nil) {
    [NSException raise:@"findPeripheral" format:@"Peripheral not found"];
  }
  return peripheral;
}

- (CBCharacteristic*)locateCharacteristic:(NSString*)characteristicId peripheral:(CBPeripheral*)peripheral serviceId:(NSString*)serviceId secondaryServiceId:(NSString*)secondaryServiceId {
  CBService *primaryService = [self getServiceFromArray:serviceId array:[peripheral services]];
  if(primaryService == nil || [primaryService isPrimary] == false) {
    [NSException raise:@"locateCharacteristic" format:@"service could not be located on the device"];
  }
  CBService *secondaryService;
  if(secondaryServiceId.length) {
    secondaryService = [self getServiceFromArray:secondaryServiceId array:[primaryService includedServices]];
    [NSException raise:@"locateCharacteristic" format:@"secondary service could not be located on the device -> %@", secondaryServiceId];
  }
  CBService *service = (secondaryService != nil) ? secondaryService : primaryService;
  CBCharacteristic *characteristic = [self getCharacteristicFromArray:characteristicId array:[service characteristics]];
  if(characteristic == nil) {
    [NSException raise:@"locateCharacteristic" format:@"characteristic could not be located on the device"];
  }
  return characteristic;
}

- (CBDescriptor*)locateDescriptor:(NSString*)descriptorId characteristic:(CBCharacteristic*)characteristic {
  CBDescriptor *descriptor = [self getDescriptorFromArray:descriptorId array:[characteristic descriptors]];
  if(descriptor == nil) {
    [NSException raise:@"locateDescriptor" format:@"descriptor could not be located on the device"];
  }
  return descriptor;
}

// Reverse search to find primary service
- (CBService*)findPrimaryService:(CBService*)secondaryService peripheral:(CBPeripheral*)peripheral {
  for(CBService *s in [peripheral services]) {
    for(CBService *ss in [s includedServices]) {
      if([[ss.UUID UUIDString] isEqualToString:[secondaryService.UUID UUIDString]]) {
        return s;
      }
    }
  }
  return nil;
}

- (CBDescriptor*)findCCCDescriptor:(CBCharacteristic*)characteristic {
  for(CBDescriptor *d in characteristic.descriptors) {
    if([d.UUID.UUIDString isEqualToString:@"2902"]) {
      return d;
    }
  }
  return nil;
}

- (CBService*)getServiceFromArray:(NSString*)uuidString array:(NSArray<CBService*>*)array {
  for(CBService *s in array) {
    if([[s UUID] isEqual:[CBUUID UUIDWithString:uuidString]]) {
      return s;
    }
  }
  return nil;
}

- (CBCharacteristic*)getCharacteristicFromArray:(NSString*)uuidString array:(NSArray<CBCharacteristic*>*)array {
  for(CBCharacteristic *c in array) {
    if([[c UUID] isEqual:[CBUUID UUIDWithString:uuidString]]) {
      return c;
    }
  }
  return nil;
}

- (CBDescriptor*)getDescriptorFromArray:(NSString*)uuidString array:(NSArray<CBDescriptor*>*)array {
  for(CBDescriptor *d in array) {
    if([[d UUID] isEqual:[CBUUID UUIDWithString:uuidString]]) {
      return d;
    }
  }
  return nil;
}


/////////////////////////////////////////////////////////////////////////////////////
//  ██████  ██████    ██████  ███████  ███    ██  ████████  ██████    █████  ██      
// ██       ██   ██  ██       ██       ████   ██     ██     ██   ██  ██   ██ ██      
// ██       ██████   ██       █████    ██ ██  ██     ██     ██████   ███████ ██      
// ██       ██   ██  ██       ██       ██  ██ ██     ██     ██   ██  ██   ██ ██      
//  ██████  ██████    ██████  ███████  ██   ████     ██     ██   ██  ██   ██ ███████ 
//                                                                                                                                          
// ███    ███   █████   ███    ██   █████    ██████   ███████  ██████               
// ████  ████  ██   ██  ████   ██  ██   ██  ██        ██       ██   ██              
// ██ ████ ██  ███████  ██ ██  ██  ███████  ██   ███  █████    ██████               
// ██  ██  ██  ██   ██  ██  ██ ██  ██   ██  ██    ██  ██       ██   ██              
// ██      ██  ██   ██  ██   ████  ██   ██   ██████   ███████  ██   ██              
//                                                                                                                                                   
// ██████   ███████  ██       ███████   ██████    █████   ████████  ███████          
// ██   ██  ██       ██       ██       ██        ██   ██     ██     ██               
// ██   ██  █████    ██       █████    ██   ███  ███████     ██     █████            
// ██   ██  ██       ██       ██       ██    ██  ██   ██     ██     ██               
// ██████   ███████  ███████  ███████   ██████   ██   ██     ██     ███████ 

- (void)centralManagerDidUpdateState:(nonnull CBCentralManager *)central {
  if (_logLevel >= debug) {
    NSLog(@"[FBP-iOS] centralManagerDidUpdateState");
  }
  NSDictionary *response = [self toBluetoothStateProto:self->_centralManager.state];
  [_methodChannel invokeMethod:@"adapterStateChanged" arguments:response];
}

- (void)centralManager:(CBCentralManager *)central didDiscoverPeripheral:(CBPeripheral *)peripheral advertisementData:(NSDictionary<NSString *,id> *)advertisementData RSSI:(NSNumber *)RSSI {
  if (_logLevel >= debug) {
    //NSLog(@"[FBP-iOS] centralManager didDiscoverPeripheral");
  }
  [self.scannedPeripherals setObject:peripheral forKey:[[peripheral identifier] UUIDString]];
  NSDictionary *result = [self toScanResultProto:peripheral advertisementData:advertisementData RSSI:RSSI];
  [_methodChannel invokeMethod:@"ScanResult" arguments:result];
}

- (void)centralManager:(CBCentralManager *)central didConnectPeripheral:(CBPeripheral *)peripheral {
  NSLog(@"didConnectPeripheral");
  [self log:debug format:@"didConnectPeripheral"];
  // Register self as delegate for peripheral
  peripheral.delegate = self;

  // Send initial mtu size
  uint32_t mtu = [self getMtu:peripheral];
  [_methodChannel invokeMethod:@"MtuSize" arguments:[self toMtuSizeResponseProto:peripheral mtu:mtu]];

  // Send connection state
  [_methodChannel invokeMethod:@"DeviceState" arguments:[self toDeviceStateProto:peripheral state:peripheral.state]];
}

- (void)centralManager:(CBCentralManager *)central didDisconnectPeripheral:(CBPeripheral *)peripheral error:(NSError *)error {
  NSLog(@"didDisconnectPeripheral");
  [self log:debug format:@"didDisconnectPeripheral"];
  if(error != NULL) {
    [self log:debug format:[NSString stringWithFormat:@"didDisconnectPeripheral error: %d", error]];
  }
  // Unregister self as delegate for peripheral, not working #42
  peripheral.delegate = nil;

  // Send connection state
  [_methodChannel invokeMethod:@"DeviceState" arguments:[self toDeviceStateProto:peripheral state:peripheral.state]];
}

- (void)centralManager:(CBCentralManager *)central didFailToConnectPeripheral:(CBPeripheral *)peripheral error:(NSError *)error {
  // TODO:?
   //エラーコードを送るようにする
  [self log:debug format:[NSString stringWithFormat:@"didFailToConnectPeripheral: %@,%d", error, [error code]]];
  [_methodChannel invokeMethod:@"DeviceState" arguments:[self toDeviceStateProto:peripheral state:peripheral.state]];
}

//
// CBPeripheralDelegate methods
//
- (void)peripheral:(CBPeripheral *)peripheral didDiscoverServices:(NSError *)error {
  NSLog(@"didDiscoverServices");
  [self log:debug format:@"didDiscoverServices"];
  // Send negotiated mtu size
  uint32_t mtu = [self getMtu:peripheral];
  [_methodChannel invokeMethod:@"MtuSize" arguments:[self toMtuSizeResponseProto:peripheral mtu:mtu]];

  // Loop through and discover characteristics and secondary services
  [_servicesThatNeedDiscovered addObjectsFromArray:peripheral.services];
  for(CBService *s in [peripheral services]) {
    NSLog(@"Found service: %@", [s.UUID UUIDString]);
    [self log:debug format:[NSString stringWithFormat:@"Found service: %@", [s.UUID UUIDString]]];
    [peripheral discoverCharacteristics:nil forService:s];
    // [peripheral discoverIncludedServices:nil forService:s]; // Secondary services in the future (#8)
  }
}

- (void)peripheral:(CBPeripheral *)peripheral didDiscoverCharacteristicsForService:(CBService *)service error:(NSError *)error {
  NSLog(@"didDiscoverCharacteristicsForService");
  [self log:debug format:@"didDiscoverCharacteristicsForService"];
  if(error != NULL) {
    [self log:debug format:[NSString stringWithFormat:@"didDiscoverCharacteristicsForService error: %@", error]];
  }
  // Loop through and discover descriptors for characteristics
  [_servicesThatNeedDiscovered removeObject:service];
  [_characteristicsThatNeedDiscovered addObjectsFromArray:service.characteristics];
  for(CBCharacteristic *c in [service characteristics]) {
    [peripheral discoverDescriptorsForCharacteristic:c];
  }
}

- (void)peripheral:(CBPeripheral *)peripheral didDiscoverDescriptorsForCharacteristic:(CBCharacteristic *)characteristic error:(NSError *)error {
  NSLog(@"didDiscoverDescriptorsForCharacteristic");
  [self log:debug format:@"didDiscoverDescriptorsForCharacteristic"];
  if(error != NULL) {
    [self log:debug format:[NSString stringWithFormat:@"didDiscoverDescriptorsForCharacteristic error: %@", error]];
  }
  [_characteristicsThatNeedDiscovered removeObject:characteristic];
  if(_servicesThatNeedDiscovered.count > 0 || _characteristicsThatNeedDiscovered.count > 0) {
    // Still discovering
    return;
  }
  // Send updated tree
  NSDictionary* result = [self toServicesResultProto:peripheral];
  [_methodChannel invokeMethod:@"DiscoverServicesResult" arguments:result];
}

- (void)peripheral:(CBPeripheral *)peripheral didDiscoverIncludedServicesForService:(CBService *)service error:(NSError *)error {
  NSLog(@"didDiscoverIncludedServicesForService");
  [self log:debug format:@"didDiscoverIncludedServicesForService"];
  if(error != NULL) {
    [self log:debug format:[NSString stringWithFormat:@"didDiscoverIncludedServicesForService error: %@", error]];
  }
  // Loop through and discover characteristics for secondary services
  for(CBService *ss in [service includedServices]) {
    [peripheral discoverCharacteristics:nil forService:ss];
  }
}

- (void)peripheral:(CBPeripheral *)peripheral didUpdateValueForCharacteristic:(CBCharacteristic *)characteristic error:(NSError *)error {
  NSLog(@"didUpdateValueForCharacteristic %@", [peripheral.identifier UUIDString]);
  // on iOS, this method also handles notification values
  [self log:debug format:[NSString stringWithFormat:@"didUpdateValueForCharacteristic %@", [peripheral.identifier UUIDString]]];
  if(error != NULL) {
    [self log:debug format:[NSString stringWithFormat:@"didUpdateValueForCharacteristic error: %@", error]];
  }
  // See BmReadCharacteristicResponse
  NSDictionary* result = @{
    @"remote_id":       [peripheral.identifier UUIDString],
    @"characteristic":  [self toCharacteristicProto:peripheral characteristic:characteristic],
  };
  [_methodChannel invokeMethod:@"ReadCharacteristicResponse" arguments:result];

  // See: BmOnCharacteristicChanged
  NSDictionary* onChangedResult = @{
    @"remote_id":       [peripheral.identifier UUIDString],
    @"characteristic":  [self toCharacteristicProto:peripheral characteristic:characteristic],
  };
  [_methodChannel invokeMethod:@"OnCharacteristicChanged" arguments:onChangedResult];
}

- (void)peripheral:(CBPeripheral *)peripheral didWriteValueForCharacteristic:(CBCharacteristic *)characteristic error:(NSError *)error {
  NSLog(@"didWriteValueForCharacteristic");
  [self log:debug format:@"didWriteValueForCharacteristic"];
  if(error != NULL) {
    [self log:debug format:[NSString stringWithFormat:@"didWriteValueForCharacteristic error: %@", error]];
  }
  // See BmWriteCharacteristicRequest
  NSDictionary* request = @{
    @"remote_id":              [peripheral.identifier UUIDString],
    @"characteristic_uuid":    [characteristic.UUID fullUUIDString],
    @"service_uuid":           [characteristic.service.UUID fullUUIDString],
    @"secondary_service_uuid": [NSNull null],
    @"write_type":             @(0),
    @"value":                  @"",
  };
  // See BmWriteCharacteristicResponse
  NSDictionary* result = @{
    @"request": request,
    @"success": @(error == nil),
  };
  [_methodChannel invokeMethod:@"WriteCharacteristicResponse" arguments:result];
}

- (void)peripheral:(CBPeripheral *)peripheral didUpdateNotificationStateForCharacteristic:(CBCharacteristic *)characteristic error:(NSError *)error {
  NSLog(@"didUpdateNotificationStateForCharacteristic");
  [self log:debug format:@"didUpdateNotificationStateForCharacteristic"];
  if(error != NULL) {
    [self log:debug format:[NSString stringWithFormat:@"didUpdateNotificationStateForCharacteristic error: %@", error]];
  }
  // Read CCC descriptor of characteristic
  CBDescriptor *cccd = [self findCCCDescriptor:characteristic];
  if(cccd == nil || error != nil) {
    // See BmSetNotificationResponse
    NSDictionary* response = @{
        @"remote_id":      [peripheral.identifier UUIDString],
        @"characteristic": [self toCharacteristicProto:peripheral characteristic:characteristic],
        @"success":        @(false),
    };
    [_methodChannel invokeMethod:@"SetNotificationResponse" arguments:response];
    return;
  }

  // Request a read
  [peripheral readValueForDescriptor:cccd];
}

- (void)peripheral:(CBPeripheral *)peripheral didUpdateValueForDescriptor:(CBDescriptor *)descriptor error:(NSError *)error {
  if(error != NULL) {
    [self log:debug format:[NSString stringWithFormat:@"didUpdateValueForDescriptor error: %@", error]];
  }
  // primary & secondary service
  CBService *primaryService = NULL;
  CBService *secondaryService = NULL;
  if ([descriptor.characteristic.service isPrimary]) {
    primaryService = descriptor.characteristic.service;
    secondaryService = NULL;
  } else {
    // Reverse search to find service and secondary service UUID
    secondaryService = descriptor.characteristic.service;
    primaryService = [self findPrimaryService:[descriptor.characteristic service]
                                       peripheral:[descriptor.characteristic.service peripheral]];
  }
  // See BmReadDescriptorRequest
  NSDictionary* q = @{
    @"remote_id":              [peripheral.identifier UUIDString],
    @"descriptor_uuid":        [descriptor.UUID fullUUIDString],
    @"service_uuid":           [primaryService.UUID fullUUIDString],
    @"secondary_service_uuid": secondaryService ? [secondaryService.UUID fullUUIDString] : [NSNull null],
    @"characteristic_uuid":    [descriptor.characteristic.UUID fullUUIDString],
  };
  int value = [descriptor.value intValue];
  // See BmReadDescriptorResponse
  NSDictionary* result = @{
    @"request": q,
    @"value": [self convertDataToHex:[NSData dataWithBytes:&value length:sizeof(value)]],
  };
  [_methodChannel invokeMethod:@"ReadDescriptorResponse" arguments:result];

  // If descriptor is CCCD, send a SetNotificationResponse in case anything is awaiting
  if([descriptor.UUID.UUIDString isEqualToString:@"2902"]){
    // See BmSetNotificationResponse
    NSDictionary* response = @{
        @"remote_id":      [peripheral.identifier UUIDString],
        @"characteristic": [self toCharacteristicProto:peripheral characteristic:descriptor.characteristic],
        @"success":        @(true),
    };
    [_methodChannel invokeMethod:@"SetNotificationResponse" arguments:response];
  }
}

- (void)peripheral:(CBPeripheral *)peripheral didWriteValueForDescriptor:(CBDescriptor *)descriptor error:(NSError *)error {
  if(error != NULL) {
    [self log:debug format:[NSString stringWithFormat:@"didWriteValueForDescriptor error: %@", error]];
  }
  // primary & secondary service
  CBService *primaryService = NULL;
  CBService *secondaryService = NULL;
  if ([descriptor.characteristic.service isPrimary]) {
    primaryService = descriptor.characteristic.service;
    secondaryService = NULL;
  } else {
    // Reverse search to find service and secondary service UUID
    secondaryService = descriptor.characteristic.service;
    primaryService = [self findPrimaryService:[descriptor.characteristic service]
                                       peripheral:[descriptor.characteristic.service peripheral]];
  }

  // See BmWriteDescriptorRequest
  NSDictionary* request = @{
    @"remote_id":               [peripheral.identifier UUIDString],
    @"descriptor_uuid":         [descriptor.UUID fullUUIDString],
    @"service_uuid" :           [primaryService.UUID fullUUIDString],
    @"secondary_service_uuid":  secondaryService ? [secondaryService.UUID fullUUIDString] : [NSNull null],
    @"characteristic_uuid":     [descriptor.characteristic.UUID fullUUIDString],
    @"value":                   @"",
  };

  // See BmWriteDescriptorResponse
  NSDictionary* result = @{
    @"request": request,
    @"success": @(error == nil),
  };

  [_methodChannel invokeMethod:@"WriteDescriptorResponse" arguments:result];
}

- (void)peripheral:(CBPeripheral *)peripheral didReadRSSI:(NSNumber *)rssi error:(NSError *)error {
  if(error != NULL) {
    [self log:debug format:[NSString stringWithFormat:@"didReadRSSI error: %@", error]];
  }
  // See BmReadRssiResult
  NSDictionary* result = @{
    @"remote_id": [peripheral.identifier UUIDString],
    @"rssi": rssi,
  };
  [_methodChannel invokeMethod:@"ReadRssiResult" arguments:result];
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


- (NSString *)convertDataToHex:(NSData *)data 
{
    const unsigned char *bytes = (const unsigned char *)[data bytes];
    NSMutableString *hexString = [NSMutableString new];

    for (NSInteger i = 0; i < data.length; i++) {
        [hexString appendFormat:@"%02x", bytes[i]];
    }

    return [hexString copy];
}

- (NSDictionary*)toBluetoothStateProto:(CBManagerState)state {
  int value = 0;
  switch(state) {
    case CBManagerStateResetting:    value = 3; break; // BmPowerEnum.turningOn
    case CBManagerStateUnsupported:  value = 1; break; // BmPowerEnum.unavailable
    case CBManagerStateUnauthorized: value = 2; break; // BmPowerEnum.unauthorized
    case CBManagerStatePoweredOff:   value = 6; break; // BmPowerEnum.off
    case CBManagerStatePoweredOn:    value = 4; break; // BmPowerEnum.on
    default:                         value = 0; break; // BmPowerEnum.unknown
  }

  // See BmBluetoothPowerState
  return @{
    @"state" : @(value),
  };
}

- (NSDictionary*)toScanResultProto:(CBPeripheral *)peripheral advertisementData:(NSDictionary<NSString *,id> *)advertisementData RSSI:(NSNumber *)RSSI {
  NSString     *localName      = advertisementData[CBAdvertisementDataLocalNameKey];
  NSNumber     *connectable    = advertisementData[CBAdvertisementDataIsConnectable];
  NSData       *manufData      = advertisementData[CBAdvertisementDataManufacturerDataKey];
  NSNumber     *txPower        = advertisementData[CBAdvertisementDataTxPowerLevelKey];
  NSArray      *serviceUuids   = advertisementData[CBAdvertisementDataServiceUUIDsKey];
  NSDictionary *serviceData    = advertisementData[CBAdvertisementDataServiceDataKey];
  // Manufacturer Data
  NSDictionary* manufDataB = nil;
  if (manufData != nil && manufData.length > 2) {

    // first 2 bytes are manufacturerId
    unsigned short manufId = 0;
    [manufData getBytes:&manufId length:2];

    // trim off first 2 bytes
    NSData* trimmed = [manufData subdataWithRange:NSMakeRange(2, manufData.length - 2)];
    NSString* hex = [self convertDataToHex:trimmed];

    manufDataB = @{
        @(manufId): hex,
    };
  }
  // Service Uuids - convert from CBUUID's to UUID strings
  NSArray *serviceUuidsB = nil;
  if (serviceData != nil) {
    NSMutableArray *mutable = [[NSMutableArray alloc] init];
    for (CBUUID *uuid in serviceUuids) {
        [mutable addObject:uuid.UUIDString];
    }
    serviceUuidsB = [mutable copy];
  }
  /// Service Data - convert from CBUUID's to UUID strings
  NSDictionary *serviceDataB = nil;
  if (serviceData != nil)
  {
    NSMutableDictionary *mutable = [[NSMutableDictionary alloc] init];
    for (CBUUID *uuid in serviceData) {
        NSString* hex = [self convertDataToHex:serviceData[uuid]];
        [mutable setObject:hex forKey:uuid.UUIDString];
    }
    serviceDataB = [mutable copy];
  }
  // See BmAdvertisementData
  NSDictionary* ad = @{
    @"local_name":         localName     ? localName     : [NSNull null],
    @"tx_power_level":     txPower       ? txPower       : [NSNull null],
    @"connectable":        connectable   ? connectable   : @(0),
    @"manufacturer_data":  manufDataB    ? manufDataB    : [NSNull null],
    @"service_uuids":      serviceUuidsB ? serviceUuidsB : [NSNull null],
    @"service_data":       serviceDataB  ? serviceDataB  : [NSNull null],
  };

  // See BmScanResult
  return @{
    @"device":             [self toDeviceProto:peripheral],
    @"advertisement_data": ad,
    @"rssi":               RSSI ? RSSI : [NSNull null],
  };
}

- (NSDictionary*)toDeviceProto:(CBPeripheral *)peripheral {
  // See BmBluetoothDevice
  return @{
    @"remote_id": [[peripheral identifier] UUIDString],
    @"name":      [peripheral name] ? [peripheral name] : [NSNull null],
    @"type":      @(2), // hardcode to BLE. Does iOS differentiate?
  };
}

- (NSDictionary *)toDeviceStateProto:(CBPeripheral *)peripheral state:(CBPeripheralState)state {
  int stateIdx = 0;
  switch(state) {
    case CBPeripheralStateDisconnected:  stateIdx = 0; break; // BmConnectionStateEnum.disconnected
    case CBPeripheralStateConnecting:    stateIdx = 1; break; // BmConnectionStateEnum.connecting
    case CBPeripheralStateConnected:     stateIdx = 2; break; // BmConnectionStateEnum.connected
    case CBPeripheralStateDisconnecting: stateIdx = 3; break; // BmConnectionStateEnum.disconnecting
  }

  // See BmConnectionStateResponse
  return @{
    @"remote_id": [[peripheral identifier] UUIDString],
    @"state":     @(stateIdx),
  };
}

- (NSDictionary*)toServicesResultProto:(CBPeripheral *)peripheral {
  // Services
  NSMutableArray *servicesProtos = [NSMutableArray new];
  for(CBService *s in [peripheral services]) {
    [servicesProtos addObject:[self toServiceProto:peripheral service:s]];
  }
  // See BmDiscoverServicesResult
  return @{
    @"remote_id": [peripheral.identifier UUIDString],
    @"services":  servicesProtos,
  };
}

- (NSDictionary*)toConnectedDeviceResponseProto:(NSArray<CBPeripheral*>*)periphs {
  // Devices
  NSMutableArray *deviceProtos = [NSMutableArray new];
  for(CBPeripheral *p in periphs) {
    [deviceProtos addObject:[self toDeviceProto:p]];
  }
  // See BmConnectedDevicesResponse
  return @{
    @"devices": deviceProtos,
  };
}

- (NSDictionary*)toServiceProto:(CBPeripheral *)peripheral service:(CBService *)service  {
  // Characteristics
  NSLog(@"peripheral uuid:%@", [peripheral.identifier UUIDString]);
  NSLog(@"service uuid:%@", [service.UUID fullUUIDString]);
  [self log:debug format:[NSString stringWithFormat:@"peripheral uuid:%@, service uuid:%@", [peripheral.identifier UUIDString], [service.UUID fullUUIDString]]];

  // Characteristic Array
  NSMutableArray *characteristicProtos = [NSMutableArray new];
  for(CBCharacteristic *c in [service characteristics]) {
    [characteristicProtos addObject:[self toCharacteristicProto:peripheral characteristic:c]];
  }

  // Included Services
  NSMutableArray *includedServicesProtos = [NSMutableArray new];
  for(CBService *s in [service includedServices]) {
    [includedServicesProtos addObject:[self toServiceProto:peripheral service:s]];
  }

  // See BmBluetoothService
  return @{
    @"uuid":                [service.UUID fullUUIDString],
    @"remote_id":           [peripheral.identifier UUIDString],
    @"is_primary":          @([service isPrimary]),
    @"characteristics":     characteristicProtos,
    @"included_services":   includedServicesProtos,
  };
}

- (NSDictionary*)toCharacteristicProto:(CBPeripheral *)peripheral characteristic:(CBCharacteristic *)characteristic {
  // descriptors
  NSLog(@"uuid: %@ value: %@", [characteristic.UUID fullUUIDString], [characteristic value]);
  NSMutableArray *descriptorProtos = [NSMutableArray new];
  for(CBDescriptor *d in [characteristic descriptors]) {
    [descriptorProtos addObject:[self toDescriptorProto:peripheral descriptor:d]];
  }
  // primary & secondary service
  CBService *primaryService = NULL;
  CBService *secondaryService = NULL;
  if ([characteristic.service isPrimary]) {
    primaryService = characteristic.service;
    secondaryService = NULL;
  } else {
    // Reverse search to find service and secondary service UUID
    secondaryService = characteristic.service;
    primaryService = [self findPrimaryService:[characteristic service]
                                       peripheral:[characteristic.service peripheral]];
  }

  // See BmBluetoothCharacteristic
  return @{
    @"uuid":                   [characteristic.UUID fullUUIDString],
    @"remote_id":              [peripheral.identifier UUIDString],
    @"service_uuid":           [primaryService.UUID fullUUIDString],
    @"secondary_service_uuid": secondaryService ? [secondaryService.UUID fullUUIDString] : [NSNull null],
    @"descriptors":            descriptorProtos,
    @"properties":             [self toCharacteristicPropsProto:characteristic.properties],
    @"value":                  [self convertDataToHex:[characteristic value]],
  };
}

- (NSDictionary*)toDescriptorProto:(CBPeripheral *)peripheral descriptor:(CBDescriptor *)descriptor {
  int value = [descriptor.value intValue];
  
  // See: BmBluetoothDescriptor
  return @{
    @"uuid":                   [descriptor.UUID fullUUIDString],
    @"remote_id":              [peripheral.identifier UUIDString],
    @"characteristic_uuid":    [descriptor.characteristic.UUID fullUUIDString],
    @"service_uuid":           [descriptor.characteristic.service.UUID fullUUIDString],
    @"secondary_service_uuid": [NSNull null],
    @"value":                  [self convertDataToHex:[NSData dataWithBytes:&value length:sizeof(value)]],
  };
}

- (NSDictionary*)toCharacteristicPropsProto:(CBCharacteristicProperties)props {
  // See: BmCharacteristicProperties
  return @{
    @"broadcast":                    @((props & CBCharacteristicPropertyBroadcast) != 0),
    @"read":                         @((props & CBCharacteristicPropertyRead) != 0),
    @"write_without_response":       @((props & CBCharacteristicPropertyWriteWithoutResponse) != 0),
    @"write":                        @((props & CBCharacteristicPropertyWrite) != 0),
    @"notify":                       @((props & CBCharacteristicPropertyNotify) != 0),
    @"indicate":                     @((props & CBCharacteristicPropertyIndicate) != 0),
    @"authenticated_signed_writes":  @((props & CBCharacteristicPropertyAuthenticatedSignedWrites) != 0),
    @"extended_properties":          @((props & CBCharacteristicPropertyExtendedProperties) != 0),
    @"notify_encryption_required":   @((props & CBCharacteristicPropertyNotifyEncryptionRequired) != 0),
    @"indicate_encryption_required": @((props & CBCharacteristicPropertyIndicateEncryptionRequired) != 0),
  };
}

- (NSDictionary*)toMtuSizeResponseProto:(CBPeripheral *)peripheral mtu:(uint32_t)mtu {
  // See: BmMtuSizeRequest
  return @{
    @"remote_id" : [[peripheral identifier] UUIDString],
    @"mtu" : @(mtu),
  };
}

//////////////////////////////////////////
// ██    ██ ████████  ██  ██       ███████ 
// ██    ██    ██     ██  ██       ██      
// ██    ██    ██     ██  ██       ███████ 
// ██    ██    ██     ██  ██            ██ 
//  ██████     ██     ██  ███████  ███████ 

- (void)log:(LogLevel)level format:(NSString *)format, ... {
  if(level <= _logLevel) {
    //va_list args;
    //va_start(args, format);
    //TODO: ログはFlutter側に送信する。
    [_methodChannel invokeMethod:@"Logger" arguments:format];
//    NSString* formattedMessage = [[NSString alloc] initWithFormat:format arguments:args];
    //NSLog(format, args);
    //va_end(args);
  }
}

- (uint32_t)getMtu:(CBPeripheral *)peripheral {
  if (@available(iOS 9.0, *)) {
    // Which type should we use? (issue #365)
    return (uint32_t)[peripheral maximumWriteValueLengthForType:CBCharacteristicWriteWithoutResponse];
  } else {
    // Fallback to minimum on earlier versions. (issue #364)
    return 20;
  }
}

@end

