package it.innove;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.WritableMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static android.content.ContentValues.TAG;

public class L2capSocket implements Runnable {

  private int percentage;

  private int remainingBytes = -1;

  private boolean isCanceled;

  private int chunkSize;

  private int blockStartByte;

  private byte[] imageFileData;

  private Peripheral peripheral;

  private Callback callback;

  private BluetoothSocket bluetoothSocket;

  private OutputStream outputStream;

  private InputStream inputStream;

  protected Thread workerThread;

  private int threadPriority = Thread.NORM_PRIORITY;

  public L2capSocket(Peripheral peripheral, byte[] imageFileData, Callback callback) {
    this.peripheral = peripheral;
    this.imageFileData = imageFileData;
    this.callback  = callback;
  }

  public void execute() {
    workerThread = new Thread(this);
    workerThread.setPriority(threadPriority);
    workerThread.start();
  }

  @Override
  public void run() {
    try {
      openL2capBleSocket();
      readStreamingData();
    } catch (Exception ex) {
      Log.e(TAG, "L2CAP Socket Error - " + ex.getMessage());
      callback.invoke("ota failed");
      this.close();
    }
  }

  private void openL2capBleSocket() throws InvocationTargetException, NoSuchMethodException, InstantiationException, IOException, IllegalAccessException {
    Constructor<BluetoothSocket> constructor;
    try {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        constructor = BluetoothSocket.class.getDeclaredConstructor(int.class, int.class,
                boolean.class, boolean.class, BluetoothDevice.class, int.class, ParcelUuid.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        bluetoothSocket = constructor.newInstance(BluetoothSocket.TYPE_L2CAP, -1, false, false, peripheral.getDevice(), 0x20000 | 0x81, null, false, false);
        Log.i(TAG, "LE-COC: created");
      } else {
        bluetoothSocket = peripheral.getDevice().createInsecureL2capChannel(0x81);
      }

      bluetoothSocket.connect();
      Log.i(TAG, "LE-COC: connected");
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        chunkSize = bluetoothSocket.getMaxTransmitPacketSize() - 3;
      }

      Log.i(TAG, "LE-COC: chunkSize " + chunkSize);

      outputStream = bluetoothSocket.getOutputStream();

      inputStream = bluetoothSocket.getInputStream();

      sendImageDataToDevice(0);
    } catch (Exception ex) {
      Log.e(TAG, "L2CAP BluetoothSocket Error - " + ex.getMessage());
      throw ex;
    }
  }

  private void readStreamingData() throws IOException {
    try {
      Log.i(TAG, "Read Streaming data started");
      int count;
      byte buffer[] = new byte[1024];
      boolean status = true;
      while ((status == true) && (count = inputStream.read(buffer)) >= 0) {
        Log.d(TAG, "LE-COC: received count " + count);
        status = sendImageDataToDevice(count);
      }
      Log.i(TAG, "After End of InputStream");
    } catch (IOException ex) {
      Log.e(TAG, "L2CAP while reading data - " + ex.getMessage());
      throw ex;
    }
  }

  private boolean sendImageDataToDevice(int count) throws IOException {

    Log.d(TAG, "No of Bytes completed - " + count);

    if (remainingBytes == 0) {
      // doStepSendEndCommand();
      callback.invoke(null, "success");
      this.close();
      return false;
    }

    int progress = 0;
    percentage += 4;

    try {
      while (percentage >= progress) {

        if (isCanceled) {
          break;
        }

        Log.d(TAG, "LE-COC: blockStartByte " + blockStartByte);

        int blockSize = Math.min(chunkSize, imageFileData.length - blockStartByte);

        outputStream.write(imageFileData, blockStartByte, blockSize);
        blockStartByte += blockSize;

        if (blockStartByte < 1024) {
          progress = (blockStartByte * 100) / (imageFileData.length);
        } else {
          int x = (blockStartByte - 1024) / 512;
          x = x * 512;
          progress = (x * 100) / (imageFileData.length);
        }
        Log.i(TAG, "Progress bar value " + progress);
        this.onProgress(progress);

        remainingBytes = imageFileData.length - blockStartByte;
        Log.i(TAG, "remainingBytes: " + remainingBytes);

        if (remainingBytes == 0) {
          Log.i(TAG, "LE-COC: data load complete");
          break;
        }
      }
    } catch (Exception ex) {
      Log.e(TAG, "L2CAP while sending data - " + ex.getMessage());
      throw ex;
    }
    return true;
  }

  private void onProgress(int progress) {
    WritableMap map = Arguments.createMap();
    map.putString("peripheral", peripheral.getDevice().getAddress());
    map.putInt("DOWNLOAD_PROGRESS", progress);
    this.peripheral.sendEvent("BleManagerL2capDownloadProgress", map);
  }

  private void close() {
    try {
      Thread.sleep(300);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    if (inputStream != null) {
      try {
        inputStream.close();
      } catch (Exception e) {
        e.printStackTrace();
      }
      inputStream = null;
    }

    if (outputStream != null) {
      try {
        outputStream.close();
      } catch (Exception e) {
        e.printStackTrace();
      }
      outputStream = null;
    }

    if (bluetoothSocket != null) {
      try {
        bluetoothSocket.close();
      } catch (Exception e) {
        e.printStackTrace();
      }
      bluetoothSocket = null;
    }
  }
}
