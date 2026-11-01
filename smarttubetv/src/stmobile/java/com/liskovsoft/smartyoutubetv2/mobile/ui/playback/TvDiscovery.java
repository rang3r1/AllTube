package com.liskovsoft.smartyoutubetv2.mobile.ui.playback;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.SystemClock;

import com.liskovsoft.sharedutils.mylogger.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Finds Google TV / Android TV / Chromecast devices on the Wi-Fi the way the official app does:
 * mDNS "_googlecast._tcp" discovery, then Cast v2 (TLS + protobuf, hand-rolled, no Cast SDK) to
 * start YouTube on the TV and ask it for its lounge screenId, so no TV code is needed.
 * ponytail: no DIAL, so TVs without Cast (older Samsung / LG) still pair with the TV code.
 */
final class TvDiscovery {
    private static final String TAG = TvDiscovery.class.getSimpleName();
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int SEARCH_MS = 2_500;
    private static final String YOUTUBE_APP_ID = "233637DE";
    private static final String NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String NS_RECEIVER = "urn:x-cast:com.google.cast.receiver";
    private static final String NS_MDX = "urn:x-cast:com.google.youtube.mdx";

    static final class Tv {
        final String name;
        final String host;
        final int port;

        Tv(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }

    private TvDiscovery() {
    }

    /** Cast devices on the Wi-Fi; blocks ~2.5 s plus resolving. Empty when none (or no Wi-Fi). */
    static List<Tv> find(Context context) {
        NsdManager nsd = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        List<NsdServiceInfo> services = Collections.synchronizedList(new ArrayList<>());
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(NsdServiceInfo service) {
                services.add(service);
            }

            @Override
            public void onStartDiscoveryFailed(String type, int error) {
                Log.d(TAG, "mDNS discovery failed: %s", error);
            }

            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onServiceLost(NsdServiceInfo service) { }
            @Override public void onStopDiscoveryFailed(String type, int error) { }
        };

        nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, listener);
        SystemClock.sleep(SEARCH_MS);
        try {
            nsd.stopServiceDiscovery(listener);
        } catch (IllegalArgumentException alreadyStopped) {
            // Discovery never started.
        }

        List<Tv> tvs = new ArrayList<>();
        for (NsdServiceInfo service : new ArrayList<>(services)) {
            NsdServiceInfo resolved = resolve(nsd, service);
            if (resolved == null || resolved.getHost() == null) {
                continue;
            }
            byte[] friendlyName = resolved.getAttributes().get("fn");
            String name = friendlyName != null ? new String(friendlyName, UTF8) : resolved.getServiceName();
            tvs.add(new Tv(name, resolved.getHost().getHostAddress(), resolved.getPort()));
        }
        return tvs;
    }

    /** NsdManager resolves one service at a time, so this blocks per service. */
    private static NsdServiceInfo resolve(NsdManager nsd, NsdServiceInfo service) {
        CountDownLatch done = new CountDownLatch(1);
        NsdServiceInfo[] result = new NsdServiceInfo[1];
        nsd.resolveService(service, new NsdManager.ResolveListener() {
            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                result[0] = info;
                done.countDown();
            }

            @Override
            public void onResolveFailed(NsdServiceInfo info, int error) {
                done.countDown();
            }
        });
        try {
            done.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result[0];
    }

    /** Starts YouTube on the TV (waking it, like the official app) and returns its lounge screenId. */
    static String screenId(Tv tv) throws Exception {
        try (SSLSocket socket = (SSLSocket) trustAll().getSocketFactory().createSocket(tv.host, tv.port)) {
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(out, "receiver-0", NS_CONNECTION, new JSONObject().put("type", "CONNECT"));
            send(out, "receiver-0", NS_RECEIVER, new JSONObject()
                    .put("type", "LAUNCH").put("appId", YOUTUBE_APP_ID).put("requestId", 1));

            String transportId = null;
            for (int i = 0; i < 50; i++) {
                String[] message = receive(in); // {sourceId, namespace, payload}
                JSONObject payload = new JSONObject(message[2]);
                String type = payload.optString("type");

                if ("PING".equals(type)) {
                    send(out, message[0], NS_HEARTBEAT, new JSONObject().put("type", "PONG"));
                } else if ("mdxSessionStatus".equals(type)) {
                    return payload.getJSONObject("data").getString("screenId");
                } else if (transportId == null && "RECEIVER_STATUS".equals(type)) {
                    JSONArray apps = payload.getJSONObject("status").optJSONArray("applications");
                    for (int j = 0; apps != null && j < apps.length(); j++) {
                        JSONObject app = apps.getJSONObject(j);
                        if (YOUTUBE_APP_ID.equals(app.optString("appId")) && app.has("transportId")) {
                            transportId = app.getString("transportId");
                            send(out, transportId, NS_CONNECTION, new JSONObject().put("type", "CONNECT"));
                            send(out, transportId, NS_MDX, new JSONObject().put("type", "getMdxSessionStatus"));
                        }
                    }
                }
            }
            throw new IOException("No screenId from " + tv.name);
        }
    }

    // CastMessage protobuf: 1 version=0, 2 source, 3 destination, 4 namespace, 5 payload type=0 (string), 6 payload.
    static void send(OutputStream out, String destination, String namespace, JSONObject payload) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        varint(message, 1 << 3);
        varint(message, 0);
        string(message, 2, "sender-0");
        string(message, 3, destination);
        string(message, 4, namespace);
        varint(message, 5 << 3);
        varint(message, 0);
        string(message, 6, payload.toString());

        // Length prefix and body in one write: Cast devices drop the connection when they arrive
        // in separate TLS records.
        byte[] body = message.toByteArray();
        int n = body.length;
        byte[] frame = new byte[4 + n];
        frame[0] = (byte) (n >>> 24);
        frame[1] = (byte) (n >>> 16);
        frame[2] = (byte) (n >>> 8);
        frame[3] = (byte) n;
        System.arraycopy(body, 0, frame, 4, n);
        out.write(frame);
        out.flush();
    }

    /** Returns {sourceId, namespace, payload} of the next message. */
    static String[] receive(DataInputStream in) throws IOException {
        int frameLength = in.readInt();
        if (frameLength < 0 || frameLength > 64 * 1024) {
            throw new IOException("Bad Cast frame length " + frameLength); // else a bad device could OOM the app
        }
        byte[] body = new byte[frameLength];
        in.readFully(body);

        String[] fields = new String[7];
        int[] pos = {0};
        while (pos[0] < body.length) {
            long key = readVarint(body, pos);
            int field = (int) (key >>> 3);
            if ((key & 7) == 2) {
                int length = (int) readVarint(body, pos);
                if (field < fields.length) {
                    fields[field] = new String(body, pos[0], length, UTF8);
                }
                pos[0] += length;
            } else {
                readVarint(body, pos);
            }
        }
        return new String[]{fields[2], fields[4], fields[6] != null ? fields[6] : "{}"};
    }

    private static void string(ByteArrayOutputStream out, int field, String value) {
        byte[] bytes = value.getBytes(UTF8);
        varint(out, field << 3 | 2);
        varint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void varint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static long readVarint(byte[] bytes, int[] pos) {
        long value = 0;
        for (int shift = 0; ; shift += 7) {
            byte b = bytes[pos[0]++];
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
    }

    /** Cast devices use self-signed certs; only the screenId is read over this LAN socket. */
    @SuppressLint({"TrustAllX509TrustManager", "CustomX509TrustManager"})
    private static SSLContext trustAll() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        return context;
    }
}
