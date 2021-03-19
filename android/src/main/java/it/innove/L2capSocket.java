package it.innove;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.RCTNativeAppEventEmitter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static android.content.ContentValues.TAG;

public class L2capSocket {

  private int percentage;

  private int remainingBytes = -1;

  private boolean isCanceled;

  private int chunkSize;

  private int blockStartByte;

  private byte[] imageFileData;

  private String filePath;

  private Peripheral peripheral;

  private Callback callback;

  private ReactContext reactContext;

  private BluetoothSocket bluetoothSocket;

  private OutputStream outputStream;

  private InputStream inputStream;

  public L2capSocket(Peripheral peripheral, String filePath, ReactContext reactContext, Callback callback) {
    this.peripheral = peripheral;
    this.filePath = filePath;
    this.reactContext = reactContext;
    this.callback  = callback;
  }

  public void execute() {
    new Thread( new Runnable() { @Override public void run() {
      try {
        setImageFilePath(filePath);
        openL2capBleSocket();
        readStreamingData();
      } catch (Exception ex) {
        Log.e(TAG, "L2CAP Socket Error - " + ex.getMessage());
        callback.invoke("ota failed");
        close();
      }
    }}).start();
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
        onProgress(progress);

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
    map.putInt("progress", progress);
    reactContext.getJSModule(RCTNativeAppEventEmitter.class).emit("BleManagerL2capDownloadProgress", map);
  }

  private void setImageFilePath(String url) {
    Log.i(TAG, "setImageFilePath - " + url);
    File file = new File(url);
    try {
        // create FileInputStream object
        FileInputStream fin = new FileInputStream(file);
        byte fileContent[] = new byte[(int) file.length()];
        // Reads up to certain bytes of data from this input stream into an array of bytes.
        fin.read(fileContent);
        fin.close();
        this.imageFileData = fileContent;
        Log.i(TAG, "File Length "+imageFileData.length);
    }
    catch (Exception ex) {
        // throw ex;
        ex.printStackTrace();
    }
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
