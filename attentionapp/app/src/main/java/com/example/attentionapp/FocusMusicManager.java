package com.example.attentionapp;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;
import java.util.Random;

/**
 * FocusMusicManager provides:
 * 1. Natural Voice Announcements via Text-To-Speech (TTS) for session starts, breaks, and focus alerts.
 * 2. Pure Acoustic 528Hz Zen Chime Bell synthesis for Pomodoro intervals.
 * 3. Continuous rich Lo-Fi Brown Noise & Alpha wave audio synthesis.
 * 4. Deep YouTube ADHD Pomodoro stream integration (https://youtu.be/QH0t_ogClhA).
 */
public class FocusMusicManager implements TextToSpeech.OnInitListener {

    private static final String TAG = "FocusMusicManager";
    public static final String YOUTUBE_POMODORO_URL = "https://youtu.be/QH0t_ogClhA";
    public static final String TRACK_NAME = "4-Hour ADHD Brown Noise Pomodoro";

    private static FocusMusicManager instance;
    private Context context;

    // Audio synthesis components
    private AudioTrack audioTrack;
    private Thread audioThread;
    private volatile boolean isRunning = false;
    private volatile boolean isPaused = false;
    private volatile float volume = 0.70f;

    // Text To Speech (Voice Engine)
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingSpeakText = null;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static synchronized FocusMusicManager getInstance(Context context) {
        if (instance == null) {
            instance = new FocusMusicManager(context != null ? context.getApplicationContext() : null);
        }
        return instance;
    }

    private FocusMusicManager(Context context) {
        this.context = context;
        initTts();
    }

    private void initTts() {
        if (context == null) return;
        try {
            tts = new TextToSpeech(context, this);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize TextToSpeech: " + e.getMessage());
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            int result = tts.setLanguage(Locale.US);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback to default system locale
                tts.setLanguage(Locale.getDefault());
            }
            tts.setPitch(1.0f);
            tts.setSpeechRate(0.95f);
            ttsReady = true;
            Log.d(TAG, "TextToSpeech initialized successfully.");

            if (pendingSpeakText != null) {
                String toSpeak = pendingSpeakText;
                pendingSpeakText = null;
                speak(toSpeak);
            }
        } else {
            Log.w(TAG, "TextToSpeech init failed with status: " + status);
        }
    }

    /**
     * Speaks voice guidance aloud through the phone speaker or headphones.
     */
    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        final String speech = text.trim();

        mainHandler.post(() -> {
            if (!ttsReady || tts == null) {
                pendingSpeakText = speech;
                if (tts == null) initTts();
                return;
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tts.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "FocusVoiceUtterance");
                } else {
                    tts.speak(speech, TextToSpeech.QUEUE_FLUSH, null);
                }
                Log.d(TAG, "Voice spoken: " + speech);
            } catch (Exception e) {
                Log.e(TAG, "TTS speak failed: " + e.getMessage());
            }
        });
    }

    /**
     * Plays a resonant Tibetan singing bowl chime bell (528 Hz Solfeggio focus tone)
     * synthesized in real time. Perfect for starting or transitioning study sessions.
     */
    public void playZenBell() {
        new Thread(() -> {
            int sampleRate = 44100;
            int numSamples = (int) (sampleRate * 2.2); // 2.2 second chime
            short[] buffer = new short[numSamples];

            double freq1 = 528.0;  // Fundamental Miracle/Clarity tone
            double freq2 = 1056.0; // 1st Harmonic
            double freq3 = 1584.0; // 2nd Harmonic

            for (int i = 0; i < numSamples; i++) {
                double time = i / (double) sampleRate;
                // Exponential decay envelope
                double envelope = Math.exp(-time * 2.2);

                // Subtle warm binaural chorus
                double wave = 0.65 * Math.sin(2.0 * Math.PI * freq1 * time)
                        + 0.25 * Math.sin(2.0 * Math.PI * freq2 * time)
                        + 0.10 * Math.sin(2.0 * Math.PI * freq3 * time);

                double sample = wave * envelope * 28000.0;
                if (sample > 32767) sample = 32767;
                if (sample < -32768) sample = -32768;
                buffer[i] = (short) sample;
            }

            AudioTrack bellTrack = null;
            try {
                bellTrack = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(buffer.length * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();

                bellTrack.write(buffer, 0, buffer.length);
                bellTrack.play();
                Thread.sleep(2300);
            } catch (Exception e) {
                Log.e(TAG, "Error playing Zen Bell: " + e.getMessage());
            } finally {
                if (bellTrack != null) {
                    try {
                        bellTrack.stop();
                        bellTrack.release();
                    } catch (Exception ignored) {}
                }
            }
        }, "ZenBellThread").start();
    }

    /**
     * Starts continuous smoothed rich Brown Noise with 10Hz Alpha-wave focus modulation.
     */
    public synchronized void startBrownNoise() {
        if (isRunning) {
            isPaused = false;
            return;
        }

        isRunning = true;
        isPaused = false;

        audioThread = new Thread(() -> {
            int sampleRate = 22050;
            int minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            int bufferSize = Math.max(minBufferSize, 4096);

            try {
                audioTrack = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();

                audioTrack.play();
                audioTrack.setVolume(volume);

                short[] buffer = new short[bufferSize / 2];
                Random random = new Random();
                float lastBrown = 0.0f;
                long sampleIndex = 0;

                while (isRunning) {
                    if (isPaused) {
                        try {
                            Thread.sleep(60);
                        } catch (InterruptedException e) {
                            break;
                        }
                        continue;
                    }

                    for (int i = 0; i < buffer.length; i++) {
                        float white = (random.nextFloat() * 2.0f - 1.0f);
                        // Stable high-gain low-pass integrator for deep acoustic warmth
                        lastBrown = (0.965f * lastBrown) + (0.12f * white);

                        // Subtle 10Hz Alpha-wave entrainment modulation
                        double alphaMod = 1.0 + 0.12 * Math.sin(2.0 * Math.PI * 10.0 * (sampleIndex++ / (double) sampleRate));

                        float sample = (float) (lastBrown * 22000.0f * alphaMod);
                        if (sample > 32767.0f) sample = 32767.0f;
                        if (sample < -32768.0f) sample = -32768.0f;
                        buffer[i] = (short) sample;
                    }

                    if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack.write(buffer, 0, buffer.length);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Audio synthesis error: " + e.getMessage(), e);
            } finally {
                cleanupAudioTrack();
            }
        }, "FocusBrownNoiseThread");

        audioThread.setPriority(Thread.NORM_PRIORITY - 1);
        audioThread.start();
    }

    private synchronized void cleanupAudioTrack() {
        if (audioTrack != null) {
            try {
                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.stop();
                }
                audioTrack.release();
            } catch (Exception ignored) {}
            audioTrack = null;
        }
    }

    public synchronized void pauseAudio() {
        isPaused = true;
    }

    public synchronized void resumeAudio() {
        if (!isRunning) {
            startBrownNoise();
        } else {
            isPaused = false;
        }
    }

    public synchronized void stopAudio() {
        isRunning = false;
        isPaused = false;
        if (audioThread != null) {
            audioThread.interrupt();
            audioThread = null;
        }
        cleanupAudioTrack();
    }

    public boolean isPlaying() {
        return isRunning && !isPaused;
    }

    public boolean isAudioRunning() {
        return isRunning;
    }

    public void setVolume(float vol) {
        this.volume = Math.max(0.0f, Math.min(1.0f, vol));
        if (audioTrack != null) {
            try {
                audioTrack.setVolume(this.volume);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Opens the YouTube Pomodoro video directly in YouTube app or external browser.
     */
    public void openYouTubePomodoro(Context ctx) {
        Context target = (ctx != null) ? ctx : this.context;
        if (target == null) return;

        speak("Opening ADHD Pomodoro YouTube focus stream. Enjoy your study session!");

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(YOUTUBE_POMODORO_URL));
            intent.setPackage("com.google.android.youtube");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            target.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(YOUTUBE_POMODORO_URL));
                webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                target.startActivity(webIntent);
            } catch (Exception ignored) {}
        }
    }

    public void shutdown() {
        stopAudio();
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {}
            tts = null;
            ttsReady = false;
        }
    }
}
