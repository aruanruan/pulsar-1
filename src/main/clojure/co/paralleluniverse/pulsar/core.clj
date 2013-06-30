 ; Pulsar: lightweight threads and Erlang-like actors for Clojure.
 ; Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
 ;
 ; This program and the accompanying materials are dual-licensed under
 ; either the terms of the Eclipse Public License v1.0 as published by
 ; the Eclipse Foundation
 ;
 ;   or (per the licensee's choosing)
 ;
 ; under the terms of the GNU Lesser General Public License version 3.0
 ; as published by the Free Software Foundation.

;;;
;;;
;;;
;;;

(ns co.paralleluniverse.pulsar.core
  "Pulsar is an implementation of lightweight threads (fibers),
  Go-like channles and Erlang-like actors for the JVM"
(:import [java.util.concurrent TimeUnit ExecutionException TimeoutException Future]
         [jsr166e ForkJoinPool ForkJoinTask]
         [co.paralleluniverse.strands Strand Stranded]
         [co.paralleluniverse.strands SuspendableCallable]
         [co.paralleluniverse.fibers DefaultFiberPool Fiber Joinable FiberUtil]
         [co.paralleluniverse.fibers.instrument]
         [co.paralleluniverse.strands.channels Channel ReceiveChannel SendChannel Channel$OverflowPolicy 
          ChannelGroup Topic
          ObjectChannel IntChannel LongChannel FloatChannel DoubleChannel
          TickerChannel TickerChannel$TickerChannelConsumer
          ObjectTickerChannel IntTickerChannel LongTickerChannel FloatTickerChannel DoubleTickerChannel
          IntSendChannel LongSendChannel FloatSendChannel DoubleSendChannel
          IntReceiveChannel LongReceiveChannel FloatReceiveChannel DoubleReceiveChannel]
         [co.paralleluniverse.strands.dataflow DelayedVal]
         [co.paralleluniverse.pulsar ClojureHelper]
         ; for types:
         [clojure.lang Keyword Sequential IObj IFn IMeta IDeref ISeq IPersistentCollection IPersistentVector IPersistentMap])
(:require [co.paralleluniverse.pulsar.interop :refer :all]
          [clojure.core.typed :refer [ann def-alias Option AnyInteger]]))

;; ## clojure.core type annotations

(ann clojure.core/split-at (All [x] (Fn [Long (IPersistentCollection x) -> (IPersistentVector (IPersistentCollection x))])))
(ann clojure.core/coll? [Any -> Boolean :filters {:then (is (IPersistentCollection Any) 0) :else (! (IPersistentCollection Any) 0)}])
(ann clojure.core/partition-all (All [x] (Fn [Long (ISeq x) -> (ISeq (U (ISeq x) x))])))
(ann clojure.core/into (All [[xs :< (IPersistentCollection Any)]] (Fn [xs (IPersistentCollection Any) -> xs])))
(ann clojure.core/set-agent-send-executor! [java.util.concurrent.ExecutorService -> nil])
(ann clojure.core/set-agent-send-off-executor! [java.util.concurrent.ExecutorService -> nil])

;; ## Private util functions
;; These are internal functions aided to assist other functions in handling variadic arguments and the like.

(defmacro dbg [& body]
  {:no-doc true}
  `(let [x# ~@body
         y#    (if (seq? x#) (take 20 x#) x#)
         more# (if (seq? x#) (nthnext x# 20) false)]
     (println "dbg:" '~@body "=" y# (if more# "..." ""))
     x#))

;; from core.clj:
(defmacro ^{:private true} assert-args
  [& pairs]
  `(do (when-not ~(first pairs)
         (throw (IllegalArgumentException.
                 (str (first ~'&form) " requires " ~(second pairs) " in " ~'*ns* ":" (:line (meta ~'&form))))))
     ~(let [more (nnext pairs)]
        (when more
          (list* `assert-args more)))))

(ann sequentialize (All [x y]
                        (Fn
                         [(Fn [x -> y]) ->
                          (Fn [x -> y]
                              [(ISeq x) -> (ISeq y)]
                              [x * -> (ISeq y)])])))
(defn- sequentialize
  "Takes a function of a single argument and returns a function that either takes any number of arguments or a
  a single sequence, and applies the original function to each argument or each element of the sequence"
  [f]
  (fn
    ([x] (if (seq? x) (map f x) (f x)))
    ([x & xs] (map f (cons x xs)))))

;;     (surround-with nil 4 5 6) -> (4 5 6)
;;     (surround-with '(1 2 3) 4 5 6) -> ((1 2 3 4 5 6))
;;     (surround-with '(1 (2)) '(3 4)) -> ((1 (2) (3 4)))
(ann surround-with [(ISeq Any) Any * -> (ISeq Any)])
(defn surround-with
  [expr & exprs]
  (if (nil? expr)
    exprs
    (list (concat expr exprs))))

;;     (deep-surround-with '(1 2 3) 4 5 6) -> (1 2 3 4 5 6)
;;     (deep-surround-with '(1 2 (3)) 4 5 6) -> (1 2 (3 4 5 6))
;;     (deep-surround-with '(1 2 (3 (4))) 5 6 7) -> (1 2 (3 (4 5 6 7)))
(ann ^:nocheck deep-surround-with [(ISeq Any) Any * -> (ISeq Any)])
(defn- deep-surround-with
  [expr & exprs]
  (if (not (coll? (last expr)))
    (concat expr exprs)
    (concat (butlast expr) (list (apply deep-surround-with (cons (last expr) exprs))))))

(ann ops-args [(ISeq (Vector* (Fn [Any -> Boolean]) Any)) (ISeq Any) -> (ISeq Any)])
(defn- ops-args
  [pds xs]
  "Used to simplify optional parameters in functions.
  Takes a sequence of [predicate? default] pairs, and a sequence of arguments. Tests the first predicate against
  the first argument. If the predicate succeeds, emits the argument's value; if not - the default, and tries the
  next pair with the argument. Any remaining arguments are copied to the output as-is."
  (if (seq pds)
    (let [[p? d] (first pds)
          x      (first xs)]
      (if (p? x)
        (cons x (ops-args (rest pds) (rest xs)))
        (cons d (ops-args (rest pds) xs))))
    (seq xs)))

(ann ^:nocheck kps-args [(ISeq Any) -> (Vector* (ISeq Any) (ISeq Any))])
(defn kps-args
  {:no-doc true}
  [args]
  (let [aps (partition-all 2 args)
        [opts-and-vals ps] (split-with #(keyword? (first %)) aps)
        options (into {} (map vec opts-and-vals))
        positionals (reduce into [] ps)]
    [options positionals]))

(ann extract-keys [(ISeq Keyword) (ISeq Any) -> (Vector* (ISeq Any) (ISeq Any))])
(defn- extract-keys
  [ks pargs]
  (if (not (seq ks))
    [[] pargs]
    (let [[k ps] (split-with #(= (first ks) (first %)) pargs)
          [rks rpargs] (extract-keys (next ks) ps)]
      [(vec (cons (first k) rks)) rpargs])))

(ann merge-meta (All [[x :< clojure.lang.IObj] [y :< (IPersistentMap Keyword Any)]]
                     [x y -> (I x (IMeta y))]))
(defn merge-meta
  {:no-doc true}
  [s m]
  (with-meta s (merge-with #(%1) m (meta s))))

(defn apply-variadic
  "Calls a variadic function by applying a concat of all arguments with the last argument (which is supposedly a collection)"
  {:no-doc true}
  [f & args]
  (apply f (concat (butlast args) (last args))))

(ann as-timeunit [Keyword -> TimeUnit])
(defn ^TimeUnit as-timeunit
  "Converts a keyword to a java.util.concurrent.TimeUnit
  <pre>
  :nanoseconds | :nanos | :ns   -> TimeUnit/NANOSECONDS
  :microseconds | :us           -> TimeUnit/MICROSECONDS
  :milliseconds | :millis | :ms -> TimeUnit/MILLISECONDS
  :seconds | :sec               -> TimeUnit/SECONDS
  :minutes | :mins              -> TimeUnit/MINUTES
  :hours | :hrs                 -> TimeUnit/HOURS
  :days                         -> TimeUnit/DAYS
  </pre>
  "
  [x]
  (case x
    (:nanoseconds :nanos :ns)   TimeUnit/NANOSECONDS
    (:microseconds :us)         TimeUnit/MICROSECONDS
    (:milliseconds :millis :ms) TimeUnit/MILLISECONDS
    (:seconds :sec)             TimeUnit/SECONDS
    (:minutes :mins)            TimeUnit/MINUTES
    (:hours :hrs)               TimeUnit/HOURS
    :days                       TimeUnit/DAYS))

(ann ->timeunit [(U TimeUnit Keyword) -> TimeUnit])
(defn ^TimeUnit ->timeunit
  [x]
  (if (instance? TimeUnit x)
    x
    (as-timeunit x)))

(defn convert-duration
  [x from-unit to-unit]
  (.convert (->timeunit to-unit) x (->timeunit from-unit)))

(ann tagged-tuple? [Any -> Boolean])
(defn tagged-tuple?
  [x]
  (and (vector? x) (keyword? (first x))))

(ann unwrap-exception* [Throwable -> Throwable])
(defn unwrap-exception*
  {:no-doc true}
  [^Throwable e]
  (if
    (or (instance? ExecutionException e)
        (and (= (.getClass e) RuntimeException) (.getCause e)))
    (unwrap-exception* (.getCause e))
    e))

(defmacro unwrap-exception
  {:no-doc true}
  [& body]
  `(try
     ~@body
     (catch Exception e#
       (throw (unwrap-exception* e#)))))

;; ## Fork/Join Pool

(ann in-fj-pool? [-> Boolean])
(defn- in-fj-pool?
  "Returns true if we're running inside a fork/join pool; false otherwise."
  []
  (ForkJoinTask/inForkJoinPool))

(ann current-fj-pool [-> ForkJoinPool])
(defn- ^ForkJoinPool current-fj-pool
  "Returns the fork/join pool we're running in; nil if we're not in a fork/join pool."
  []
  (ForkJoinTask/getPool))

(ann make-fj-pool [AnyInteger Boolean -> ForkJoinPool])
(defn ^ForkJoinPool make-fj-pool
  "Creates a new ForkJoinPool with the given parallelism and with the given async mode"
  [^Integer parallelism ^Boolean async]
  (ForkJoinPool. parallelism jsr166e.ForkJoinPool/defaultForkJoinWorkerThreadFactory nil async))

(ann fj-pool ForkJoinPool)
(def fj-pool
  "A global fork/join pool. The pool uses all available processors and runs in the async mode."
  (DefaultFiberPool/getInstance))

;; ***Make agents use the global fork-join pool***

(set-agent-send-executor! fj-pool)
(set-agent-send-off-executor! fj-pool)

;; ## Suspendable functions
;; Only functions that have been especially instrumented can perform blocking actions
;; while running in a fiber.

(ann suspendable? [IFn -> Boolean])
(defn suspendable?
  "Returns true of a function has been instrumented as suspendable; false otherwise."
  [f]
  (.isAnnotationPresent (.getClass ^Object f) co.paralleluniverse.fibers.Instrumented))

(ann suspendable! (Fn [IFn -> IFn]
                      [IFn * -> (ISeq IFn)]
                      [(ISeq IFn) -> (ISeq IFn)]))
(defn suspendable!
  "Makes a function suspendable"
  ([f]
   (ClojureHelper/retransform f nil))
  ([x prot]
   (ClojureHelper/retransform x prot)))

(ann ->suspendable-callable [[Any -> Any] -> SuspendableCallable])
(defn ^SuspendableCallable ->suspendable-callable
  "wrap a clojure function as a SuspendableCallable"
  {:no-doc true}
  [f]
  (ClojureHelper/asSuspendableCallable f))

(defmacro susfn
  "Creates a suspendable function that can be used by a fiber or actor"
  [& expr]
  `(suspendable! (fn ~@expr)))

(defmacro defsusfn
  "Defines a suspendable function that can be used by a fiber or actor"
  [& expr]
  `(do
     (defn ~@expr)
     (suspendable! ~(first expr))))

(ann ^:nocheck strampoline (All [v1 v2 ...]
                                (Fn
                                 [(Fn [v1 v2 ... v2 -> Any]) v1 v2 ... v2 -> Any]
                                 [[-> Any] -> Any])))
(defsusfn strampoline
  "A suspendable version of trampoline. Should be used to implement
  finite-state-machine actors.

  trampoline can be used to convert algorithms requiring mutual
  recursion without stack consumption. Calls f with supplied args, if
  any. If f returns a fn, calls that fn with no arguments, and
  continues to repeat, until the return value is not a fn, then
  returns that non-fn value. Note that if you want to return a fn as a
  final value, you must wrap it in some data structure and unpack it
  after trampoline returns."
  ([f]
     (let [ret (f)]
       (if (fn? ret)
         (recur ret)
         ret)))
  ([f & args]
     (strampoline #(apply f args))))

;; ## Fibers

(ann get-pool [-> ForkJoinPool])
(defn ^ForkJoinPool get-pool
  {:no-doc true}
  [^ForkJoinPool pool]
  (or pool (current-fj-pool) fj-pool))

(ann fiber [String ForkJoinPool AnyInteger [Any -> Any] -> Fiber])
(defn ^Fiber fiber
  "Creates a new fiber (a lightweight thread) running in a fork/join pool."
  [& args]
  (let [[^String name ^ForkJoinPool pool ^Integer stacksize f] (ops-args [[string? nil] [#(instance? ForkJoinPool %) fj-pool] [integer? -1]] args)]
    (Fiber. name (get-pool pool) (int stacksize) (->suspendable-callable f))))

(ann start [Fiber -> Fiber])
(defn start
  "Starts a fiber"
  [^Fiber fiber]
  (.start fiber))

(defmacro spawn-fiber
  "Creates and starts a new fiber"
  [& args]
  (let [[{:keys [^String name ^Integer stack-size ^ForkJoinPool pool] :or {stack-size -1}} body] (kps-args args)]
    `(let [f#     (suspendable! ~(if (== (count body) 1) (first body) `(fn [] (apply ~@body))))
           fiber# (co.paralleluniverse.fibers.Fiber. ~name (get-pool ~pool) (int ~stack-size) (->suspendable-callable f#))]
       (.start fiber#))))

(ann current-fiber [-> Fiber])
(defn current-fiber
  "Returns the currently running lightweight-thread or nil if none"
  []
  (Fiber/currentFiber))

(ann current-fiber [-> Fiber])
(defn fiber->future
  "Takes a spawned fiber yields a future object that will
  invoke the function in another thread, and will cache the result and
  return it on all subsequent calls to deref/@. If the computation has
  not yet finished, calls to deref/@ will block, unless the variant
  of deref with timeout is used. See also - realized?."
  [f]
  (let [^Future fut (FiberUtil/toFuture f)]
    (reify
      clojure.lang.IDeref
      (deref [_] (.get fut))
      clojure.lang.IBlockingDeref
      (deref
       [_ timeout-ms timeout-val]
       (try (.get fut timeout-ms TimeUnit/MILLISECONDS)
          (catch TimeoutException e
            timeout-val)))
      clojure.lang.IPending
      (isRealized [_] (.isDone fut))
      Future
      (get [_] (.get fut))
      (get [_ timeout unit] (.get fut timeout unit))
      (isCancelled [_] (.isCancelled fut))
      (isDone [_] (.isDone fut))
      (cancel [_ interrupt?] (.cancel fut interrupt?)))))

;; ## Strands
;; A strand is either a thread or a fiber.

(ann current-strand [-> Strand])
(defn ^Strand current-strand
  "Returns the currently running fiber or current thread in case of new active fiber"
  []
  (Strand/currentStrand))

(ann alive? [Strand -> Boolean])
(defn alive?
  "Tests whether or not a strand is alive. A strand is alive if it has been started but has not yet died."
  [^Strand a]
  (.isAlive a))

(ann get-strand [Stranded -> Strand])
(defn get-strand
  [^Stranded x]
  (.getStrand x))

(defn spawn-thread
  "Creates and starts a new thread"
  [& args]
  (let [[{:keys [^String name]} body] (kps-args args)]
    (let [f      (if (== (count body) 1) (first body) (fn [] (apply (first body) (rest body))))
          thread (if name (Thread. ^Runnable f name) (Thread. ^Runnable f))]
       (.start thread)
       thread)))

(ann join* [(U Joinable Thread) -> (Option Any)])
(defn- join*
  ([s]
   (if (instance? Joinable s)
     (unwrap-exception
      (.get ^Joinable s))
     (Strand/join s)))
  ([timeout unit s]
   (if (instance? Joinable s)
     (unwrap-exception
      (.get ^Joinable s timeout (->timeunit unit)))
     (Strand/join s timeout (->timeunit unit)))))

(ann join (Fn [(U Joinable Thread) -> (Option Any)]
              [(Sequential (U Joinable Thread)) -> (ISeq Any)]))
(defn join
  ([s]
   (if (sequential? s)
     (doall (map join* s))
     (join* s)))
  ([timeout unit s]
   (if (sequential? s)
     (loop [nanos (long (convert-duration timeout unit :nanos))
            res []
            ss s]
       (when (not (pos? nanos))
         (throw (TimeoutException.)))
       (if (seq? ss)
         (let [start (long (System/nanoTime))
               r (join* (first ss) nanos TimeUnit/NANOSECONDS)]
           (recur (- nanos (- (System/nanoTime) start))
                  (conj res r)
                  (rest ss)))
         (seq res)))
     (join* timeout unit s))))

;; ## Promises

(defn promise
  "Returns a promise object that can be read with deref/@, and set,
  once only, with deliver. Calls to deref/@ prior to delivery will
  block, unless the variant of deref with timeout is used. All
  subsequent derefs will return the same delivered value without
  blocking. See also - realized?.

  Unlike clojure.core/promise, this promise object can be used inside Pulsar fibers."
  ([]
   (let [dv (DelayedVal.)]
     (reify
       clojure.lang.IDeref
       (deref [_]
              (.get dv))
       clojure.lang.IBlockingDeref
       (deref
        [_ timeout-ms timeout-val]
        (try
          (.get dv timeout-ms TimeUnit/MILLISECONDS)
          (catch TimeoutException e
            timeout-val)))
       clojure.lang.IPending
       (isRealized [this]
                   (.isDone dv))
       clojure.lang.IFn
       (invoke
        [this x]
        (.set dv x)
        this))))
  ([f]
   (let [p (promise)]
     (suspendable! f)
     (spawn-fiber #(deliver p (f)))
     p)))

;; ## Channels

(ann channel (Fn [AnyInteger -> Channel]
                 [-> Channel]))
(defn ^Channel channel
  "Creates a channel"
  ([size overflow-policy] (ObjectChannel/create (int size) (keyword->enum Channel$OverflowPolicy overflow-policy)))
  ([size]                 (ObjectChannel/create (int size)))
  ([]                     (ObjectChannel/create -1)))

(ann attach! [Channel (U Strand Fiber Thread) -> Channel])
(defn attach!
  "Sets a channel's owning strand (fiber or thread).
  This is done automatically the first time a rcv (or one of the primitive-type rcv-xxx) is called on the channel."
  [^Channel channel strand]
  (.setStrand channel strand))

(defn ^TickerChannel ticker-channel
  "Cerates a ticker channel"
  [size]
  (ObjectTickerChannel/create size))

(defn ^TickerChannel$TickerChannelConsumer ticker-consumer
  [^TickerChannel ticker]
  (.newConsumer ticker))

(ann snd (All [x] [Channel x -> x]))
(defn snd
  "Sends a message to a channel"
  [^SendChannel channel message]
  (.send channel message))

(ann rcv (Fn [Channel -> Any]
             [Channel Long (U TimeUnit Keyword) -> (Option Any)]))
(defsusfn rcv
  "Receives a message from a channel or a channel group.
  If a timeout is given, and it expires, rcv returns nil."
  ([^ReceiveChannel channel]
   (.receive channel))
  ([^ReceiveChannel channel timeout unit]
   (.receive channel (long timeout) (->timeunit unit))))

(defn close 
  "Closes a channel"
  [channel]
  (cond
    (instance? SendChannel channel)    (.close ^SendChannel channel)
    (instance? ReceiveChannel channel) (.close ^ReceiveChannel channel)
    :else (throw (IllegalArgumentException. (str (.toString ^Object channel) " is not a channel")))))

(defn closed?
  "Tests whether a channel has been closed and no more messages will be received.
  This function can only be called by the channel's owning strand (the receiver)"
  [^ReceiveChannel channel]
  (.isClosed channel))

(defn channel-group
  "Creates a channel group"
  [& channels]
  (ChannelGroup. ^java.util.Collection channels))

(defn topic
  "Creates a new topic."
  []
  (Topic.))

(defn subscribe
  "Subscribes a channel to a topic"
  [^Topic topic ^SendChannel channel]
  (.subscribe topic channel))

(defn unsubscribe
  "Unsubscribes a channel from a topic"
  [^Topic topic ^SendChannel channel]
  (.unsubscribe topic channel))

;; ### Primitive channels

(ann int-channel (Fn [AnyInteger -> IntChannel]
                     [-> IntChannel]))
(defn ^IntChannel int-channel
  "Creates an int channel"
  ([size overflow-policy] (IntChannel/create (int size) (keyword->enum Channel$OverflowPolicy overflow-policy)))
  ([size]                 (IntChannel/create (int size)))
  ([]                     (IntChannel/create -1)))

(defn ^IntTickerChannel int-ticker-channel
  "Cerates an int ticker channel"
  [size]
  (IntTickerChannel/create size))

(defmacro snd-int
  [channel message]
  `(.send ^co.paralleluniverse.strands.channels.IntSendChannel ~channel (int ~message)))

(defmacro rcv-int
  ([channel]
   `(int (.receiveInt ^co.paralleluniverse.strands.channels.IntReceiveChannel ~channel)))
  ([channel timeout unit]
   `(int (.receiveInt ^co.paralleluniverse.strands.channels.IntReceiveChannel ~channel (long ~timeout) (->timeunit ~unit)))))

(ann long-channel (Fn [AnyInteger -> LongChannel]
                      [-> LongChannel]))
(defn ^LongChannel long-channel
  "Creates a long channel"
  ([size overflow-policy] (LongChannel/create (int size) (keyword->enum Channel$OverflowPolicy overflow-policy)))
  ([size]                 (LongChannel/create (int size)))
  ([]                     (LongChannel/create -1)))

(defn ^LongTickerChannel long-ticker-channel
  "Cerates a long ticker channel"
  [size]
  (LongTickerChannel/create size))

(defmacro snd-long
  [channel message]
  `(.send ^co.paralleluniverse.strands.channels.LongSendChannel ~channel (long ~message)))

(defmacro rcv-long
  ([channel]
   `(long (.receiveLong ^co.paralleluniverse.strands.channels.LongReceiveChannel ~channel)))
  ([channel timeout unit]
   `(long (.receiveLong ^co.paralleluniverse.strands.channels.LongReceiveChannel ~channel (long ~timeout) (->timeunit ~unit)))))

(ann float-channel (Fn [AnyInteger -> FloatChannel]
                       [-> FloatChannel]))
(defn ^FloatChannel float-channel
  "Creates a float channel"
  ([size overflow-policy] (FloatChannel/create (int size) (keyword->enum Channel$OverflowPolicy overflow-policy)))
  ([size]                 (FloatChannel/create (int size)))
  ([]                     (FloatChannel/create -1)))

(defn ^FloatTickerChannel long-ticker-channel
  "Cerates a float ticker channel"
  [size]
  (FloatTickerChannel/create size))

(defmacro snd-float
  [channel message]
  `(.send ^co.paralleluniverse.strands.channels.FloatSendChannel ~channel (float ~message)))

(defmacro rcv-float
  ([channel]
   `(float (.receiveFloat ^co.paralleluniverse.strands.channels.FloatReceiveChannel ~channel)))
  ([channel timeout unit]
   `(float (.receiveFloat ^co.paralleluniverse.strands.channels.FloatReceiveChannel ~channel (long ~timeout) (->timeunit ~unit)))))

(ann double-channel (Fn [AnyInteger -> DoubleChannel]
                        [-> DoubleChannel]))
(defn ^DoubleChannel double-channel
  "Creates a double channel"
  ([size overflow-policy] (DoubleChannel/create (int size) (keyword->enum Channel$OverflowPolicy overflow-policy)))
  ([size]                 (DoubleChannel/create (int size)))
  ([]                     (DoubleChannel/create -1)))

(defn ^DoubleTickerChannel long-ticker-channel
  "Cerates a double ticker channel"
  [size]
  (DoubleTickerChannel/create size))

(defmacro snd-double
  [channel message]
  `(.send ^co.paralleluniverse.strands.channels.DoubleSendChannel ~channel (double ~message)))

(defmacro rcv-double
  ([channel]
   `(double (.receiveDouble ^co.paralleluniverse.strands.channels.DoubleReceiveChannel ~channel)))
  ([channel timeout unit]
   `(double (.receiveDouble ^co.paralleluniverse.strands.channels.DoubleReceiveChannel ~channel (long ~timeout) (->timeunit ~unit)))))

