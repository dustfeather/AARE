package net.prototux.aare.audio;

/** The audio capture helper Shizuku runs with adb-level rights (ShellAudioService). */
interface IShellAudio {
    /**
     * Starts capturing everything the phone plays (which then stops playing on the phone).
     * Returns the read end of a pipe of 16-bit little-endian PCM, or null if capture failed.
     */
    ParcelFileDescriptor start(int sampleRate, int channels) = 1;

    void stop() = 2;

    /** Shizuku's user-service teardown call. */
    void destroy() = 16777114;
}
