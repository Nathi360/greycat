package org.kevoree.modeling.scheduler.impl;

import org.kevoree.modeling.scheduler.KScheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * @ignore ts
 */
public class ExecutorServiceScheduler implements KScheduler {

    private ExecutorService _service = new ForkJoinPool(Runtime.getRuntime().availableProcessors(), ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true);

    @Override
    public void dispatch(Runnable p_runnable) {
        _service.submit(p_runnable);
    }

    @Override
    public void stop() {
        _service.shutdown();
    }
    
}
