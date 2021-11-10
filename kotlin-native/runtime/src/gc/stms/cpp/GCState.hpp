/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#pragma once

#include <condition_variable>
#include <mutex>
#include <atomic>

enum class GCState {
    kNone,
    kNeedsGC,
    kNeedsSuspend,
    kWorldIsStopped,
    kGCRunning,
    kNeedsFinalizersRun,
    kShutdown,
};


class GCStateHolder {
public:
    explicit GCStateHolder(std::atomic<bool>& gNeedSafepointSlowpath): gNeedSafepointSlowpath_(gNeedSafepointSlowpath) {}
    GCState get() { return state_.load(); }
    bool compareAndSwap(GCState &oldState, GCState newState) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (state_.compare_exchange_strong(oldState, newState)) {
            cond_.notify_all();
            gNeedSafepointSlowpath_ = recalcNeedSlowPath(newState);
            return true;
        }
        return false;
    }
    bool compareAndSet(GCState oldState, GCState newState) {
        return compareAndSwap(oldState, newState);
    }

    template<class WaitF>
    GCState waitUntil(WaitF fun) {
        return waitUntil(std::move(fun), []{});
    }
    template<class WaitF, class AfterF>
    GCState waitUntil(WaitF fun, AfterF after) {
        std::unique_lock<std::mutex> lock(mutex_);
        cond_.wait(lock, std::move(fun));
        after();
        return state_.load();
    }
    template<class WaitF>
    GCState waitUntilIfNotSingleThreaded(WaitF fun) {
        return waitUntilIfNotSingleThreaded(std::move(fun), []{});
    }
    template<class WaitF, class AfterF>
    GCState waitUntilIfNotSingleThreaded(WaitF fun, AfterF after) {
#ifndef KONAN_NO_THREADS
        return waitUntil(std::move(fun), std::move(after));
#else
        return state_.load();
#endif
    }
private:
    static bool recalcNeedSlowPath(GCState state) {
        switch (state) {
            case GCState::kNone:
            case GCState::kNeedsGC:
            case GCState::kWorldIsStopped:
            case GCState::kGCRunning:
            case GCState::kShutdown:
                return false;
            case GCState::kNeedsSuspend:
            case GCState::kNeedsFinalizersRun:
                return true;
        }
        RuntimeFail("Unknown GC State %d\n", static_cast<int>(state));
    }

    std::atomic<GCState> state_ = GCState::kNone;
    std::mutex mutex_;
    std::condition_variable cond_;
    std::atomic<bool>& gNeedSafepointSlowpath_;
};