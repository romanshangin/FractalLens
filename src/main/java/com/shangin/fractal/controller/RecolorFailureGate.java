package com.shangin.fractal.controller;

/** Defers a lone stale failure but reports a continuing recolor failure once. */
final class RecolorFailureGate {
    private long reportedEpoch = Long.MIN_VALUE;
    private long reportedAfterPublication = Long.MIN_VALUE;
    private long failureEpoch = Long.MIN_VALUE;
    private long failureAfterPublication = Long.MIN_VALUE;
    private long lastFailedRequest = Long.MIN_VALUE;
    private int consecutiveFailures;

    boolean shouldReport(long requestEpoch, long currentEpoch, long requestSequence,
                         long latestRequestSequence, long publishedSequence,
                         boolean sameFrame) {
        if (requestEpoch != currentEpoch || !sameFrame
                || requestSequence <= publishedSequence) {
            return false;
        }
        if (failureEpoch != requestEpoch || failureAfterPublication != publishedSequence) {
            failureEpoch = requestEpoch;
            failureAfterPublication = publishedSequence;
            lastFailedRequest = Long.MIN_VALUE;
            consecutiveFailures = 0;
        }
        if (requestSequence <= lastFailedRequest) return false;
        lastFailedRequest = requestSequence;
        consecutiveFailures++;
        if (reportedEpoch == requestEpoch
                && reportedAfterPublication == publishedSequence) {
            return false;
        }
        if (requestSequence < latestRequestSequence && consecutiveFailures < 2) {
            return false;
        }
        reportedEpoch = requestEpoch;
        reportedAfterPublication = publishedSequence;
        return true;
    }
}
