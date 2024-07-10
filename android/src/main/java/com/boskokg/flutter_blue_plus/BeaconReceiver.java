package com.boskokg.flutter_blue_plus;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.arch.core.util.Function;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.Identifier;
import org.altbeacon.beacon.MonitorNotifier;
import org.altbeacon.beacon.RangeNotifier;
import org.altbeacon.beacon.Region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

public class BeaconReceiver {
    private static final String TAG = "beacon_receiver_nex";
    private static final String IBEACON_FORMAT = "m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24";
    private static final String UUID = "D30A3941-35F9-D31A-215B-1EACF2DADB8B";

    private Activity activity;
    private BeaconManager beaconManager;
    private Region region;
    private Function<List<Map<String, String>>, Void> function;

    BeaconReceiver(Activity activity, Context context, Function<List<Map<String, String>>, Void> function){
        beaconManager = BeaconManager.getInstanceForApplication(context);
        beaconManager.getBeaconParsers().add(new BeaconParser().setBeaconLayout(IBEACON_FORMAT));
        beaconManager.bindInternal(beaconConsumer);
        this.activity = activity;
        this.function = function;
    }

    public boolean startBeaconReceiver(){
        try{
            Identifier uuid = Identifier.parse(UUID);
            region = new Region("all-beacons-region2", uuid, null, null);
            beaconManager.stopMonitoringBeaconsInRegion(region);
            beaconManager.stopRangingBeaconsInRegion(region);
            beaconManager.removeRangeNotifier(rangeNotifier);
            beaconManager.removeMonitorNotifier(monitorNotifier);

            beaconManager.removeAllRangeNotifiers();
            beaconManager.addMonitorNotifier(monitorNotifier);
            beaconManager.addRangeNotifier(rangeNotifier);
            beaconManager.startMonitoringBeaconsInRegion(region);

            return true;
        }catch (Exception e){
            return false;
        }
    }

    public boolean stopBeaconReceiver(){
        try{
            beaconManager.stopMonitoring(region);
            beaconManager.stopRangingBeacons(region);
            beaconManager.removeAllMonitorNotifiers();
            beaconManager.removeAllRangeNotifiers();

            return true;
        }catch (Exception e){
            return false;
        }
    }

    private final MonitorNotifier monitorNotifier = new MonitorNotifier() {
        @Override
        public void didEnterRegion(Region region) {
            beaconManager.startRangingBeacons(region);
        }

        @Override
        public void didExitRegion(Region region) {
            beaconManager.stopRangingBeacons(region);
        }

        @Override
        public void didDetermineStateForRegion(int state, Region region) {

        }
    };

    private final RangeNotifier rangeNotifier = new RangeNotifier() {
        @Override
        public void didRangeBeaconsInRegion(Collection<Beacon> beacons, Region region) {
            try{
                if (!beacons.isEmpty()) {
                    List<Map<String, String>> result = new ArrayList<>();
                    for(Beacon beacon: beacons){
                        Map<String, String> tmpResult = new HashMap<>();
                        tmpResult.put("uuid",     beacon.getId1().toString());
                        tmpResult.put("major",    beacon.getId2().toString());
                        tmpResult.put("minor",    beacon.getId3().toString());
                        tmpResult.put("Distance", String.valueOf(beacon.getDistance()));
                        tmpResult.put("RSSI",     String.valueOf(beacon.getRssi()));
                        tmpResult.put("TxPower",  String.valueOf(beacon.getTxPower()));
                        result.add(tmpResult);
                    }

                    function.apply(result);
                }
            }catch (Exception e){
            }
        }
    };

    private final BeaconConsumer beaconConsumer = new BeaconConsumer() {
        @Override
        public void onBeaconServiceConnect() {
        }

        @Override
        public Context getApplicationContext() {
            return activity;
        }

        @Override
        public void unbindService(ServiceConnection connection) {
            activity.unbindService(connection);
        }

        @Override
        public boolean bindService(Intent intent, ServiceConnection connection, int mode) {
            return activity.bindService(intent, connection, mode);
        }
    };
}
