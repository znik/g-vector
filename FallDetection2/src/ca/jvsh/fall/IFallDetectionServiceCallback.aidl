package ca.jvsh.fall;

/**
 * Example of a callback interface used by IRemoteService to send
 * synchronous notifications back to its clients.  Note that this is a
 * one-way interface so the server does not block waiting for the client.
 */
oneway interface IFallDetectionServiceCallback {
    /**
     * Called when the service has a new value for you.
     */
    void updateStats(double average, double variance, double frequency);
    /**
     * Called when fall is detected.
     */
    void fallMessage(String fallMsg);
}
