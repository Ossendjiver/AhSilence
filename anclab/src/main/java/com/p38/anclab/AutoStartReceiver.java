package com.p38.anclab;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.p38.anclab.settings.AppSettings;

import java.util.Locale;

/** Starts the prepared profile when the user-selected Bluetooth/USB endpoint connects. */
public final class AutoStartReceiver extends BroadcastReceiver {
    @Override @SuppressLint("MissingPermission") public void onReceive(Context context,Intent intent){AppSettings settings=new AppSettings(context);if(!settings.autoStartEnabled())return;String wanted=settings.autoStartDevice().trim().toLowerCase(Locale.US);if(wanted.isEmpty())return;String connected=connectedName(intent).toLowerCase(Locale.US);if(connected.isEmpty()||(!connected.contains(wanted)&&!wanted.contains(connected)))return;Intent service=new Intent(context,AncMediaService.class).setAction(AncMediaService.ACTION_START);if(Build.VERSION.SDK_INT>=26)context.startForegroundService(service);else context.startService(service);}
    private static String connectedName(Intent intent){if(intent==null)return "";try{BluetoothDevice device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);if(device!=null)return String.valueOf(device.getName())+" "+device.getAddress();}catch(Throwable ignored){}try{UsbDevice device=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);if(device!=null)return String.valueOf(device.getProductName())+" "+String.valueOf(device.getManufacturerName())+" "+device.getDeviceName();}catch(Throwable ignored){}return "";}
}
