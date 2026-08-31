const activePendingStreams = new Map();
let stopRequestInFlight = false;

function getPendingStreamSource(assistantId) {
    return activePendingStreams.get(assistantId);
}

function registerPendingStream(assistantId, source) {
    activePendingStreams.set(assistantId, source);
}

function clearPendingStreamState(assistantId, source) {
    if (activePendingStreams.get(assistantId) === source) {
        activePendingStreams.delete(assistantId);
        return true;
    }
    return false;
}

function isStopRequestInFlight() {
    return stopRequestInFlight;
}

function setStopRequestInFlight(value) {
    stopRequestInFlight = Boolean(value);
}

export {
    activePendingStreams,
    clearPendingStreamState,
    getPendingStreamSource,
    isStopRequestInFlight,
    registerPendingStream,
    setStopRequestInFlight
};
