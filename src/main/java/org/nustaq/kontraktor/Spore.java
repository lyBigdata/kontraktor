package org.nustaq.kontraktor;

import org.nustaq.kontraktor.annotations.InThread;
import org.nustaq.kontraktor.impl.CallbackWrapper;
import org.nustaq.serialization.annotations.AnonymousTransient;

import java.io.Serializable;

/**
 * Created by ruedi on 26.08.2014.
 *
 * A Spore is sent to a foreign actor executes on its data and sends results back to caller
 */
@AnonymousTransient
public abstract class Spore<I,O> implements Serializable {

    Callback cb;
    transient protected boolean finished;
    transient Callback<O> localCallback;
    transient Promise finSignal = new Promise();

    public Spore() {
        Callback mycb = new Callback() {
            @Override
            public void receive(Object result, Object error) {
                if ( FIN.equals(error) ) {
                    finSignal.signal();
                } else {
                    if (localCallback != null) {
                        localCallback.receive((O) result, error);
                    } else {
                        System.err.println("set callback using then() prior sending");
                    }
                }
            }
        };
        this.cb = new CallbackWrapper<>(Actor.sender.get(),mycb);
    }

    /**
     * implements code to be executed at receiver side
     * @param input
     */
    public abstract void remote( I input );

    /**
     * local. Register at sending side and will recieve data streamed back from remote.
     *
     * @param cb
     * @return a future triggered
     */
    public Spore<I,O> forEach(Callback<O> cb) {
        if ( localCallback != null ) {
            throw new RuntimeException("forEachResult callback handler can only be set once.");
        }
        localCallback = cb;
        return this;
    }

    public Spore<I,O> onFinish( Runnable toRun) {
        finSignal.then(toRun);
        return this;
    }

    /**
     * to be called at remote side
     * when using streaming to deliver multiple results, call this in order to signal no further
     * results are expected.
     */
    public void finished() {
        // signal finish of execution, so remoting can clean up callback id mappings
        // override if always single result or finish can be emitted by the remote method
        // note one can send FINSILENT to avoid the final message to be visible to receiver callback/spore
        cb.receive(null, Callback.FIN);
        finished = true;
    }

    /**
     * note that sending an error implicitely will close the backstream.
     * @param err
     */
    protected void streamError(Object err) {
        cb.receive(null, err);
    }

    protected void stream(O result) {
        cb.receive(result, Actor.CONT);
    }

    /**
     * to be read at remote side in order to decide wether to stop e.g. iteration.
     * @return
     */
    public boolean isFinished() {
        return finished;
    }
}
