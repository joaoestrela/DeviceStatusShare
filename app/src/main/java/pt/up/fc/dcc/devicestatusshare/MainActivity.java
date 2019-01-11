package pt.up.fc.dcc.devicestatusshare;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.security.keystore.KeyProperties;
import android.telephony.TelephonyManager;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {

    private Activity thisActivity = this;
    static SecureRandom secureRandom = new SecureRandom();
    static byte[] encrypt_iv = new byte[16];
    private static String groupName;
    private static String groupKey;
    private static SecretKey secretKey;

    private TextView messages;

    private MyDatagramReceiver myDatagramReceiver = null;
    private static final int UDP_SERVER_PORT = 2562;
    private static final int MAX_UDP_DATAGRAM_LEN = 512;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        groupName = intent.getStringExtra("groupName");
        groupKey = intent.getStringExtra("groupKey");
        if (groupKey == null || groupKey.isEmpty()) {
            KeyGenerator keyGen = null;
            try {
                keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES);
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
            keyGen.init(256);
            secretKey = keyGen.generateKey();
            if (secretKey != null) {
                groupKey = Base64.encodeToString(secretKey.getEncoded(), Base64.DEFAULT);
            }
        }
        else {
            groupName = intent.getStringExtra("groupName");
            groupKey = intent.getStringExtra("groupKey");
            byte[] encodedKey = Base64.decode(groupKey, Base64.DEFAULT);
            secretKey = new SecretKeySpec(encodedKey, 0, encodedKey.length, "AES");
        }
        Log.i("MainActivityLog", "GroupName: " + groupName);
        Log.i("MainActivityLog", "GroupKey: " + groupKey);
        setContentView(R.layout.activity_main);
        setTitle("Group: " + groupName);
        messages = findViewById(R.id.messages);
        Timer timer = new Timer();
        timer.schedule(new MyDatagramBroadcaster(), 5000, 10000);
        Button shareButton = findViewById(R.id.buttonShareGroup);
        shareButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JSONObject json = new JSONObject();
                try {
                    json.put("groupName", groupName);
                    json.put("groupKey", groupKey);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                //
                BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
                Bitmap bitmap = null;
                try {
                    bitmap = barcodeEncoder.encodeBitmap(json.toString(), BarcodeFormat.QR_CODE, 400, 400);
                } catch (WriterException e) {
                    e.printStackTrace();
                }
                //
                Dialog builder = new Dialog(thisActivity);
                builder.requestWindowFeature(Window.FEATURE_NO_TITLE);
                builder.getWindow().setBackgroundDrawable(
                        new ColorDrawable(android.graphics.Color.TRANSPARENT));
                                ImageView imageView = new ImageView(thisActivity);
                imageView.setImageBitmap(bitmap);
                builder.addContentView(imageView, new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                builder.show();
            }
        });
    }

    protected void onResume() {
        super.onResume();
        myDatagramReceiver = new MyDatagramReceiver();
        myDatagramReceiver.start();
    }

    protected void onPause() {
        super.onPause();
        myDatagramReceiver.kill();
    }

    private class MyDatagramBroadcaster extends TimerTask {
        @Override
        public void run() {
            JSONObject sendMessage = new JSONObject();
            try {
                sendMessage.put("AndroidID", getDeviceId());
                sendMessage.put("BatteryStatus", getBatteryPercentage(thisActivity));
            } catch (JSONException e) {
                e.printStackTrace();
            }
            try {
                secureRandom.nextBytes(encrypt_iv);
                byte[] mac = hmac(secretKey.getEncoded(),sendMessage.toString().getBytes("UTF-8"));
                byte[] sendMessageBytes = sendMessage.toString().getBytes("UTF-8");
                ByteBuffer byteBuffer1 = ByteBuffer.allocate(1 + mac.length + sendMessageBytes.length);
                byteBuffer1.put((byte) mac.length);
                byteBuffer1.put(mac);
                byteBuffer1.put(sendMessageBytes);
                byte[] messageWithMac = byteBuffer1.array();
                byte[] messageWithMacEncrypted = encrypt(secretKey.getEncoded(),messageWithMac);
                ByteBuffer byteBuffer2 = ByteBuffer.allocate(1+ encrypt_iv.length + messageWithMacEncrypted.length);
                byteBuffer2.put((byte) encrypt_iv.length);
                byteBuffer2.put(encrypt_iv);
                byteBuffer2.put(messageWithMacEncrypted);
                byte[] finalMessage = byteBuffer2.array();
                broadcastMessage(finalMessage);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private class MyDatagramReceiver extends Thread {
        private boolean bKeepRunning = true;
        private String lastMessage = "";

        public void run() {
            byte[] tempBytes = new byte[MAX_UDP_DATAGRAM_LEN];
            DatagramPacket packet = new DatagramPacket(tempBytes, tempBytes.length);
            try {
                DatagramSocket socket = new DatagramSocket(UDP_SERVER_PORT,InetAddress.getByName("0.0.0.0"));
                socket.setBroadcast(true);
                while (bKeepRunning) {
                    socket.receive(packet);
                    byte[] lmessage = new byte[packet.getLength()];
                    System.arraycopy(tempBytes, 0, lmessage, 0, lmessage.length);
                    ByteBuffer byteBuffer1 = ByteBuffer.wrap(lmessage);
                    int ivLength = (byteBuffer1.get());
                    if (ivLength != 16) {
                        throw new IllegalArgumentException("invalid iv length");
                    }
                    byte[] iv = new byte[ivLength];
                    byteBuffer1.get(iv);
                    byte[] messageWithMac = new byte[byteBuffer1.remaining()];
                    byteBuffer1.get(messageWithMac);
                    ByteBuffer byteBuffer2 = ByteBuffer.wrap(decrypt(secretKey.getEncoded(),iv,messageWithMac));
                    int macLength = (byteBuffer2.get());
                    if (macLength != 32) {
                        throw new IllegalArgumentException("invalid mac length");
                    }
                    byte[] mac = new byte[macLength];
                    byteBuffer2.get(mac);
                    byte[] message = new byte[byteBuffer2.remaining()];
                    byteBuffer2.get(message);
                    if(!veryfyMac(secretKey.getEncoded(),message,mac)) {
                        throw new IllegalArgumentException("invalid mac");
                    }
                    lastMessage = new String(message, "UTF-8");
                    runOnUiThread(updateTextMessages);
                }
                socket.close();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        String getLastMessage() {
            return lastMessage;
        }

        void kill() {
            bKeepRunning = false;
        }
    }

    private Runnable updateTextMessages = new Runnable() {
        public void run() {
            if (myDatagramReceiver == null) return;
            if (messages.getText().length() == 0)
                messages.setText(myDatagramReceiver.getLastMessage());
            else
                messages.setText(messages.getText() + "\n" + myDatagramReceiver.getLastMessage());
        }
    };

    private void broadcastMessage(byte[] sendData) {
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setBroadcast(true);
            InetAddress IPAddress = getBroadcastAddress();
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, UDP_SERVER_PORT);
            socket.send(sendPacket);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static int getBatteryPercentage(Context context) {
        IntentFilter iFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, iFilter);
        int level = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
        int scale = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : -1;
        float batteryPct = level / (float) scale;
        return (int) (batteryPct * 100);
    }

    private String getDeviceId() {
        String deviceId;
        final TelephonyManager mTelephony = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(thisActivity, new String[]{Manifest.permission.READ_PHONE_STATE}, 0);
        }
        if (mTelephony.getDeviceId() != null) {
            deviceId = mTelephony.getDeviceId();
        } else {
            deviceId = Settings.Secure.getString(getApplicationContext()
                    .getContentResolver(), Settings.Secure.ANDROID_ID);
        }
        return deviceId;
    }

    private static byte[] encrypt(byte[] key, byte[] message) throws Exception {
        SecretKeySpec skeySpec = new SecretKeySpec(key, "AES");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING");
        cipher.init(Cipher.ENCRYPT_MODE, skeySpec, new IvParameterSpec(encrypt_iv));
        return cipher.doFinal(message);
    }

    private static byte[] decrypt(byte[] key, byte[] iv, byte[] message) throws Exception {
        SecretKeySpec skeySpec = new SecretKeySpec(key, "AES");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING");
        cipher.init(Cipher.DECRYPT_MODE, skeySpec, new IvParameterSpec(iv));
        return cipher.doFinal(message);
    }

    private static byte[] hmac(byte[] key, byte[] message) throws Exception{
        SecretKeySpec skeySpec = new SecretKeySpec(key, "HmacSHA256");
        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        sha256_HMAC.init(skeySpec);
        return sha256_HMAC.doFinal(message);
    }

    private static boolean veryfyMac(byte[] key, byte[] message, byte[] mac) throws Exception{
        SecretKeySpec skeySpec = new SecretKeySpec(key, "HmacSHA256");
        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        sha256_HMAC.init(skeySpec);
        return (Arrays.equals(sha256_HMAC.doFinal(message),mac));
    }

    InetAddress getBroadcastAddress() throws IOException {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        DhcpInfo dhcp = wifi.getDhcpInfo();
        int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
        byte[] quads = new byte[4];
        for (int k = 0; k < 4; k++)
            quads[k] = (byte) ((broadcast >> k * 8) & 0xFF);
        return InetAddress.getByAddress(quads);
    }

}
