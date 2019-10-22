package net.yrom.screenrecorder;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.projection.MediaProjection;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import com.upyun.hardware.AudioEncoder;
import com.upyun.hardware.PushClient;
import com.upyun.hardware.VideoEncoder;

import net.ossrs.yasea.SrsFlvMuxer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Yrom
 */
public class ScreenRecorder extends Thread {
    public static final int VFPS = 24;
    public static final int VGOP = 48;
    private static final String TAG = "ScreenRecorder";
    // parameters for the encoder
    private static final String MIME_TYPE = "video/avc"; // H.264 Advanced Video Coding
    private static final int FRAME_RATE = 30; // 30 fps
    private static final int IFRAME_INTERVAL = VGOP / VFPS; // 10 seconds between I-frames
    private static final int TIMEOUT_US = 10000;
    private final AudioEncoder audioEncoder;
    ScheduledExecutorService executorServiceVideo = Executors.newSingleThreadScheduledExecutor();
    ScheduledExecutorService executorServiceAudio = Executors.newSingleThreadScheduledExecutor();
    private int mWidth;
    private int mHeight;
    private int mBitRate;
    private int mDpi;
    private String mDstPath;
    private MediaProjection mMediaProjection;
    private MediaCodec mEncoder;
    private Surface mSurface;
    private boolean mMuxerStarted = false;
    private int mVideoTrackIndex = -1;
    private AtomicBoolean mQuit = new AtomicBoolean(false);
    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private VirtualDisplay mVirtualDisplay;
    private SrsFlvMuxer mSrsFlvMuxer;
    private byte[] spspps;
    private ByteBuffer sps;
    private ByteBuffer pps;
    private static AudioRecord audioRecord;
    public static int aChannelConfig = AudioFormat.CHANNEL_IN_STEREO;
    public static final int ASAMPLERATE = 44100;
    private static AcousticEchoCanceler aec;
    private static AutomaticGainControl agc;
    public ScreenRecorder(int width, int height, int bitrate, int dpi, MediaProjection mp, String dstPath, SrsFlvMuxer mSrsFlvMuxer) {
        super(TAG);
        mWidth = width;
        this.mSrsFlvMuxer = mSrsFlvMuxer;
        mHeight = height;
        mBitRate = bitrate;
        mDpi = dpi;
        mMediaProjection = mp;
        mDstPath = dstPath;
        audioEncoder = new AudioEncoder(mSrsFlvMuxer);
    }

    /**
     * stop task
     */
    public final void quit() {
        mQuit.set(true);
    }

    @Override
    public void run() {
        try {
            try {
                prepareEncoder();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(TAG + "-display",
                    mWidth, mHeight, mDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    mSurface, null, null);
            Log.d(TAG, "created virtual display: " + mVirtualDisplay);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    startAudio();
                }
            }).start();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    recordVirtualDisplay();
                }
            }).start();
            mSrsFlvMuxer.start(mDstPath);
        } catch (Exception e) {
            e.printStackTrace();
            release();
        }
    }

    private void recordVirtualDisplay() {
        executorServiceVideo.scheduleWithFixedDelay(new Runnable() {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            ByteBuffer lastData = null;
            @Override
            public void run() {
                int index = mEncoder.dequeueOutputBuffer(bufferInfo, -1);
                if (index >= 0 || lastData != null) {
                    if (index >= 0){
                        ByteBuffer data = mEncoder.getOutputBuffer(index);
                        mSrsFlvMuxer.writeSampleData(mVideoTrackIndex, data, bufferInfo);
                        mEncoder.releaseOutputBuffer(index, false);
                        lastData = data;
                    }else {
                        mSrsFlvMuxer.writeSampleData(mVideoTrackIndex, lastData, bufferInfo);
                    }
                }
            }
        },0,35, TimeUnit.MILLISECONDS);
    }


    private void prepareEncoder() throws IOException {
        final MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mWidth,mHeight );
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mSurface = mEncoder.createInputSurface();
        Log.d(TAG, "created input surface: " + mSurface);
        mEncoder.start();
        mVideoTrackIndex = mSrsFlvMuxer.addTrack(format);
    }


    private void startAudio() {
        audioRecord = chooseAudioRecord();
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(audioRecord.getAudioSessionId());
            if (aec != null) {
                aec.setEnabled(true);
            }
        }

        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(audioRecord.getAudioSessionId());
            if (agc != null) {
                agc.setEnabled(true);
            }
        }
        audioRecord.startRecording();
        executorServiceAudio.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
                byte[] buffer = new byte[4096];
                int len = audioRecord.read(buffer, 0, buffer.length);
                if (len > 0) audioEncoder.fireAudio(buffer,len);
            }
        },0,5, TimeUnit.MILLISECONDS);
    }

    public AudioRecord chooseAudioRecord() {
        AudioRecord mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, ASAMPLERATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, getPcmBufferSize() * 4);
        if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
            mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, ASAMPLERATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, getPcmBufferSize() * 4);
            if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
                mic = null;
            } else {
                aChannelConfig = AudioFormat.CHANNEL_IN_MONO;
            }
        } else {
            aChannelConfig = AudioFormat.CHANNEL_IN_STEREO;
        }

        return mic;
    }

    private int getPcmBufferSize() {
        int pcmBufSize = AudioRecord.getMinBufferSize(ASAMPLERATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT) + 8191;
        return pcmBufSize - (pcmBufSize % 8192);
    }

    private void release() {
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
        if (audioEncoder != null && audioRecord != null) {
            audioEncoder.stop();
            audioRecord.stop();
            audioRecord.release();
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
        }
        if (mSrsFlvMuxer != null) {
            mSrsFlvMuxer.stop();
            mSrsFlvMuxer = null;
        }
    }
}
