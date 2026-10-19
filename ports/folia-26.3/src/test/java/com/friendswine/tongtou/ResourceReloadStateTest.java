package com.friendswine.tongtou;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResourceReloadStateTest {
    @Test void fallbackOnlyIfNoStartupResultAndModelsMissing() {
        var state = new ResourceReloadState();
        assertFalse(state.shouldAutoRebuild(false));
        assertTrue(state.shouldAutoRebuild(true));
        state.observeResult();
        assertFalse(state.shouldAutoRebuild(true));
    }
    @Test void failedStartupDoesNotImmediatelyRetrySameBrokenModels() {
        var state = new ResourceReloadState();
        assertTrue(state.reportFailure(new IllegalArgumentException("Degenerate polygon")));
        assertFalse(state.shouldAutoRebuild(true));
        assertTrue(state.beginRequest(), "管理员手动重试仍应可用");
    }
    @Test void eventAndReturnValueReportSameFailureOnlyOnce() {
        var state = new ResourceReloadState();
        var failure = new IllegalArgumentException("mesh");
        assertTrue(state.reportFailure(failure));
        assertFalse(state.reportFailure(failure));
        assertTrue(state.beginRequest());
        assertTrue(state.reportFailure(failure));
        assertFalse(state.reportFailure(failure));
    }
    @Test void onlyOneOutstandingRequestAndNoRequestsAfterClose() {
        var state = new ResourceReloadState();
        assertTrue(state.beginRequest());
        assertFalse(state.beginRequest());
        assertFalse(state.shouldAutoRebuild(true));
        state.finishRequest();
        assertTrue(state.beginRequest());
        state.close();
        state.finishRequest();
        assertFalse(state.beginRequest());
        assertFalse(state.shouldAutoRebuild(true));
        assertFalse(state.reportFailure(new RuntimeException()));
    }
}
