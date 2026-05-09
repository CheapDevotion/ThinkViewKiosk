package com.thinkview.kiosk;

import android.content.Context;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Minimal client for Home Assistant's WebSocket API. Connects to {@code ws://host:8123/api/websocket},
 * authenticates with a long-lived access token, subscribes to a state-changed trigger on the
 * configured alarm entity, and invokes {@link Listener#onAlarmTriggered} when the entity goes
 * to state {@code triggered}.
 *
 * Auto-reconnects with exponential backoff (max 60s) on disconnect. Doesn't try to be a general
 * HA WebSocket client -- only the slice we need for the alarm integration.
 *
 * Top-level class (not nested) for d8's sake.
 */
class HaWebSocketClient {
    private static final String TAG = "ThinkViewKiosk/Alarm";

    interface Listener {
        void onAlarmTriggered(String triggerInfo);
        void onConnectionState(boolean connected);
        /// Fired when an HA `kiosk_command` event arrives addressed to this device (or "all").
        void onCommandReceived(String command, String value);
    }

    private final OkHttpClient http;
    private final String wsUrl;
    private final String accessToken;
    private final String alarmEntity;
    private volatile String deviceName;
    private final Listener listener;

    private volatile WebSocket socket;
    private volatile boolean shutdown = false;
    private int subscriptionId = 1;
    private long backoffMs = 1000;
    // Track whether we've already fired the alarm for the current trigger episode -- only re-arm
    // the firing once HA reports the alarm is no longer in 'triggered' state.
    private boolean alreadyFiredCurrentTrigger = false;

    HaWebSocketClient(Context context, String haUrl, String accessToken, String alarmEntity, String deviceName, Listener listener) {
        this.wsUrl = toWebSocketUrl(haUrl);
        this.accessToken = accessToken;
        this.alarmEntity = alarmEntity;
        this.deviceName = deviceName == null ? "" : deviceName;
        this.listener = listener;
        // Custom Dns: routes *.local through MdnsResolver since Android 8.1's stock resolver
        // has no mDNS support. Without this, the WebSocket loops forever on
        // "Unable to resolve host 'homeassistant.local'".
        this.http = new OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // long-poll WebSocket
                .dns(new MdnsDns(context))
                .build();
    }

    /// Update the device name used for filtering kiosk_command events. Called when the user
    /// renames the device via a remote set_display_name command -- AlarmListenerService stays
    /// connected, no need to tear down the websocket.
    void setDeviceName(String newName) {
        this.deviceName = newName == null ? "" : newName;
        Log.i(TAG, "device name updated to '" + this.deviceName + "'");
    }

    private static String toWebSocketUrl(String haUrl) {
        // HA dashboard URL might be http://host:8123/lovelace/foo -- strip path and switch
        // scheme. The /api/websocket endpoint lives at the root of HA's web server.
        String base = haUrl;
        int schemeEnd = base.indexOf("://");
        if (schemeEnd > 0) {
            String scheme = base.substring(0, schemeEnd);
            String wsScheme = scheme.equalsIgnoreCase("https") ? "wss" : "ws";
            int pathStart = base.indexOf('/', schemeEnd + 3);
            String hostPort = (pathStart >= 0) ? base.substring(schemeEnd + 3, pathStart) : base.substring(schemeEnd + 3);
            return wsScheme + "://" + hostPort + "/api/websocket";
        }
        return "ws://" + base + "/api/websocket";
    }

    void connect() {
        if (shutdown) return;
        Log.i(TAG, "connecting to " + wsUrl);
        Request req = new Request.Builder().url(wsUrl).build();
        socket = http.newWebSocket(req, new HaWsListener(this));
    }

    void shutdown() {
        shutdown = true;
        if (socket != null) {
            try { socket.close(1000, "shutdown"); } catch (Exception ignore) {}
            socket = null;
        }
    }

    void onOpen() {
        Log.i(TAG, "websocket open");
    }

    void onTextMessage(String text) {
        try {
            JsonObject msg = JsonParser.parseString(text).getAsJsonObject();
            String type = msg.has("type") ? msg.get("type").getAsString() : "";
            switch (type) {
                case "auth_required":
                    sendAuth();
                    break;
                case "auth_ok":
                    listener.onConnectionState(true);
                    backoffMs = 1000; // reset backoff on successful auth
                    sendSubscribe();
                    sendSubscribeCommands();
                    break;
                case "auth_invalid":
                    Log.w(TAG, "HA auth_invalid -- check long-lived access token");
                    listener.onConnectionState(false);
                    break;
                case "event":
                    handleEvent(msg);
                    break;
                case "result":
                    if (msg.has("success") && !msg.get("success").getAsBoolean()) {
                        Log.w(TAG, "HA result error: " + text);
                    }
                    break;
            }
        } catch (Exception ex) {
            Log.w(TAG, "ws message parse error: " + ex.getMessage());
        }
    }

    void onClosing(int code, String reason) {
        Log.i(TAG, "websocket closing: " + code + " " + reason);
        listener.onConnectionState(false);
    }

    void onClosed(int code, String reason) {
        Log.i(TAG, "websocket closed: " + code + " " + reason);
        listener.onConnectionState(false);
        if (!shutdown) scheduleReconnect();
    }

    void onFailure(Throwable t) {
        Log.w(TAG, "websocket failure: " + t.getMessage());
        listener.onConnectionState(false);
        if (!shutdown) scheduleReconnect();
    }

    private void scheduleReconnect() {
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, 60_000L);
        Log.i(TAG, "reconnecting in " + delay + "ms");
        new Thread(new ReconnectTask(this, delay), "ha-ws-reconnect").start();
    }

    void doReconnect() {
        if (!shutdown) connect();
    }

    private void sendAuth() {
        JsonObject m = new JsonObject();
        m.addProperty("type", "auth");
        m.addProperty("access_token", accessToken);
        socket.send(m.toString());
    }

    private void sendSubscribe() {
        // subscribe_trigger fires whenever the alarm entity transitions state -- we filter to
        // 'triggered' below.
        JsonObject m = new JsonObject();
        m.addProperty("id", subscriptionId++);
        m.addProperty("type", "subscribe_trigger");
        JsonObject trigger = new JsonObject();
        trigger.addProperty("platform", "state");
        trigger.addProperty("entity_id", alarmEntity);
        m.add("trigger", trigger);
        socket.send(m.toString());
        Log.i(TAG, "subscribed to state changes for " + alarmEntity);
    }

    /// Subscribe to HA's event bus for our custom `kiosk_command` event. Lets you remotely
    /// disable a device's siren, change the dashboard URL, etc. via an HA automation that
    /// fires an event with data like {"device": "Living Room", "command": "set_url", "value": "..."}.
    /// Use device "all" to broadcast.
    private void sendSubscribeCommands() {
        JsonObject m = new JsonObject();
        m.addProperty("id", subscriptionId++);
        m.addProperty("type", "subscribe_events");
        m.addProperty("event_type", "kiosk_command");
        socket.send(m.toString());
        Log.i(TAG, "subscribed to kiosk_command events as '" + deviceName + "'");
    }

    private void handleEvent(JsonObject msg) {
        try {
            JsonObject event = msg.getAsJsonObject("event");
            // subscribe_events delivers {event: {event_type, data, ...}}; subscribe_trigger
            // delivers {event: {variables: {trigger: {...}}, ...}}. The presence of a top-level
            // "event_type" tells us which.
            if (event.has("event_type") && "kiosk_command".equals(event.get("event_type").getAsString())) {
                handleCommandEvent(event);
                return;
            }
            JsonObject variables = event.getAsJsonObject("variables");
            JsonObject trigger = variables.getAsJsonObject("trigger");
            JsonObject toState = trigger.getAsJsonObject("to_state");
            String state = toState.get("state").getAsString();
            String entityId = toState.has("entity_id") ? toState.get("entity_id").getAsString() : alarmEntity;

            if ("triggered".equals(state)) {
                if (alreadyFiredCurrentTrigger) {
                    Log.i(TAG, "alarm still triggered (debounced)");
                    return;
                }
                alreadyFiredCurrentTrigger = true;
                String info = describeTrigger(entityId, toState);
                Log.w(TAG, "ALARM TRIGGERED: " + info);
                listener.onAlarmTriggered(info);
            } else {
                // Reset firing latch when alarm goes back to a non-triggered state.
                if (alreadyFiredCurrentTrigger) {
                    Log.i(TAG, "alarm cleared (state=" + state + ")");
                    alreadyFiredCurrentTrigger = false;
                }
            }
        } catch (Exception ex) {
            Log.w(TAG, "event parse error: " + ex.getMessage());
        }
    }

    private void handleCommandEvent(JsonObject event) {
        try {
            JsonObject data = event.getAsJsonObject("data");
            if (data == null) return;
            String target  = data.has("device")  ? data.get("device").getAsString()  : "";
            String command = data.has("command") ? data.get("command").getAsString() : "";
            String value   = data.has("value")   ? data.get("value").getAsString()   : "";
            // Filter: only handle commands targeting us or the broadcast keyword "all".
            boolean forUs = "all".equalsIgnoreCase(target) || deviceName.equalsIgnoreCase(target);
            if (!forUs) {
                Log.i(TAG, "ignoring kiosk_command for '" + target + "' (we are '" + deviceName + "')");
                return;
            }
            if (command.isEmpty()) return;
            Log.i(TAG, "kiosk_command: " + command + " value='" + value + "'");
            listener.onCommandReceived(command, value);
        } catch (Exception ex) {
            Log.w(TAG, "command event parse error: " + ex.getMessage());
        }
    }

    /// Builds a human-readable description of what triggered the alarm. Prefers Alarmo's
    /// last_triggered_sensors array (specific entity that tripped), then falls back to the
    /// HA built-in panel's changed_by attribute, then the entity id itself.
    private static String describeTrigger(String entityId, JsonObject toState) {
        if (!toState.has("attributes")) return entityId;
        JsonObject attrs = toState.getAsJsonObject("attributes");

        // Alarmo: last_triggered_sensors is an array of entity_ids that caused the trigger.
        if (attrs.has("last_triggered_sensors")) {
            JsonElement el = attrs.get("last_triggered_sensors");
            if (el.isJsonArray()) {
                JsonArray sensors = el.getAsJsonArray();
                if (sensors.size() > 0) {
                    StringBuilder sb = new StringBuilder("Triggered by ");
                    for (int i = 0; i < sensors.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(sensors.get(i).getAsString());
                    }
                    return sb.toString();
                }
            }
        }
        // HA built-in alarm panel: changed_by carries the entity that disarmed/armed/triggered.
        if (attrs.has("changed_by") && !attrs.get("changed_by").isJsonNull()) {
            return "Triggered by " + attrs.get("changed_by").getAsString();
        }
        return entityId;
    }
}
