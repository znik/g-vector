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
    void sensorChanged(double resultant, long timestamp);
}
