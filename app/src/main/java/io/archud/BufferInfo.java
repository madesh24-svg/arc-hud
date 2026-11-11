package io.archud;

/** What the player needs to know about the data waiting for it, whichever way it reaches the TV. */
interface BufferInfo {
    /** Bytes ready in front of the player. */
    long ahead();

    /** True when everything up to the end of the video is already here. */
    boolean restBuffered();

    /** Bytes per second received over the last few seconds (0 until known). */
    long rate();

    /** Size of the whole video in bytes, or -1 when it is a stream of unknown length. */
    long sizeBytes();

    void close();
}
