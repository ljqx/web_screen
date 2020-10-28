package com.bbogush.web_screen;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.CameraEnumerator;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class WebRtcManager {
    private static final String TAG = WebRtcManager.class.getSimpleName();

    private static final boolean ENABLE_INTEL_VP8_ENCODER = true;
    private static final boolean ENABLE_H264_HIGH_PROFILE = true;
    private static final int FRAMES_PER_SECOMD = 30;
    private static final String SDP_PARAM = "sdp";
    private static final String ICE_PARAM = "ice";

    private VideoCapturer videoCapturer;
    private EglBase rootEglBase;
    private PeerConnectionFactory peerConnectionFactory;
    private MediaConstraints audioConstraints;
    private MediaConstraints videoConstraints;
    private VideoTrack localVideoTrack;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private PeerConnection localPeer;
    private MediaConstraints sdpConstraints;
    private HttpServer server;

    List<PeerConnection.IceServer> peerIceServers = new ArrayList<>();
    private List<IceServer> iceServers = null;

    public WebRtcManager(Intent intent, Context context, HttpServer server) {
        this.server = server;
        //XXX getIceServers();
        createMediaProjection(intent);
        initWebRTC(context);
        //createPeerConnection();
        initSignaling();
    }

    public void close() {
        uninitSignaling();
        destroyMediaProjection();
    }

    private void createMediaProjection(Intent intent) {
        videoCapturer = new ScreenCapturerAndroid(intent,
                new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        super.onStop();
                        Log.e(TAG, "User has revoked media projection permissions");
                    }
                });
    }

    private void destroyMediaProjection() {
        videoCapturer = null;
    }

    private void initWebRTC(Context context) {
        rootEglBase = EglBase.create();

        PeerConnectionFactory.InitializationOptions initializationOptions =
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        DefaultVideoEncoderFactory defaultVideoEncoderFactory = new DefaultVideoEncoderFactory(
                rootEglBase.getEglBaseContext(), ENABLE_INTEL_VP8_ENCODER,
                ENABLE_H264_HIGH_PROFILE);
        DefaultVideoDecoderFactory defaultVideoDecoderFactory = new
                DefaultVideoDecoderFactory(rootEglBase.getEglBaseContext());
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(defaultVideoEncoderFactory)
                .setVideoDecoderFactory(defaultVideoDecoderFactory)
                .createPeerConnectionFactory();

        //XXX enable camera for test
        //videoCapturer = createCameraCapturer(new Camera1Enumerator(false));

        audioConstraints = new MediaConstraints();
        videoConstraints = new MediaConstraints();

        SurfaceTextureHelper surfaceTextureHelper;
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.getEglBaseContext());
        VideoSource videoSource =
                peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());

        localVideoTrack = peerConnectionFactory.createVideoTrack("100", videoSource);

        audioSource = peerConnectionFactory.createAudioSource(audioConstraints);
        localAudioTrack = peerConnectionFactory.createAudioTrack("101", audioSource);

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        display.getRealMetrics(metrics);
        if (videoCapturer != null) {
            videoCapturer.startCapture(metrics.widthPixels, metrics.heightPixels,
                    FRAMES_PER_SECOMD);
        }
    }

    public void onTryToStart(HttpServer server) {
        Log.d(TAG, new Object(){}.getClass().getEnclosingMethod().getName());
        createPeerConnection();
        doCall(server);
    }

    private void createPeerConnection() {
        PeerConnection.RTCConfiguration rtcConfig =
                new PeerConnection.RTCConfiguration(peerIceServers);
        // TCP candidates are only useful when connecting to a server that supports
        // ICE-TCP.
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        // Use ECDSA encryption.
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA;
        localPeer = peerConnectionFactory.createPeerConnection(rtcConfig,
                new CustomPeerConnectionObserver("localPeerCreation") {
                    @Override
                    public void onIceCandidate(IceCandidate iceCandidate) {
                        super.onIceCandidate(iceCandidate);
                        onIceCandidateReceived(iceCandidate);
                    }

                    @Override
                    public void onAddStream(MediaStream mediaStream) {
                        //showToast("Received Remote stream");
                        super.onAddStream(mediaStream);
                        gotRemoteStream(mediaStream);
                    }
                });

        addStreamToLocalPeer();
    }

    public void onIceCandidateReceived(IceCandidate iceCandidate) {
        Log.d(TAG, new Object(){}.getClass().getEnclosingMethod().getName());

        JSONObject messageJson = new JSONObject();
        JSONObject iceJson = new JSONObject();
        try {
            iceJson.put("type", "candidate");
            iceJson.put("label", iceCandidate.sdpMLineIndex);
            iceJson.put("id", iceCandidate.sdpMid);
            iceJson.put("candidate", iceCandidate.sdp);

            messageJson.put("type", "ice");
            messageJson.put("ice", iceJson);

            String messageJsonStr = messageJson.toString();
            //XXX broadcast
            server.send(messageJson.toString());
            Log.d(TAG, "Send: " + messageJsonStr);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
    }

    private void gotRemoteStream(MediaStream stream) {
        //we have remote video stream. add to the renderer.
//        final VideoTrack videoTrack = stream.videoTracks.get(0);
//        runOnUiThread(() -> {
//            try {
//                remoteVideoView.setVisibility(View.VISIBLE);
//                videoTrack.addSink(remoteVideoView);
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//        });
    }

    private void addStreamToLocalPeer() {
        //creating local mediastream
        MediaStream stream = peerConnectionFactory.createLocalMediaStream("102");
        stream.addTrack(localAudioTrack);
        stream.addTrack(localVideoTrack);
        localPeer.addStream(stream);
    }

    private void doCall(HttpServer server) {
        Log.d(TAG, new Object(){}.getClass().getEnclosingMethod().getName());
        sdpConstraints = new MediaConstraints();
        sdpConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        sdpConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        localPeer.createOffer(new CustomSdpObserver("localCreateOffer") {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);
                localPeer.setLocalDescription(new CustomSdpObserver("localSetLocalDesc"), sessionDescription);
                Log.d("onCreateSuccess", "SignallingClient emit ");

                JSONObject messageJson = new JSONObject();
                JSONObject sdpJson = new JSONObject();
                try {
                    sdpJson.put("type", sessionDescription.type.canonicalForm());
                    sdpJson.put("sdp", sessionDescription.description);

                    messageJson.put("type", "sdp");
                    messageJson.put("sdp", sdpJson);
                } catch (JSONException e) {
                    e.printStackTrace();
                    return;
                }

                String messageJsonStr = messageJson.toString();
                try {
                    server.send(messageJsonStr);
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
                Log.d(TAG, messageJsonStr);
            }
        }, sdpConstraints);
    }

    private void onOffer(HttpServer.Ws ws, String data) {
        Log.d(TAG, "Offer: SDP=" + data);

        String sdp;
        try {
            JSONObject json = new JSONObject(data);
            sdp = json.getString("sdp");
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }

        localPeer.setRemoteDescription(new CustomSdpObserver("localSetRemote"),
                new SessionDescription(SessionDescription.Type.OFFER, sdp));
        doAnswer(ws);
    }

    public void onAnswerReceived(JSONObject data) {
        Log.d(TAG, new Object(){}.getClass().getEnclosingMethod().getName());

        JSONObject json;
        try {
            json = data.getJSONObject(SDP_PARAM);
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }

        try {
            localPeer.setRemoteDescription(new CustomSdpObserver("localSetRemote"),
                    new SessionDescription(SessionDescription.Type.fromCanonicalForm(json.getString(
                            "type").toLowerCase()), json.getString("sdp")));
            //updateVideoViews(true);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void doAnswer(HttpServer.Ws ws) {
        localPeer.createAnswer(new CustomSdpObserver("localCreateAns") {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);
                localPeer.setLocalDescription(new CustomSdpObserver("localSetLocal"),
                        sessionDescription);
                //SignallingClient.getInstance().emitMessage(sessionDescription);
                JSONObject obj = new JSONObject();
                try {
                    obj.put("type", sessionDescription.type.canonicalForm());
                    obj.put("sdp", sessionDescription.description);
                } catch (JSONException e) {
                    e.printStackTrace();
                    return;
                }
                String jsonStr = obj.toString();

                try {
                    ws.send(jsonStr);
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
                Log.d(TAG, jsonStr);
            }
        }, new MediaConstraints());
    }

    public void onIceCandidateReceived(JSONObject data) {
        Log.d(TAG, new Object(){}.getClass().getEnclosingMethod().getName());

        JSONObject json;
        try {
            json = data.getJSONObject(ICE_PARAM);
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }

        try {
            localPeer.addIceCandidate(new IceCandidate(json.getString("id"), json.getInt("label"),
                    json.getString("candidate")));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        Log.d(TAG, new Object(){}.getClass().getEnclosingMethod().getName());
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing camera
        Logging.d(TAG, "Looking for front facing cameras.");
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating front facing camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        // Front facing camera not found, try something else
        Logging.d(TAG, "Looking for other cameras.");
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating other camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;
    }

    public void getIceServers() {
        final String API_ENDPOINT = "https://global.xirsys.net";

        Log.d(TAG, "getIceServers");

        byte[] data = new byte[0];
        try {
            data = ("<xirsys_ident>:<xirsys_secret>").getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return;
        }
        Log.d(TAG, "getIceServers2");

        String authToken = "Basic " + Base64.encodeToString(data, Base64.NO_WRAP);
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(API_ENDPOINT)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        Log.d(TAG, "getIceServers3");
        TurnServer turnServer = retrofit.create(TurnServer.class);
        Log.d(TAG, "getIceServers4");
        turnServer.getIceCandidates(authToken).enqueue(new Callback<TurnServerPojo>() {
            @Override
            public void onResponse(@NonNull Call<TurnServerPojo> call,
                                   @NonNull Response<TurnServerPojo> response) {
                Log.d(TAG, "getIceServers Response");
                TurnServerPojo body = response.body();
                if (body != null)
                    iceServers = body.iceServerList.iceServers;

                Log.d(TAG, "getIceServers iceServers=" + iceServers);

                for (IceServer iceServer : iceServers) {
                    if (iceServer.credential == null) {
                        PeerConnection.IceServer peerIceServer = PeerConnection.IceServer
                                .builder(iceServer.url).createIceServer();
                        peerIceServers.add(peerIceServer);
                    } else {
                        PeerConnection.IceServer peerIceServer = PeerConnection.IceServer
                                .builder(iceServer.url)
                                .setUsername(iceServer.username)
                                .setPassword(iceServer.credential)
                                .createIceServer();
                        peerIceServers.add(peerIceServer);
                    }
                }
                Log.d(TAG, "IceServers:\n" + iceServers.toString());
            }

            @Override
            public void onFailure(@NonNull Call<TurnServerPojo> call, @NonNull Throwable t) {
                t.printStackTrace();
            }
        });
    }

    public void initSignaling() {
        SignallingClient.getInstance().init(new Signaling());
    }

    public void uninitSignaling() {
        SignallingClient.getInstance().close();
    }

    private class Signaling implements SignallingClient.SignalingInterface {

        @Override
        public void onRemoteHangUp(String msg) {

        }

        @Override
        public void onOfferReceived(JSONObject data) {

        }

        @Override
        public void onAnswerReceived(JSONObject data) {

        }

        @Override
        public void onIceCandidateReceived(JSONObject data) {

        }

        @Override
        public void onTryToStart() {

        }

        @Override
        public void onCreatedRoom() {

        }

        @Override
        public void onJoinedRoom() {

        }

        @Override
        public void onNewPeerJoined() {

        }
    }
}
