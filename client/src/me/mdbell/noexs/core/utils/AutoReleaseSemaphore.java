package me.mdbell.noexs.core.utils;

import java.util.concurrent.Semaphore;

public class AutoReleaseSemaphore<E extends RuntimeException> implements AutoCloseable {

    private static final org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger(AutoReleaseSemaphore.class);

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
            logger.error("Unexpected error", e);
            RuntimeException re = null;
            try {
                re = (RuntimeException) exceptionType.newInstance();
                re.initCause(e);
            } catch (Throwable t) {
                logger.error("Unexpected error", t);
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
