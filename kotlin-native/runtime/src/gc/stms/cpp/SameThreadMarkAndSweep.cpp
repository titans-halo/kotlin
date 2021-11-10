/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "SameThreadMarkAndSweep.hpp"

#include <cinttypes>

#include "CompilerConstants.hpp"
#include "GlobalData.hpp"
#include "Logging.hpp"
#include "MarkAndSweepUtils.hpp"
#include "Memory.h"
#include "RootSet.hpp"
#include "Runtime.h"
#include "ThreadData.hpp"
#include "ThreadRegistry.hpp"
#include "ThreadSuspension.hpp"
#include "GCState.hpp"

using namespace kotlin;

namespace {

struct MarkTraits {
    static bool IsMarked(ObjHeader* object) noexcept {
        auto& objectData = mm::ObjectFactory<gc::SameThreadMarkAndSweep>::NodeRef::From(object).GCObjectData();
        return objectData.color() == gc::SameThreadMarkAndSweep::ObjectData::Color::kBlack;
    }

    static bool TryMark(ObjHeader* object) noexcept {
        auto& objectData = mm::ObjectFactory<gc::SameThreadMarkAndSweep>::NodeRef::From(object).GCObjectData();
        if (objectData.color() == gc::SameThreadMarkAndSweep::ObjectData::Color::kBlack) return false;
        objectData.setColor(gc::SameThreadMarkAndSweep::ObjectData::Color::kBlack);
        return true;
    };
};

struct SweepTraits {
    using ObjectFactory = mm::ObjectFactory<gc::SameThreadMarkAndSweep>;
    using ExtraObjectsFactory = mm::ExtraObjectDataFactory;

    static bool IsMarkedByExtraObject(mm::ExtraObjectData &object) noexcept {
        auto *baseObject = object.GetBaseObject();
        if (!baseObject->heap()) return true;
        auto& objectData = mm::ObjectFactory<gc::SameThreadMarkAndSweep>::NodeRef::From(baseObject).GCObjectData();
        return objectData.color() == gc::SameThreadMarkAndSweep::ObjectData::Color::kBlack;
    }

    static bool TryResetMark(ObjectFactory::NodeRef node) noexcept {
        auto& objectData = node.GCObjectData();
        if (objectData.color() == gc::SameThreadMarkAndSweep::ObjectData::Color::kWhite) return false;
        objectData.setColor(gc::SameThreadMarkAndSweep::ObjectData::Color::kWhite);
        return true;
    }
};

// Global, because it's accessed on a hot path: avoid memory load from `this`.
std::atomic<bool> gNeedSafepointSlowpath = false;
mm::ObjectFactory<gc::SameThreadMarkAndSweep>::FinalizerQueue FinalizerQueue;
} // namespace

ALWAYS_INLINE void gc::SameThreadMarkAndSweep::ThreadData::SafePointFunctionPrologue() noexcept {
    SafePointRegular(GCSchedulerThreadData::kFunctionPrologueWeight);
}

ALWAYS_INLINE void gc::SameThreadMarkAndSweep::ThreadData::SafePointLoopBody() noexcept {
    SafePointRegular(GCSchedulerThreadData::kLoopBodyWeight);
}

void gc::SameThreadMarkAndSweep::ThreadData::SafePointAllocation(size_t size) noexcept {
    threadData_.gcScheduler().OnSafePointAllocation(size);
    if (gNeedSafepointSlowpath.load()) {
        SafePointSlowPath();
    }
}

void gc::SameThreadMarkAndSweep::ThreadData::ScheduleAndWaitFullGC() noexcept {
    auto state = gc_.state_.get();
    while (true) {
        if (state == GCState::kNeedsGC || state == GCState::kNeedsSuspend) {
            break;
        }
        if (state != GCState::kNone && state != GCState::kGCRunning) {
            // run finalizers from previous gc
            SafePointLoopBody();
            continue;
        }
        gc_.state_.compareAndSwap(state, GCState::kNeedsGC);
    }
    state = gc_.state_.waitUntilIfNotSingleThreaded([this] { return gc_.state_.get() != GCState::kNeedsGC; });
    RuntimeAssert(state == GCState::kNeedsSuspend, "I'm not suspended, someone started GC, but no suspension requested?");
    threadData_.suspensionData().suspendIfRequested();
    gc_.state_.waitUntilIfNotSingleThreaded([this] { return gc_.state_.get() != GCState::kGCRunning; });
    SafePointRegular(0);
}

void gc::SameThreadMarkAndSweep::ThreadData::OnOOM(size_t size) noexcept {
    RuntimeLogDebug({kTagGC}, "Attempt to GC on OOM at size=%zu", size);
    ScheduleAndWaitFullGC();
}

ALWAYS_INLINE void gc::SameThreadMarkAndSweep::ThreadData::SafePointRegular(size_t weight) noexcept {
    threadData_.gcScheduler().OnSafePointRegular(weight);
    if (gNeedSafepointSlowpath.load()) {
        SafePointSlowPath();
    }
}

NO_INLINE void gc::SameThreadMarkAndSweep::ThreadData::SafePointSlowPath() noexcept {
    auto state = gc_.state_.get();
    if (state == GCState::kNone) {
        return;
    }
    // No need to check for kNeedsSuspend, because `suspendIfRequested` checks for its own atomic.
    if (state == GCState::kNeedsFinalizersRun) {
        if (gc_.state_.compareAndSwap(state, GCState::kNone)) {
            // need to move it to stack, to avoid concurrent access to global queue, if finalizers decide to run GC
            mm::ObjectFactory<gc::SameThreadMarkAndSweep>::FinalizerQueue queue(std::move(FinalizerQueue));
            FinalizerQueue = mm::ObjectFactory<gc::SameThreadMarkAndSweep>::FinalizerQueue();
            size_t queueSize = queue.size();
            uint64_t startTimeUs = konan::getTimeMicros();
            queue.Finalize();
            RuntimeLogDebug({kTagGC},
                            "Finalized %zd objects on thread %d in %" PRIu64 " microseconds.",
                            queueSize,
                            konan::currentThreadId(),
                            konan::getTimeMicros() - startTimeUs);
            state = gc_.state_.get();
        }
    }
    threadData_.suspensionData().suspendIfRequested();
#ifdef KONAN_NO_THREADS
    if (state == GCState::kNeedsGC) {
        RuntimeLogDebug({kTagGC}, "Attempt to GC at SafePoint");
        gc_.PerformFullGC();
    }
#endif
}

gc::SameThreadMarkAndSweep::SameThreadMarkAndSweep() noexcept : state_(gNeedSafepointSlowpath) {
    mm::GlobalData::Instance().gcScheduler().SetScheduleGC([this]() NO_EXTERNAL_CALLS_CHECK NO_INLINE {
        RuntimeLogDebug({kTagGC}, "Scheduling GC by thread %d", konan::currentThreadId());
        state_.compareAndSet(GCState::kNone, GCState::kNeedsGC);
    });
#ifndef KONAN_NO_THREADS
    gcThread = std::thread([this] {
        while (true) {
            auto state = state_.waitUntil([this] {
                auto state = state_.get();
                return state == GCState::kNeedsGC || state == GCState::kShutdown;
            });
            if (state_.get() == GCState::kNeedsGC) {
                PerformFullGC();
            } else if (state == GCState::kShutdown){
                break;
            } else {
                RuntimeFail("GC thread wake up in strange state %d", static_cast<int>(state));
            }
        }
    });
#endif
}

gc::SameThreadMarkAndSweep::~SameThreadMarkAndSweep() {
#ifndef KONAN_NO_THREADS
    state_.waitUntil(
        [this] {
            auto state = state_.get();
            return state == GCState::kNone || state != GCState::kNeedsGC || state == GCState::kNeedsFinalizersRun;
        },
        [this] {
            state_.compareAndSet(state_.get(), GCState::kShutdown);
        }
    );
    gcThread.join();
#endif
}

bool gc::SameThreadMarkAndSweep::PerformFullGC() noexcept {
    RuntimeLogDebug({kTagGC}, "Attempt to suspend threads by thread %d", konan::currentThreadId());
    auto timeStartUs = konan::getTimeMicros();
    bool didSuspend = mm::RequestThreadsSuspension();
    if (!didSuspend) {
        RuntimeLogDebug({kTagGC}, "Failed to suspend threads by thread %d", konan::currentThreadId());
        // Somebody else suspended the threads, and so ran a GC.
        // TODO: This breaks if suspension is used by something apart from GC.
        return false;
    }
    RuntimeLogDebug({kTagGC}, "Requested thread suspension by thread %d", konan::currentThreadId());
    if (!state_.compareAndSet(GCState::kNeedsGC, GCState::kNeedsSuspend)) {
        RuntimeFail("Someone steel kNeedsGC state before moving to kNeedsSuspend");
    }

    NativeOrUnregisteredThreadGuard guard;

    mm::WaitForThreadsSuspension();
    auto timeSuspendUs = konan::getTimeMicros();
    RuntimeLogDebug({kTagGC}, "Suspended all threads in %" PRIu64 " microseconds", timeSuspendUs - timeStartUs);

    auto& scheduler = mm::GlobalData::Instance().gcScheduler();
    scheduler.gcData().OnPerformFullGC();

    RuntimeLogInfo(
            {kTagGC}, "Started GC epoch %zu. Time since last GC %" PRIu64 " microseconds", epoch_, timeStartUs - lastGCTimestampUs_);
    KStdVector<ObjHeader*> graySet;
    for (auto& thread : mm::GlobalData::Instance().threadRegistry().LockForIter()) {
        // TODO: Maybe it's more efficient to do by the suspending thread?
        thread.Publish();
        thread.gcScheduler().OnStoppedForGC();
        size_t stack = 0;
        size_t tls = 0;
        for (auto value : mm::ThreadRootSet(thread)) {
            if (!isNullOrMarker(value.object)) {
                graySet.push_back(value.object);
                switch (value.source) {
                    case mm::ThreadRootSet::Source::kStack:
                        ++stack;
                        break;
                    case mm::ThreadRootSet::Source::kTLS:
                        ++tls;
                        break;
                }
            }
        }
        RuntimeLogDebug({kTagGC}, "Collected root set for thread stack=%zu tls=%zu", stack, tls);
    }
    mm::StableRefRegistry::Instance().ProcessDeletions();
    size_t global = 0;
    size_t stableRef = 0;
    for (auto value : mm::GlobalRootSet()) {
        if (!isNullOrMarker(value.object)) {
            graySet.push_back(value.object);
            switch (value.source) {
                case mm::GlobalRootSet::Source::kGlobal:
                    ++global;
                    break;
                case mm::GlobalRootSet::Source::kStableRef:
                    ++stableRef;
                    break;
            }
        }
    }
    auto timeRootSetUs = konan::getTimeMicros();
    RuntimeLogDebug({kTagGC}, "Collected global root set global=%zu stableRef=%zu", global, stableRef);

    // Can be unsafe, because we've stopped the world.
    auto objectsCountBefore = mm::GlobalData::Instance().objectFactory().GetSizeUnsafe();

    RuntimeLogInfo(
            {kTagGC}, "Collected root set of size %zu of which %zu are stable refs in %" PRIu64 " microseconds", graySet.size(),
            stableRef, timeRootSetUs - timeSuspendUs);
    gc::Mark<MarkTraits>(std::move(graySet));
    auto timeMarkUs = konan::getTimeMicros();
    RuntimeLogDebug({kTagGC}, "Marked in %" PRIu64 " microseconds", timeMarkUs - timeRootSetUs);
    gc::SweepExtraObjects<SweepTraits>(mm::GlobalData::Instance().extraObjectDataFactory());
    auto timeSweepExtraObjectsUs = konan::getTimeMicros();
    RuntimeLogDebug({kTagGC}, "Sweeped extra objects in %" PRIu64 " microseconds", timeSweepExtraObjectsUs - timeMarkUs);
    gc::Sweep<SweepTraits>(mm::GlobalData::Instance().objectFactory(), FinalizerQueue);
    auto timeSweepUs = konan::getTimeMicros();
    RuntimeLogDebug({kTagGC}, "Sweeped in %" PRIu64 " microseconds", timeSweepUs - timeSweepExtraObjectsUs);

    // Can be unsafe, because we've stopped the world.
    auto objectsCountAfter = mm::GlobalData::Instance().objectFactory().GetSizeUnsafe();
    auto extraObjectsCountAfter = mm::GlobalData::Instance().extraObjectDataFactory().GetSizeUnsafe();

    if (!state_.compareAndSet(GCState::kNeedsSuspend, GCState::kGCRunning)) {
        RuntimeFail("Someone changed kNeedsSuspend during stop-the-world-phase");
    }
    auto newState = FinalizerQueue.size() ? GCState::kNeedsFinalizersRun : GCState::kNone;
    if (!state_.compareAndSet(GCState::kGCRunning, newState)) {
        RuntimeLogDebug({kTagGC}, "New GC is already scheduled while finishing previous one");
    }
    mm::ResumeThreads();
    auto timeResumeUs = konan::getTimeMicros();

    RuntimeLogDebug({kTagGC}, "Resumed threads in %" PRIu64 " microseconds.", timeResumeUs - timeSweepUs);

    auto finalizersCount = FinalizerQueue.size();
    auto collectedCount = objectsCountBefore - objectsCountAfter - finalizersCount;

    RuntimeLogInfo(
            {kTagGC},
            "Finished GC epoch %zu. Collected %zu objects, to be finalized %zu objects, %zu objects and %zd extra data objects remain. Total pause time %" PRIu64
            " microseconds",
            epoch_, collectedCount, finalizersCount, objectsCountAfter, extraObjectsCountAfter, timeResumeUs - timeStartUs);
    ++epoch_;
    lastGCTimestampUs_ = timeResumeUs;

    return true;
}
