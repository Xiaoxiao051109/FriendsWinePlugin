package com.friendswine.tongtou;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 只保存插件数据；会话内的世界操作由所属线程执行。 */
public final class SessionBook<K> {
    private final Map<K, Lease> entries = new ConcurrentHashMap<>();
    private final int limit;
    private boolean closed, stopping;
    public SessionBook(int limit) {
        if (limit < 1) throw new IllegalArgumentException("会话上限必须为正数");
        this.limit = limit;
    }
    public synchronized Lease acquire(K key) {
        if (closed || stopping || entries.size() >= limit || entries.containsKey(key)) return null;
        Lease lease = new Lease(key);
        entries.put(key, lease);
        return lease;
    }
    public int size() { return entries.size(); }
    public void cancel(K key) {
        Lease lease = entries.get(key);
        if (lease != null) lease.close();
    }
    public synchronized void cancelAll() {
        if (stopping) return;
        stopping = true;
        RuntimeException failures = null;
        try {
            for (Lease lease : List.copyOf(entries.values())) try { lease.close(); }
            catch (RuntimeException failure) { if(failures==null)failures=failure;else failures.addSuppressed(failure); }
        } finally { stopping = false; }
        if(failures!=null)throw failures;
    }
    public synchronized void shutdown() {
        closed = true;
        cancelAll();
    }
    public final class Lease implements AutoCloseable {
        private final K key;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final List<Runnable> cleanup = new ArrayList<>();
        private Lease(K key) { this.key = key; }
        public boolean active() { return active.get(); }
        public synchronized void onClose(Runnable action) {
            Objects.requireNonNull(action);
            if (!active()) action.run();
            else cleanup.add(action);
        }
        @Override public void close() {
            List<Runnable> actions;
            synchronized (this) {
                if (!active.compareAndSet(true, false)) return;
                entries.remove(key, this);
                actions = List.copyOf(cleanup); cleanup.clear();
            }
            RuntimeException failures = null;
            for(Runnable action:actions)try{action.run();}
            catch(RuntimeException failure){if(failures==null)failures=failure;else failures.addSuppressed(failure);}
            if(failures!=null)throw failures;
        }
    }
}
