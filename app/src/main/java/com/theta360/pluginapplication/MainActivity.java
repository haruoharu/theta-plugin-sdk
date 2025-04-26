package com.theta360.pluginapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.theta360.pluginapplication.network.HttpConnector;
import com.theta360.pluginlibrary.activity.PluginActivity;
import com.theta360.pluginlibrary.callback.KeyCallback;
import com.theta360.pluginlibrary.receiver.KeyReceiver;
import com.theta360.pluginlibrary.values.LedColor;
import com.theta360.pluginlibrary.values.LedTarget;

import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends PluginActivity {
    private static final String TAG = "ThetaWebRTC";
    private static final int PERMISSION_REQUEST_CODE = 100;

    // WebRTC関連
    private EglBase eglBase;
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private VideoSource videoSource;
    private VideoTrack localVideoTrack;
    private CameraVideoCapturer videoCapturer;
    private SurfaceViewRenderer localVideoView;
    private ExecutorService executor;

    // シグナリングサーバーのURL（実際の環境に合わせて変更）
    private static final String SIGNALING_SERVER_URL = "wss://your-signaling-server.com";
    private SignalingClient signalingClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 権限の確認
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CODE);
            return;
        }

        // WebRTC初期化
        initWebRTC();

        // キーイベントの設定
        setKeyCallback(new KeyCallback() {
            @Override
            public void onKeyDown(int keyCode, KeyEvent event) {
                if (keyCode == KeyReceiver.KEYCODE_CAMERA) {
                    // カメラボタンが押されたらストリーミングを開始/停止
                    toggleStreaming();
                }
            }

            @Override
            public void onKeyUp(int keyCode, KeyEvent event) {
                // 何もしない
            }

            @Override
            public void onKeyLongPress(int keyCode, KeyEvent event) {
                // 何もしない
            }
        });

        // LEDを青色に設定（準備完了の合図）
        notificationLedBlink(LedTarget.LED4, LedColor.BLUE,1000);
    }

    private void initWebRTC() {
        executor = Executors.newSingleThreadExecutor();

        // EglBaseの作成
        eglBase = EglBase.create();

        // ローカルビデオビューの設定
        localVideoView = findViewById(R.id.local_video_view);
        localVideoView.init(eglBase.getEglBaseContext(), null);
        localVideoView.setZOrderMediaOverlay(true);

        // PeerConnectionFactoryの初期化
        PeerConnectionFactory.InitializationOptions initOptions =
                PeerConnectionFactory.InitializationOptions.builder(this)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        DefaultVideoEncoderFactory videoEncoderFactory =
                new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
        DefaultVideoDecoderFactory videoDecoderFactory =
                new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(videoEncoderFactory)
                .setVideoDecoderFactory(videoDecoderFactory)
                .createPeerConnectionFactory();

        // カメラキャプチャの設定
        videoCapturer = createCameraCapturer();
        if (videoCapturer == null) {
            Log.e(TAG, "Failed to create camera capturer");
            return;
        }

        // ビデオソースの作成
        SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
        videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(surfaceTextureHelper, this, videoSource.getCapturerObserver());

        // ビデオトラックの作成
        localVideoTrack = peerConnectionFactory.createVideoTrack("ARDAMSv0", videoSource);
        localVideoTrack.addSink(localVideoView);

        // シグナリングクライアントの初期化
        signalingClient = new SignalingClient(SIGNALING_SERVER_URL, new SignalingClient.Callback() {
            @Override
            public void onOfferReceived(SessionDescription sdp) {
                handleRemoteOffer(sdp);
            }

            @Override
            public void onAnswerReceived(SessionDescription sdp) {
                handleRemoteAnswer(sdp);
            }

            @Override
            public void onIceCandidateReceived(IceCandidate candidate) {
                handleRemoteIceCandidate(candidate);
            }

            @Override
            public void onSignalingConnected() {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "シグナリングサーバーに接続しました", Toast.LENGTH_SHORT).show();
                    notificationLedBlink(LedTarget.LED4, LedColor.GREEN,1000);
                });
            }

            @Override
            public void onSignalingDisconnected() {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "シグナリングサーバーから切断されました", Toast.LENGTH_SHORT).show();
                    notificationLedBlink(LedTarget.LED4, LedColor.BLUE,1000);
                });
            }
        });

        // PeerConnectionの作成
        createPeerConnection();
    }

    private CameraVideoCapturer createCameraCapturer() {
        Camera2Enumerator enumerator = new Camera2Enumerator(this);
        String[] deviceNames = enumerator.getDeviceNames();

        // 前面カメラを優先的に使用
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                CameraVideoCapturer capturer = enumerator.createCapturer(deviceName, null);
                if (capturer != null) {
                    return capturer;
                }
            }
        }

        // 前面カメラがなければ背面カメラを使用
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                CameraVideoCapturer capturer = enumerator.createCapturer(deviceName, null);
                if (capturer != null) {
                    return capturer;
                }
            }
        }

        return null;
    }

    private void createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        // STUNサーバーの設定
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                Log.d(TAG, "onSignalingChange: " + signalingState);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.d(TAG, "onIceConnectionChange: " + iceConnectionState);
                if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
                    runOnUiThread(() -> {
                        notificationLedBlink(LedTarget.LED4, LedColor.GREEN,1000);
                        Toast.makeText(MainActivity.this, "接続しました", Toast.LENGTH_SHORT).show();
                    });
                } else if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                    runOnUiThread(() -> {
                        notificationLedBlink(LedTarget.LED4, LedColor.BLUE,1000);
                        Toast.makeText(MainActivity.this, "切断されました", Toast.LENGTH_SHORT).show();
                    });
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {
                Log.d(TAG, "onIceConnectionReceivingChange: " + b);
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: " + iceGatheringState);
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Log.d(TAG, "onIceCandidate: " + iceCandidate);
                signalingClient.sendIceCandidate(iceCandidate);
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
                Log.d(TAG, "onIceCandidatesRemoved");
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                Log.d(TAG, "onAddStream: " + mediaStream.getId());
            }

            @Override
            public void onRemoveStream(MediaStream mediaStream) {
                Log.d(TAG, "onRemoveStream: " + mediaStream.getId());
            }

            @Override
            public void onDataChannel(DataChannel dataChannel) {
                Log.d(TAG, "onDataChannel: " + dataChannel.label());
            }

            @Override
            public void onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded");
                createOffer();
            }

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                Log.d(TAG, "onAddTrack");
            }
        });

        // ローカルストリームの追加
        List<String> streamIds = new ArrayList<>();
        streamIds.add("ARDAMS");
        peerConnection.addTrack(localVideoTrack, streamIds);
    }

    private void createOffer() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.d(TAG, "Offer created");
                peerConnection.setLocalDescription(new SimpleSdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        Log.d(TAG, "Local description set");
                        signalingClient.sendOffer(sessionDescription);
                    }
                }, sessionDescription);
            }
        }, constraints);
    }

    private void handleRemoteOffer(SessionDescription sdp) {
        peerConnection.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                Log.d(TAG, "Remote description set");
                createAnswer();
            }
        }, sdp);
    }

    private void createAnswer() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.d(TAG, "Answer created");
                peerConnection.setLocalDescription(new SimpleSdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        Log.d(TAG, "Local description set");
                        signalingClient.sendAnswer(sessionDescription);
                    }
                }, sessionDescription);
            }
        }, constraints);
    }

    private void handleRemoteAnswer(SessionDescription sdp) {
        peerConnection.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                Log.d(TAG, "Remote description set");
            }
        }, sdp);
    }


    private void handleRemoteIceCandidate(IceCandidate candidate) {
        if (peerConnection != null) {
            peerConnection.addIceCandidate(candidate);
            Log.d(TAG, "Remote ICE candidate added");
        } else {
            Log.e(TAG, "Failed to add remote ICE candidate: PeerConnection is null");
        }
    }

    private void toggleStreaming() {
        if (videoCapturer != null && peerConnection != null) {
            if (isStreaming) {
                // ストリーミングを停止
                stopStreaming();
                notificationLedBlink(LedTarget.LED4, LedColor.BLUE,1000);
                Toast.makeText(this, "ストリーミングを停止しました", Toast.LENGTH_SHORT).show();
            } else {
                // ストリーミングを開始
                startStreaming();
                notificationLedBlink(LedTarget.LED4, LedColor.MAGENTA,1000);
                Toast.makeText(this, "ストリーミングを開始しました", Toast.LENGTH_SHORT).show();
            }
            isStreaming = !isStreaming;
        }
    }

    private boolean isStreaming = false;

    private void startStreaming() {
        if (videoCapturer != null) {
            videoCapturer.startCapture(1920, 960, 30); // THETA カメラの解像度とフレームレート
            signalingClient.connect();
        }
    }

    private void stopStreaming() {
        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException e) {
                Log.e(TAG, "Failed to stop camera capture: " + e.getMessage());
            }
        }

        if (signalingClient != null) {
            signalingClient.disconnect();
        }
    }

    @Override
    protected void onDestroy() {
        stopStreaming();

        if (localVideoTrack != null) {
            localVideoTrack.dispose();
            localVideoTrack = null;
        }

        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }

        if (videoCapturer != null) {
            videoCapturer.dispose();
            videoCapturer = null;
        }

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }

        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
        }

        if (localVideoView != null) {
            localVideoView.release();
            localVideoView = null;
        }

        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }

        if (executor != null) {
            executor.shutdown();
            executor = null;
        }

        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 権限が付与されたらWebRTCを初期化
                initWebRTC();
            } else {
                Toast.makeText(this, "カメラの権限が必要です", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    // SimpleSdpObserverクラス - WebRTCのコールバックを簡略化するためのヘルパークラス
    private static class SimpleSdpObserver implements org.webrtc.SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            // 実装はサブクラスで行う
        }

        @Override
        public void onSetSuccess() {
            // 実装はサブクラスで行う
        }

        @Override
        public void onCreateFailure(String s) {
            Log.e(TAG, "SDP creation failed: " + s);
        }

        @Override
        public void onSetFailure(String s) {
            Log.e(TAG, "SDP set failed: " + s);
        }
    }
}