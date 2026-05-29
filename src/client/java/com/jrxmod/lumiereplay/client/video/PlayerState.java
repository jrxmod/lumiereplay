package com.jrxmod.lumiereplay.client.video;

/**
 * Represents the current state of a VideoPlayer instance.
 */
public enum PlayerState {
    IDLE,       // No video loaded
    RESOLVING,  // yt-dlp is fetching the direct URL
    LOADING,    // FFmpeg is opening and buffering the stream
    PLAYING,    // Actively decoding and rendering frames
    PAUSED,     // Decoding paused, last frame still visible
    ERROR       // Playback failed
}
