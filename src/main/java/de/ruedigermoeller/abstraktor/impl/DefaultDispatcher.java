package de.ruedigermoeller.abstraktor.impl;

import de.ruedigermoeller.abstraktor.*;
import de.ruedigermoeller.abstraktor.sample.balancing.SubActor;
import de.ruedigermoeller.abstraktor.sample.balancing.WorkerActor;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Copyright (c) 2012, Ruediger Moeller. All rights reserved.
 * <p/>
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * <p/>
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * <p/>
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 * <p/>
 * Date: 03.01.14
 * Time: 22:19
 * To change this template use File | Settings | File Templates.
 */

/**
 * Implements the default dispatcher/scheduling of actors.
 * For each actor created from "outside" (not from within another actor). A new Dispatcher is created
 * automatically. An actor created from within another actor inherits the dispatcher of the enclosing actor
 * by default.
 * Calls from actors sharing the same dispatcher are done directly (no queueing). Calls across actors in
 * different dispatchers are put to the queue of the receiving dispatcher. Note that cross-dispatcher calls
 * are like 1000 times slower than inbound calls.
 *
 * Each dispatcher owns exactly one single thread.
 * Note that dispatchers must be terminated if not needed any longer, as a thread is associated with them.
 *
 * For more sophisticated applications it might be appropriate to manually set up dispatchers (Actors.newDispatcher()).
 * The Actors.New method allows to specifiy a dedicated dispatcher on which to run the actor. This way it is possible
 * to exactly balance and control the number of threads created and which thread operates a set of actors.
 *
 */
public class DefaultDispatcher implements Dispatcher {

    public static AtomicInteger instanceCount = new AtomicInteger(0);

    final int SENTINEL_MASK = 1024-1;

    Thread worker;
    AtomicBoolean shutDown = new AtomicBoolean(false);
    private volatile boolean dead;

    ConcurrentHashMap<Actor,Boolean> scheduledActors = new ConcurrentHashMap<>();

    int instanceNum;
    boolean isSystemDispatcher;
    private int queueSize;

    public int getQueueSize() {
        return queueSize;
    }

    static class CallEntry {
        final private Actor sender;
        final private Actor target;
        final private Method method;
        final private Object[] args;
        final boolean sentinel;

        CallEntry(Actor sender, Actor actor, Method method, Object[] args, boolean sentinel) {
            this.sender = sender;
            this.target = actor;
            this.method = method;
            this.args = args;
            this.sentinel = sentinel;
        }

        public Actor getSender() {
            return sender;
        }
        public Actor getTarget() {
            return target;
        }
        public Method getMethod() {
            return method;
        }
        public Object[] getArgs() { return args; }
        public boolean isSentinel() { return sentinel; }
    }

    public DefaultDispatcher() {
        instanceNum = instanceCount.incrementAndGet();
        start();
    }

    @Override
    public String toString() {
        return "DefaultDispatcher{" +
                "worker=" + worker + " sched: "+scheduledActors.size()+
                '}';
    }

    public boolean isSystemDispatcher() {
        return isSystemDispatcher;
    }

    public void setSystemDispatcher(boolean isSystemDispatcher) {
        this.isSystemDispatcher = isSystemDispatcher;
    }

//    static ConcurrentHashMap debug = new ConcurrentHashMap();
    @Override
    public void dispatch(ActorProxy actorRef, boolean sameThread, Method method, Object args[]) {
        // MT. sequential per actor ref
        if ( dead )
            throw new RuntimeException("received message on terminated dispatcher "+this);
        Actor sender = Actor.__sender.get();
        if ( sender != null ) {
//            String out = "sender " + sender.getActor().getClass().getSimpleName() + " to " + actorRef.getActor().getClass().getSimpleName()+"."+method.getName();
//            if (! debug.containsKey(out) ) {
//                debug.put(out,out);
//                System.out.println(out);
//            }
            sender.__genCalls++;
        }
        plainDispatch( actorRef.getActor(), method, args, false );
    }

    protected void plainDispatch(Actor actor, Method method, Object[] args, boolean sentinel) {
        CallEntry e = new CallEntry(Actor.__sender.get(), actor, method, args, sentinel);
        int count = 0;
        while ( ! actor.__queue.offer(e) ) {
            yield(count);
            count++;
        }
    }

    public void start() {
        worker = new Thread("DefaultDispatcher "+instanceNum) {
            public void run() {
                Actors.threadDispatcher.set(DefaultDispatcher.this);
                int emptyCount = 0;
                while( ! shutDown.get() ) {
                    if ( poll() )
                        emptyCount = 0;
                    else
                        emptyCount++;
                    DefaultDispatcher.this.yield(emptyCount);
                }
                dead = true;
                instanceCount.decrementAndGet();
            }
        };
        worker.start();
    }

    final int YIELD_THRESH = 100000;
    final int PARK_THRESH = YIELD_THRESH+50000;
    final int SLEEP_THRESH = PARK_THRESH+5000;
    protected void yield(int count) {
        if ( count > SLEEP_THRESH ) {
            LockSupport.parkNanos(1000*500);
        } else if ( count > PARK_THRESH ) {
            LockSupport.parkNanos(1);
        } else {
            if ( count > YIELD_THRESH)
                Thread.yield();
        }
    }

    public void calcQSize() {
        queueSize = 0;
        for (Iterator<Actor> iterator = scheduledActors.keySet().iterator(); iterator.hasNext(); ) {
            Actor current = iterator.next();
            queueSize+=current.__queue.size();
        }
    }

    protected boolean poll() {
        boolean anyOne = false;
        for (Iterator<Actor> iterator = scheduledActors.keySet().iterator(); iterator.hasNext(); ) {
            Actor current = iterator.next();
            if ( pollActor(current) ) {
                anyOne = true;
                current.__emptyCount = 0;
            } else {
                if ( current.__queue.isEmpty() ) {
                    if ( current.__emptyCount == 10 ) {
                        iterator.remove();
                        current.__emptyCount = 0;
                    } else {
                        current.__emptyCount++;
                    }
                }
            }
        }
        return anyOne;
    }

    // ret true when yield should be triggered. read queue of actor current
    private boolean pollActor(Actor current) {
        boolean yield = true;
        Actor.__sender.set(current);
        int bulk = current.__genCalls < 1000 ? 100 : 1;
//        int bulk = 100;
        current.__genCalls=0;
        while ( bulk-- > 0 ) {
            CallEntry poll = (CallEntry) current.__queue.poll();
            if ( poll != null ) {
                try {
                    poll.getMethod().invoke(current,poll.getArgs());
                    yield = false;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                break;
            }
        }
//        if ( current.__genCalls > 0 )
//            System.out.println("sender "+current.getClass().getName()+" created "+current.__genCalls);
        return !yield;
    }

    public Thread getWorker() {
        return worker;
    }

    public Marshaller instantiateMarshaller(Actor target) {
        return new Marshaller() {
            HashMap<String, Method> methodCache = new HashMap<>();

            @Override
            public boolean isSameThread(String methodName, ActorProxy proxy) {
                return proxy.getDispatcher().getWorker() == Thread.currentThread();
            }

            @Override
            /**
             * callback from bytecode weaving
             */
            public boolean doDirectCall(String methodName, ActorProxy proxy) {
                return false; // proxy.getActor().__outCalls == 0;
            }

            @Override
            public void dispatchCall( ActorProxy senderRef, boolean sameThread, String methodName, Object args[] ) {
                // here sender + receiver are known in a ST context
                Method method = methodCache.get(methodName);
                Actor actor = senderRef.getActor();
                if ( method == null ) {
                    Method[] methods = actor.getClass().getMethods();
                    for (int i = 0; i < methods.length; i++) {
                        Method m = methods[i];
                        if ( m.getName().equals(methodName) ) {
                            methodCache.put(methodName,m);
                            method = m;
                            break;
                        }
                    }
                }
                actor.getDispatcher().dispatch(senderRef, sameThread, method, args);
                scheduledActors.put(actor, Boolean.TRUE);
            }
        };
    }

    /**
     * stop processing messages, but do not block adding of new messages to Q
     */
    @Override
    public void pauseOperation() {
        throw new RuntimeException("unimplemented");
    }

    /**
     * continue processing messages
     */
    @Override
    public void continueOperation() {
        throw new RuntimeException("unimplemented");
    }

    /**
     * @return true if Dispatcher is not shut down
     */
    @Override
    public boolean isAlive() {
        return ! shutDown.get();
    }

    /**
     * @return true if Dispatcher is paused but not shut-down
     */
    @Override
    public boolean isPaused() {
        return false;
    }

    /**
     * terminate operation after emptying Q
     */
    @Override
    public void shutDown() {
        shutDown.set(true);
    }

    /**
     * terminate operation immediately. Pending messages in Q are lost
     */
    @Override
    public void shutDownImmediate() {
        throw new RuntimeException("unimplemented");
    }

    /**
     * blocking method, use for debugging only.
     */
    public void waitEmpty() {
        while( scheduledActors.size() > 0 )
            LockSupport.parkNanos(1000*50);
    }


}
