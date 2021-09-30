package org.jitsi.videobridge;

import java.util.Objects;

public class EndpointVideoConstraint {
    private final boolean offVideo;
    private final boolean lowRes;
    private final boolean lowFps;

    public EndpointVideoConstraint(boolean offVideo, boolean lowRes, boolean lowFps) {
        this.offVideo = offVideo;
        this.lowRes = lowRes;
        this.lowFps = lowFps;
    }

    public boolean isOffVideo() {
        return offVideo;
    }

    public boolean isLowRes() {
        return lowRes;
    }

    public boolean isLowFps() {
        return lowFps;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EndpointVideoConstraint that = (EndpointVideoConstraint) o;
        return offVideo == that.offVideo && lowRes == that.lowRes && lowFps == that.lowFps;
    }

    @Override
    public int hashCode() {
        return Objects.hash(offVideo, lowRes, lowFps);
    }
}
