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
import java.util.Locale;

/**
 * Minimal mDNS A-record resolver for Android 8.1 (which has no native .local resolver).
 *
 * Sends a multicast DNS query to 224.0.0.251:5353 and walks the response for an A record whose
 * NAME matches what we asked for. Without that name match, librespot's Spotify Connect Zeroconf
 * advertisements (also flying around 224.0.0.251) trick us into returning the kiosk's own IP.
 *
 * Acquires a Wi-Fi multicast lock for the duration of the lookup; without it, the kernel won't
 * deliver multicast packets to our socket on most Android devices.
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
                String ip = findMatchingAnswer(response.getData(), response.getLength(), hostname);
                if (ip != null) return ip;
                // Otherwise: response was for a different hostname (typical librespot Zeroconf
                // chatter). Keep listening for one that matches what we asked.
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
        // QCLASS = IN (0x0001) with the QU bit (high bit) set -- requests unicast response back
        // to our ephemeral port. RFC 6762 §5.4. Avahi/HA's mDNS responder honors this and
        // sidesteps the multicast firehose librespot is contributing to.
        dos.writeShort(0x8001);
        return baos.toByteArray();
    }

    /// Walks the response for an A record whose NAME matches {@code wantedHost}. Returns the
    /// IP as a dotted-quad string, or null if no matching answer is in this packet.
    private static String findMatchingAnswer(byte[] data, int length, String wantedHost) {
        if (length < 12) return null;
        int qdcount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        int ancount = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        if (ancount <= 0) return null;
        int offset = 12;

        // Skip questions (some responders echo, some don't).
        for (int i = 0; i < qdcount; i++) {
            offset = skipName(data, offset, length);
            offset += 4; // QTYPE + QCLASS
            if (offset > length) return null;
        }

        String wantedLower = wantedHost.toLowerCase(Locale.ROOT);

        // Walk answers
        for (int i = 0; i < ancount; i++) {
            StringBuilder name = new StringBuilder();
            offset = decodeName(data, offset, length, name);
            if (offset < 0 || offset + 10 > length) return null;
            int type = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            // mDNS uses the high bit of class for the cache-flush flag; mask it off before
            // comparing if we ever care -- we don't here, just skip past CLASS.
            int rdLength = ((data[offset + 8] & 0xFF) << 8) | (data[offset + 9] & 0xFF);
            offset += 10;
            if (offset + rdLength > length) return null;
            if (type == 1 /* A */ && rdLength == 4) {
                String thisName = name.toString().toLowerCase(Locale.ROOT);
                if (thisName.equals(wantedLower)) {
                    return (data[offset] & 0xFF) + "." + (data[offset + 1] & 0xFF) + "."
                            + (data[offset + 2] & 0xFF) + "." + (data[offset + 3] & 0xFF);
                }
            }
            offset += rdLength;
        }
        return null;
    }

    /// Decodes the DNS NAME at {@code offset} into {@code out} as a dotted hostname, handling
    /// 0xC0 compression pointers (which can chain). Returns the offset of the byte AFTER the
    /// name field at the original position (NOT after the chased pointer's target). Returns
    /// -1 on malformed input.
    private static int decodeName(byte[] data, int offset, int length, StringBuilder out) {
        int returnOffset = -1; // offset to return if we follow a pointer
        int hops = 0;
        while (true) {
            if (offset < 0 || offset >= length) return -1;
            int len = data[offset] & 0xFF;
            if (len == 0) {
                int end = offset + 1;
                return returnOffset >= 0 ? returnOffset : end;
            }
            if ((len & 0xC0) == 0xC0) {
                if (offset + 1 >= length) return -1;
                if (returnOffset < 0) returnOffset = offset + 2;
                int ptr = ((len & 0x3F) << 8) | (data[offset + 1] & 0xFF);
                offset = ptr;
                if (++hops > 32) return -1; // pointer-loop guard
                continue;
            }
            offset++;
            if (offset + len > length) return -1;
            if (out.length() > 0) out.append('.');
            for (int i = 0; i < len; i++) {
                out.append((char) (data[offset + i] & 0xFF));
            }
            offset += len;
        }
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
