package org.nustaq.reallive.api;

import org.nustaq.kontraktor.IPromise;
import org.nustaq.kontraktor.Promise;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by ruedi on 06/08/15.
 */
public interface RealLiveTable extends SafeRealLiveTable, ChangeStream, RealLiveStreamActor {

    /**
     * apply the function to the record with given key and return the result inside a promise
     *
     * changes to the record inside the function are applied to the real record and a change message
     * is generated.
     *
     * In case the function returns a changemessage (add,putRecord,remove ..), the change message is applied
     * to the original record and broadcasted. Else the result of the action function is passed to the remote callee
     *
     * @param key
     * @param action
     * @return the result of function.
     */
    IPromise atomic(String key, RLFunction<Record,Object> action);

    /**
     * mass update.
     *
     * @param filter - selects records
     * @param action - function, the function might modify the record using putField. If false is returned, the record is deleted
     */
    void atomicUpdate(RLPredicate<Record> filter, RLFunction<Record, Boolean> action);

    void unsubscribeById(int subsId);

    default IPromise<List<Record>> queryList(RLPredicate<Record> condition) {
        Promise prom = new Promise();
        List<Record> res = new ArrayList<>();
        forEach(condition, (r,e) -> {
            if ( r != null ) {
                res.add(r);
            } else {
                prom.resolve(res);
            }
        });
        return prom;
    }

    default IPromise<Record> find(RLPredicate<Record> condition) {
        Promise prom = new Promise();
        queryList(condition).then( (r,e) -> {
            if ( e != null )
                prom.reject(e);
            if ( r == null ) {
                prom.reject("expected list but got null");
            }
            prom.resolve(r.size() == 0 ? null : r.get(0));
        });
        return prom;
    }

}
