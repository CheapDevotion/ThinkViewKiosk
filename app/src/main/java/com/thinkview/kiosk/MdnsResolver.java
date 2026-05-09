package com.thinkview.kiosk;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;

/**
 * Minimal mDNS A-record resolver for Android 8.1 (which has no native .local resolver).
 *
 * Sends a multicast DNS query to 224.0.0.251:5353 and parses the first A record from the
 * response. No service discovery -- just hostname -> IPv4. Acquires a Wi-Fi multicast lock for
 * the duration of the lookup; without it, the kernel won't deliver multicast packets to our
 * socket on most Android devices.
 *
 * Caller is responsible for running this off the main thread.
 */
public class MdnsResolver {
    private static final String TAG = "ThinkViewKiosk";
    private static final String MDNS_GROUP = "224.0.0.251";
    private static final int MDNS_PORT = 5353;

    public static String resolve(Context ctx, String hostname, int timeoutMs) {
        WifiManager.MulticastLock lock = null;
        MulticastSocket socket = null;
        try {
            WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                lock = wifi.createMulticastLock("kiosk-mdns");
                lock.setReferenceCounted(false);
                lock.acquire();
            }

            socket = new MulticastSocket();
            InetAddress group = InetAddress.getByName(MDNS_GROUP);

            byte[] query = buildQuery(hostname);
            DatagramPacket queryPacket = new DatagramPacket(query, query.length, group, MDNS_PORT);
            socket.send(queryPacket);

            byte[] buf = new byte[1500];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                int remaining = (int) (deadline - System.currentTimeMillis());
                if (remaining <= 0) break;
                socket.setSoTimeout(remaining);
                try {
                    socket.receive(response);
                } catch (SocketTimeoutException e) {
                    break;
                }
                String ip = parseFirstAnswer(response.getData(), response.getLength());
                if (ip != null) return ip;
                // Other mDNS responses are floating around (services advertising themselves);
                // keep listening until we see one matching our query, or timeout expires.
            }
            return null;
        } catch (Exception ex) {
            Log.w(TAG, "mDNS resolve(" + hostname + ") failed: " + ex.getMessage());
            return null;
        } finally {
            if (socket != null) socket.close();
            if (lock != null) {
                try { lock.release(); } catch (Exception ignore) {}
            }
        }
    }

    private static byte[] buildQuery(String hostname) throws java.io.IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        // DNS header
        dos.writeShort(0);     // ID -- mDNS uses 0
        dos.writeShort(0);     // Flags -- standard query
        dos.writeShort(1);     // QDCOUNT
        dos.writeShort(0);     // ANCOUNT
        dos.writeShort(0);     // NSCOUNT
        dos.writeShort(0);     // ARCOUNT
        // Question section: encoded hostname + QTYPE + QCLASS
        for (String label : hostname.split("\\.")) {
            byte[] bytes = label.getBytes("UTF-8");
            if (bytes.length == 0 || bytes.length > 63) continue;
            dos.writeByte(bytes.length);
            dos.write(bytes);
        }
        dos.writeByte(0);      // root label terminator
        dos.writeShort(1);     // QTYPE = A
        dos.writeShort(1);     // QCLASS = IN
        return baos.toByteArray();
    }

    /// Returns the first A-record IPv4 address from the DNS response, or null.
    private static String parseFirstAnswer(byte[] data, int length) {
        if (length < 12) return null;
        int ancount = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        if (ancount <= 0) return null;
        int qdcount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        int offset = 12;

        // Skip questions
        for (int i = 0; i < qdcount; i++) {
            offset = skipName(data, offset, length);
            offset += 4; // QTYPE + QCLASS
            if (offset > length) return null;
        }

        // Walk answers
        for (int i = 0; i < ancount; i++) {
            offset = skipName(data, offset, length);
            if (offset + 10 > length) return null;
            int type = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            int rdLength = ((data[offset + 8] & 0xFF) << 8) | (data[offset + 9] & 0xFF);
            offset += 10;
            if (offset + rdLength > length) return null;
            if (type == 1 /* A */ && rdLength == 4) {
                return (data[offset] & 0xFF) + "." + (data[offset + 1] & 0xFF) + "."
                        + (data[offset + 2] & 0xFF) + "." + (data[offset + 3] & 0xFF);
            }
            offset += rdLength;
        }
        return null;
    }

    private static int skipName(byte[] data, int offset, int length) {
        while (offset < length) {
            int len = data[offset] & 0xFF;
            if (len == 0) return offset + 1;
            if ((len & 0xC0) == 0xC0) return offset + 2; // compression pointer
            offset += len + 1;
        }
        return offset;
    }
}
