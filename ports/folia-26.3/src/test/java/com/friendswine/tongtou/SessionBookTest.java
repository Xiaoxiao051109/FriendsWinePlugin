package com.friendswine.tongtou;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionBookTest {
    @Test void duplicateLimitAndReuse() {
        var book = new SessionBook<String>(1);
        var first = book.acquire("entity");
        assertNotNull(first);
        assertNull(book.acquire("entity"));
        assertNull(book.acquire("block"));
        first.close();
        assertNotNull(book.acquire("entity"));
    }
    @Test void cleanupExactlyOnceAndLateRegistration() {
        var book = new SessionBook<String>(1);
        var count = new AtomicInteger();
        var lease = book.acquire("a");
        lease.onClose(count::incrementAndGet);
        lease.close(); lease.close();
        assertEquals(1, count.get());
        lease.onClose(count::incrementAndGet);
        assertEquals(2, count.get());
    }
    @Test void retiredAndShutdown() {
        var book = new SessionBook<String>(2);
        book.acquire("a"); book.acquire("b");
        book.cancel("a"); assertEquals(1, book.size());
        book.shutdown(); assertEquals(0, book.size());
        assertNull(book.acquire("new"));
    }
    @Test void concurrentDuplicateNeverAdmitted() throws Exception {
        var book = new SessionBook<String>(100);
        var admitted = new AtomicInteger();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 300; i++) threads.submit(() -> { if (book.acquire("same") != null) admitted.incrementAndGet(); });
        }
        assertEquals(1, admitted.get());
    }
    @Test void concurrentDistinctKeysRespectLimit() throws Exception {
        var book = new SessionBook<Integer>(8);
        var admitted = new AtomicInteger();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 300; i++) {
                int key = i;
                threads.submit(() -> { if (book.acquire(key) != null) admitted.incrementAndGet(); });
            }
        }
        assertEquals(8, admitted.get());
        book.cancelAll(); assertEquals(0, book.size());
    }
    @Test void composedCleanupAndOtherLeasesSurviveOneFailure() {
        var book = new SessionBook<String>(3);var count = new AtomicInteger();
        var first = book.acquire("a");first.onClose(()->{throw new IllegalStateException("模拟清理失败");});
        first.onClose(count::incrementAndGet);book.acquire("b").onClose(count::incrementAndGet);
        assertThrows(IllegalStateException.class,book::cancelAll);
        assertEquals(2,count.get());assertEquals(0,book.size());assertNotNull(book.acquire("new"));
    }
    @Test void stoppingPreventsCleanupCallbackFromRevivingSession() {
        var book = new SessionBook<String>(2);
        book.acquire("a").onClose(()->assertNull(book.acquire("a")));
        book.cancelAll();assertEquals(0,book.size());assertNotNull(book.acquire("a"));
    }
}
