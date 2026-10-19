package com.friendswine.tongtou;

import java.util.Objects;

/** 区分未收到启动结果与已确定失败；失败不触发自动重试，手动重试仍可使用。 */
final class ResourceReloadState {
    private boolean observed;
    private boolean requested;
    private boolean closed;
    private Throwable lastFailure;
    synchronized void observeResult() { observed = true; }
    synchronized boolean shouldAutoRebuild(boolean missingModels) {
        return !closed && !observed && !requested && missingModels;
    }
    synchronized boolean beginRequest() {
        if (closed || requested) return false;
        requested = true;
        lastFailure = null;
        return true;
    }
    synchronized void finishRequest() { requested = false; }
    synchronized boolean reportFailure(Throwable failure) {
        Objects.requireNonNull(failure);
        observed = true;
        if (closed || failure == lastFailure) return false;
        lastFailure = failure;
        return true;
    }
    synchronized void close() { closed = true; }
}
