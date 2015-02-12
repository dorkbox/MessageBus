package net.engio.mbassy.multi.common;

public abstract class InterruptRunnable implements Runnable {
    protected volatile boolean running = true;

    public InterruptRunnable() {
        super();
    }

    public void stop() {
        this.running = false;
    }
}
