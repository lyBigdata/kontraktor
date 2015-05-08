package org.nustaq.kontraktor.remoting.base;

import org.nustaq.kontraktor.Actor;
import org.nustaq.kontraktor.Actors;
import org.nustaq.kontraktor.IPromise;
import org.nustaq.kontraktor.Promise;
import org.nustaq.kontraktor.impl.BackOffStrategy;
import org.nustaq.kontraktor.remoting.RemoteRegistry;
import org.nustaq.kontraktor.util.Log;

import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.LockSupport;

/**
 * polls queues of remote actor proxies and serializes messages to their associated object sockets.
 *
 * Terminated / Disconnected remote actors (registries) are removed from the entry list,
 * so regular actor messages sent to a terminated remote actor queue up in its mailbox.
 * Callbacks/Future results from exported callbacks/futures still reach the object socket
 * as these are redirected directly inside serializers. Those queue up in the webobjectsocket's list,
 * as flush is not called anymore because of removement from SendLoop list.
 *
 * in short: regular messages to disconected remote actors queue up in mailbox, callbacks in object socket buffer
 *
 * This is a rewrite designed to run inside the facade actor's thread instead of scheduling a separate thread.
 *
 */
public class RemoteRefPolling implements Runnable {

    ArrayList<ActorServerAdapter.ScheduleEntry> sendJobs = new ArrayList<>();

    public RemoteRefPolling() {
    }

    /**
     * return a future which is completed upon connection close
     *
     * @param reg
     * @return
     */
    public IPromise scheduleSendLoop(RemoteRegistry reg) {
        if ( Actor.sender.get() == null )
            throw new RuntimeException("must be used from inside an actor thread or provide an executor");
        Promise promise = new Promise();
        synchronized (sendJobs) {
            sendJobs.add(new ActorServerAdapter.ScheduleEntry(reg, promise));
            if ( sendJobs.size() == 1 ) {
                Actor.sender.get().execute(this);
            }
        }
        return promise;
    }

    public void run() {
        int count = 1;
        int maxit = 50;
        while ( maxit > 0 && count > 0) {
            count = 0;
            synchronized (sendJobs) {
                for (int i = 0; i < sendJobs.size(); i++) {
                    ActorServerAdapter.ScheduleEntry entry = sendJobs.get(i);
                    if ( entry.reg.isTerminated() ) {
                        terminateEntry(i, entry, "terminated", null );
                        i--;
                        continue;
                    }
                    try {
                        if (entry.reg.pollAndSend2Remote(entry.reg.getWriteObjectSocket().get())) {
                            count++;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        terminateEntry(i, entry, null, e);
                        i--;
                    }
                }
                maxit--;
            }
        }
        if ( sendJobs.size() > 0 ) {
            if (count == 0)
                Actor.sender.get().delayed(2, this);
            else
                Actor.sender.get().execute(this);
        }
    }

    protected void terminateEntry(int i, ActorServerAdapter.ScheduleEntry entry, Object res, Exception e) {
        entry.reg.stopRemoteRefs();
        sendJobs.remove(i);
        entry.promise.complete(res,e);
    }

}
