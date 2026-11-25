package local.cc3.obd.android;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.content.Intent;
import android.os.Build;

/** API33 immutable-value operations with narrowly retained Android10 fallbacks. */
final class BluetoothCompat {
    @SuppressWarnings("deprecation")
    static BluetoothDevice discovered(Intent intent){
        return Build.VERSION.SDK_INT>=33?intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice.class)
                :intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }
    @SuppressWarnings("deprecation")
    static boolean subscribe(BluetoothGatt gatt,BluetoothGattDescriptor descriptor,byte[] value){
        if(Build.VERSION.SDK_INT>=33)return gatt.writeDescriptor(descriptor,value)==0;
        descriptor.setValue(value);return gatt.writeDescriptor(descriptor);
    }
    @SuppressWarnings("deprecation")
    static boolean write(BluetoothGatt gatt,BluetoothGattCharacteristic characteristic,byte[] value,int type){
        if(Build.VERSION.SDK_INT>=33)return gatt.writeCharacteristic(characteristic,value,type)==0;
        characteristic.setWriteType(type);characteristic.setValue(value);return gatt.writeCharacteristic(characteristic);
    }
    private BluetoothCompat(){ }
}
