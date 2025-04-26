package com.theta360.pluginapplication;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.net.URI;
import java.net.URISyntaxException;

import tech.gusavila92.websocketclient.WebSocketClient;

public class SignalingClient {
    private static final String TAG = "SignalingClient";
    private final String serverUrl;
    private final Callback callback;
    private WebSocketClient webSocketClient;
    private final Handler handler;
    private boolean isConnected = false;

    public interface Callback {
        void onOfferReceived(SessionDescription sdp);
        void onAnswerReceived(SessionDescription sdp);
        void onIceCandidateReceived(IceCandidate candidate);
        void onSignalingConnected();
        void onSignalingDisconnected();
    }

    public SignalingClient(String serverUrl, Callback callback) {
        this.serverUrl = serverUrl;
        this.callback = callback;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void connect() {
        if (webSocketClient != null && isConnected) {
            Log.d(TAG, "Already connected to signaling server");
            return;
        }

        try {
            URI uri = new URI(serverUrl);
            createWebSocketClient(uri);
            webSocketClient.connect();
        } catch (URISyntaxException e) {
            Log.e(TAG, "Invalid server URL: " + e.getMessage());
        }
    }

    public void disconnect() {
        if (webSocketClient != null) {
            webSocketClient.close();
            webSocketClient = null;
            isConnected = false;
        }
    }

    private void createWebSocketClient(URI uri) {
        webSocketClient = new WebSocketClient(uri) {
            @Override
            public void onOpen() {
                Log.d(TAG, "Connected to signaling server");
                isConnected = true;
                handler.post(callback::onSignalingConnected);
            }

            @Override
            public void onTextReceived(String message) {
                Log.d(TAG, "Message received: " + message);
                try {
                    JSONObject jsonObject = new JSONObject(message);
                    String type = jsonObject.getString("type");

                    switch (type) {
                        case "offer":
                            String offerSdp = jsonObject.getString("sdp");
                            SessionDescription offer = new SessionDescription(
                                    SessionDescription.Type.OFFER, offerSdp);
                            handler.post(() -> callback.onOfferReceived(offer));
                            break;
                        case "answer":
                            String answerSdp = jsonObject.getString("sdp");
                            SessionDescription answer = new SessionDescription(
                                    SessionDescription.Type.ANSWER, answerSdp);
                            handler.post(() -> callback.onAnswerReceived(answer));
                            break;
                        case "candidate":
                            JSONObject candidateJson = jsonObject.getJSONObject("candidate");
                            String sdpMid = candidateJson.getString("sdpMid");
                            int sdpMLineIndex = candidateJson.getInt("sdpMLineIndex");
                            String sdp = candidateJson.getString("candidate");
                            IceCandidate candidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
                            handler.post(() -> callback.onIceCandidateReceived(candidate));
                            break;
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to parse message: " + e.getMessage());
                }
            }

            @Override
            public void onBinaryReceived(byte[] data) {
                Log.d(TAG, "Binary data received");
            }

            @Override
            public void onPingReceived(byte[] data) {
                Log.d(TAG, "Ping received");
            }

            @Override
            public void onPongReceived(byte[] data) {
                Log.d(TAG, "Pong received");
            }

            @Override
            public void onException(Exception e) {
                Log.e(TAG, "WebSocket exception: " + e.getMessage());
            }

            @Override
            public void onCloseReceived() {
                Log.d(TAG, "Connection closed");
                isConnected = false;
                handler.post(callback::onSignalingDisconnected);
            }
        };
    }

    public void sendOffer(SessionDescription sessionDescription) {
        if (webSocketClient != null && isConnected) {
            try {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("type", "offer");
                jsonObject.put("sdp", sessionDescription.description);
                webSocketClient.send(jsonObject.toString());
                Log.d(TAG, "Offer sent");
            } catch (JSONException e) {
                Log.e(TAG, "Failed to send offer: " + e.getMessage());
            }
        }
    }

    public void sendAnswer(SessionDescription sessionDescription) {
        if (webSocketClient != null && isConnected) {
            try {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("type", "answer");
                jsonObject.put("sdp", sessionDescription.description);
                webSocketClient.send(jsonObject.toString());
                Log.d(TAG, "Answer sent");
            } catch (JSONException e) {
                Log.e(TAG, "Failed to send answer: " + e.getMessage());
            }
        }
    }

    public void sendIceCandidate(IceCandidate iceCandidate) {
        if (webSocketClient != null && isConnected) {
            try {
                JSONObject candidateJson = new JSONObject();
                candidateJson.put("sdpMid", iceCandidate.sdpMid);
                candidateJson.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                candidateJson.put("candidate", iceCandidate.sdp);

                JSONObject jsonObject = new JSONObject();
                jsonObject.put("type", "candidate");
                jsonObject.put("candidate", candidateJson);
                webSocketClient.send(jsonObject.toString());
                Log.d(TAG, "ICE candidate sent");
            } catch (JSONException e) {
                Log.e(TAG, "Failed to send ICE candidate: " + e.getMessage());
            }
        }
    }
}