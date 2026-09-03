package me.mdbell.noexs.core.utils;

import java.util.concurrent.Semaphore;

public class AutoReleaseSemaphore<E extends RuntimeException> implements AutoCloseable {

    private Semaphore semaphore;
    private final Class<E> exceptionType;

    public AutoReleaseSemaphore(int permits, Class<E> exceptionType) {
        this.semaphore = new Semaphore(permits);
        this.exceptionType = exceptionType;
    }

    public AutoReleaseSemaphore(Semaphore semaphore, Class<E> exceptionType) {
        this.semaphore = semaphore;
        this.exceptionType = exceptionType;
    }

    public AutoReleaseSemaphore<E> acquire() {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
            RuntimeException re = null;
            try {
                re = (RuntimeException) exceptionType.newInstance();
                re.initCause(e);
            } catch (Throwable t) {
                t.printStackTrace();
            }

            if (re != null) {
                throw re;
            }
        }
        return this;
    }

    public void release() {
        semaphore.release();
    }

    @Override
    public void close() throws RuntimeException {
        release();
    }

}
