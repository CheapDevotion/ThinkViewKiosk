package com.thinkview.kiosk;

import android.content.Context;
import android.util.Log;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;

import okhttp3.Dns;

/**
 * OkHttp {@link Dns} that resolves {@code *.local} hostnames via {@link MdnsResolver}, falling
 * through to the system resolver for everything else.
 *
 * Android 8.1 has no native mDNS resolver, so OkHttp's default DNS path
 * (InetAddress.getByName) returns "Unable to resolve host" for any {@code .local} name. The
 * dashboard load avoids this by going through MdnsResolver explicitly in MainActivity, but
 * the alarm WebSocket and any other OkHttp client never got that fix -- it would loop forever
 * on backoff with `homeassistant.local` unable to resolve. This wires MdnsResolver into the
 * OkHttp resolution path so all callers benefit transparently.
 */
class MdnsDns implements Dns {
    private static final String TAG = "ThinkViewKiosk/MdnsDns";
    private static final int RESOLVE_TIMEOUT_MS = 4000;

    private final Context appContext;

    MdnsDns(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        if (hostname == null) throw new UnknownHostException("null hostname");
        if (hostname.toLowerCase().endsWith(".local")) {
            String ip = MdnsResolver.resolve(appContext, hostname, RESOLVE_TIMEOUT_MS);
            if (ip == null) {
                throw new UnknownHostException("mDNS lookup failed for " + hostname);
            }
            Log.i(TAG, "mDNS " + hostname + " -> " + ip);
            // InetAddress.getByName on a dotted-quad just parses; no DNS hit.
            return Collections.singletonList(InetAddress.getByName(ip));
        }
        return Dns.SYSTEM.lookup(hostname);
    }
}
