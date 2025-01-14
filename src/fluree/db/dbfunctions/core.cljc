(ns fluree.db.dbfunctions.core
  (:refer-clojure :exclude [read-string])
  (:require [#?(:cljs cljs.reader :clj clojure.edn) :refer [read-string]]
            [#?(:cljs cljs.cache :clj clojure.core.cache) :as cache]
            [fluree.db.dbproto :as dbproto]
            [fluree.db.util.core :refer [try* catch*]]
            [fluree.db.util.log :as log]
            [fluree.db.util.async :refer [<? go-try channel?]]
            [fluree.db.dbfunctions.fns :as fns]
            [clojure.string :as str]))

#?(:clj (set! *warn-on-reflection* true))

(declare resolve-fn)

(defn db-fn-cache-factory
  "Returns an empty TTL cache for db-function caching.
  Implication of this caching strategy is changes to db functions that get
  update will take 5 seconds to recompile, benefit is that the same function
  call for a ledger will not have to recompile every time."
  []
  (cache/ttl-cache-factory {} :ttl 5000))

(def db-fn-cache (atom (db-fn-cache-factory)))

(defn clear-db-fn-cache []
  (reset! db-fn-cache (db-fn-cache-factory)))


(defn tx-fn?
  "Returns true if this value is a transaction function."
  [value]
  (and (string? value) (re-matches #"^#\(.+\)$" value)))

(def default-fn-map {'get           (resolve 'fluree.db.dbfunctions.fns/get)
                     'get-all       (resolve 'fluree.db.dbfunctions.fns/get-all)
                     'get-in        (resolve 'fluree.db.dbfunctions.fns/get-in)
                     'follow        (resolve 'fluree.db.dbfunctions.fns/get-all)
                     'contains?     (resolve 'fluree.db.dbfunctions.fns/contains?)
                     'relationship? (resolve 'fluree.db.dbfunctions.fns/relationship?)
                     'query         (resolve 'fluree.db.dbfunctions.fns/query)
                     'max-pred-val  (resolve 'fluree.db.dbfunctions.fns/max-pred-val)
                     'max           (resolve 'fluree.db.dbfunctions.fns/max)
                     'min           (resolve 'fluree.db.dbfunctions.fns/min)
                     'inc           (resolve 'fluree.db.dbfunctions.fns/inc)
                     'dec           (resolve 'fluree.db.dbfunctions.fns/dec)
                     'now           (resolve 'fluree.db.dbfunctions.fns/now)
                     '+             (resolve 'fluree.db.dbfunctions.fns/+)
                     '-             (resolve 'fluree.db.dbfunctions.fns/-)
                     '*             (resolve 'fluree.db.dbfunctions.fns/*)
                     '/             (resolve 'fluree.db.dbfunctions.fns//)
                     'quot          (resolve 'fluree.db.dbfunctions.fns/quot)
                     'mod           (resolve 'fluree.db.dbfunctions.fns/mod)
                     'rem           (resolve 'fluree.db.dbfunctions.fns/rem)
                     '==            (resolve 'fluree.db.dbfunctions.fns/==)
                     '=             (resolve 'fluree.db.dbfunctions.fns/==)
                     'not=          (resolve 'fluree.db.dbfunctions.fns/not=)
                     '>             (resolve 'fluree.db.dbfunctions.fns/>)
                     '>=            (resolve 'fluree.db.dbfunctions.fns/>=)
                     '?sid          (resolve 'fluree.db.dbfunctions.fns/?sid)
                     '?pid          (resolve 'fluree.db.dbfunctions.fns/?pid)
                     '?o            (resolve 'fluree.db.dbfunctions.fns/?o)
                     '?s            (resolve 'fluree.db.dbfunctions.fns/?s)
                     '?p            (resolve 'fluree.db.dbfunctions.fns/?p)
                     'nil?          (resolve 'fluree.db.dbfunctions.fns/nil?)
                     'empty?        (resolve 'fluree.db.dbfunctions.fns/empty?)
                     'not           (resolve 'fluree.db.dbfunctions.fns/not)
                     '?auth_id      (resolve 'fluree.db.dbfunctions.fns/?auth_id)
                     '?user_id      (resolve 'fluree.db.dbfunctions.fns/?user_id)
                     '<             (resolve 'fluree.db.dbfunctions.fns/<)
                     '<=            (resolve 'fluree.db.dbfunctions.fns/<=)
                     'boolean       (resolve 'fluree.db.dbfunctions.fns/boolean)
                     're-find       (resolve 'fluree.db.dbfunctions.fns/re-find)
                     'valid-email?  (resolve 'fluree.db.dbfunctions.fns/valid-email?)
                     'and           (resolve 'fluree.db.dbfunctions.fns/and)
                     'or            (resolve 'fluree.db.dbfunctions.fns/or)
                     'count         (resolve 'fluree.db.dbfunctions.fns/count)
                     'str           (resolve 'fluree.db.dbfunctions.fns/str)
                     'subs          (resolve 'fluree.db.dbfunctions.fns/subs)
                     'nth           (resolve 'fluree.db.dbfunctions.fns/nth)
                     'if-else       (resolve 'fluree.db.dbfunctions.fns/if-else)
                     '?pO           (resolve 'fluree.db.dbfunctions.fns/?pO)
                     'objT          (resolve 'fluree.db.dbfunctions.fns/objT)
                     'objF          (resolve 'fluree.db.dbfunctions.fns/objF)
                     'flakes        (resolve 'fluree.db.dbfunctions.fns/flakes)
                     'rand          (resolve 'fluree.db.dbfunctions.fns/rand)
                     'hash-set      (resolve 'fluree.db.dbfunctions.fns/hash-set)
                     'ceil          (resolve 'fluree.db.dbfunctions.fns/ceil)
                     'floor         (resolve 'fluree.db.dbfunctions.fns/floor)
                     'upper-case    (resolve 'fluree.db.dbfunctions.fns/upper-case)
                     'lower-case    (resolve 'fluree.db.dbfunctions.fns/lower-case)
                     'uuid          (resolve 'fluree.db.dbfunctions.fns/uuid)
                     'cas           (resolve 'fluree.db.dbfunctions.fns/cas)})

(defn resolve-local-fn
  [f]
  (let [{:keys [fdb/spec arglists]} (meta f)
        arglist (first arglists)
        &args?  (and ((into #{} arglist) (symbol "&"))
                     ((into #{} arglist) (symbol "args")))
        arity   (if-not &args?
                  (into #{} (map #(- (count %) 1) arglists)))]
    {:f      f
     :params arglists
     :arity  arity
     :&args? &args?
     :spec   spec
     :code   nil}))

#?(:cljs
   (defn- build-fn
     [var fun]
     (eval `(fn [~var]
              ~fun))))

(defn- find-fn*
  [db fn-name funType]
  (go-try
    (let [forward-time-travel-db? (:tt-id db)]
      (or (when-not forward-time-travel-db?
            (get @db-fn-cache [fn-name (:network db) (:dbid db)]))
          (let [res (let [query       {:selectOne ["_fn/params" "_fn/code" "_fn/spec"]
                                       :from      ["_fn/name" (name fn-name)]}
                          res         (<? (dbproto/-query db query))
                          _           (if (empty? res)
                                        (throw (ex-info (str "Unknown function: " (pr-str fn-name))
                                                        {:status 400
                                                         :error  :db/invalid-fn})))
                          params      (read-string (get res "_fn/params"))
                          code        (<? (resolve-fn db (read-string (get res "_fn/code")) funType params))
                          spec        (get res "_fn/spec")
                          params'     (->> params
                                           (mapv (fn [x] (symbol x)))
                                           (cons '?ctx)
                                           (into []))
                          custom-func #?(:clj (list #'clojure.core/fn params' code)
                                         :cljs (build-fn params' code))]
                      {:f      custom-func
                       :params params
                       :arity  (hash-set (count params))
                       :&args? false
                       :spec   spec
                       :code   nil})]
            (when-not forward-time-travel-db?
              (swap! db-fn-cache assoc [fn-name (:network db) (:dbid db)] res))
            res)))))

(defn find-fn
  ([db fn-name]
   (find-fn db fn-name nil))
  ([db fn-name funType]
   #?(:clj  (find-fn* db fn-name funType)
      :cljs (cond
              (identical? "nodejs" cljs.core/*target*)
              (find-fn* db fn-name funType)
              :else
              (throw (ex-info "DB functions not yet supported in javascript!" {}))))))

(defn combine-fns
  "Given a collection of function strings, returns a combined function using the and function"
  [fn-str-coll]
  (if (> (count fn-str-coll) 1)
    (str "(and " (str/join " " fn-str-coll) ")")
    (first fn-str-coll)))


(def symbol-whitelist #{'?s '?user_id '?db '?o 'sid '?auth_id '?pid '?a '?pO})

(defn parse-vector
  "Ensures contents of vector are allowed"
  ([db vec]
   (parse-vector db vec nil nil))
  ([db vec funType]
   (parse-vector db vec funType nil))
  ([db vec funType params]
   (go-try
     (loop [[x & r] vec
            acc []]
       (if (nil? x)
         acc
         (recur r
                (conj acc
                      (cond
                        (string? x) x
                        (number? x) x
                        (symbol? x) (or (symbol-whitelist x) (some #{x} (mapv #(symbol %) params)) (= funType "functionDec")
                                        (throw (ex-info (str "Invalid symbol: " x " used in function." (pr-str vec))
                                                        {:status 400
                                                         :error  :db/invalid-fn})))
                        (or (true? x) (false? x) (nil? x)) x
                        (vector? x) (<? (parse-vector db x funType params))
                        (nil? x) x
                        (list? x) (<? (resolve-fn db x funType params))
                        :else (throw (ex-info (str "Illegal element (" (pr-str x) ") in vector: " (pr-str vec) ".") {}))))))))))


(defn find-local-fn*
  "Looks up function in local-function map. If exists returns map of function details,
  if doesn't exist returns nil."
  [fn-name]
  (when-let [local-fn (get default-fn-map (symbol fn-name))]
    (resolve-local-fn local-fn)))


(def find-local-fn (memoize find-local-fn*))


(defn resolve-fn
  "Resolves a full code form expression."
  ([db form]
   (resolve-fn db form nil nil))
  ([db form type]
   (resolve-fn db form type nil))
  ([db form type params]
   (go-try
     (let [fn-name (first form)
           args    (rest form)
           args-n  (count args)
           fn-map  (or (find-local-fn fn-name)
                       (<? (find-fn db fn-name type)))
           {:keys [f arity arglist &args?]} fn-map
           _       (when (not (or &args? (arity args-n)))
                     (throw (ex-info (str "Incorrect arity for function " fn-name ". Expected " arity ", provided: " args-n ".") {})))
           args*   (loop [[arg & r] args
                          acc []]
                     (if (or arg (false? arg))
                       (let [arg* (cond
                                    (list? arg) (<? (resolve-fn db arg type params))
                                    (string? arg) arg
                                    (number? arg) arg
                                    (symbol? arg) (or (symbol-whitelist arg)
                                                      (some #{arg} (mapv #(symbol %) params))
                                                      (= type "functionDec")
                                                      (throw (ex-info (str "Invalid symbol: " arg
                                                                           " used in function argument: " (pr-str form))
                                                                      {:status 400
                                                                       :error  :db/invalid-fn})))
                                    (or (true? arg)
                                        (false? arg)
                                        (nil? arg)) arg
                                    (vector? arg) (<? (parse-vector db arg type params))
                                    :else (throw (ex-info (str "Illegal element (" (pr-str arg) (type arg)
                                                               ") in function argument: " (pr-str form) ".") {})))]
                         (recur r (conj acc arg*))) acc))
           form*   (cons f (cons '?ctx args*))]
       form*))))


(defn parse-fn
  ([db fn-str type]
   (parse-fn db fn-str type nil))
  ([db fn-str type params]
   (go-try
     (if
       (or (= fn-str "true") (= fn-str "false"))
       (defn true-or-false [n] (read-string fn-str))

       (try*
         (when-not (re-matches #"(^\(.+\)$)" fn-str)
           (throw (ex-info (str "Bad function")
                           {:status 400
                            :error  :db/invalid-fn})))

         (let [form      (read-string fn-str)
               resolved  (<? (resolve-fn db form type params))
               f-wrapped `(fn [~'?ctx] ~resolved)
               f         (if (and params (= type "functionDec"))
                           f-wrapped
                           (eval f-wrapped))]
           (with-meta f {:fnstr fn-str}))

         (catch* e
                 (throw e)
                 (throw (ex-info (str "Error parsing function: " fn-str)
                                 {:status 400 :error :db/invalid-tx}))))))))


(defn execute-tx-fn
  "Executes a transaction function"
  [db auth_id credits s p o fuel block-instant]
  (go-try
    (let [fn-str  (subs o 1)                                ;; remove preceding '#'
          credits 10000000
          ctx     {:db      db
                   :instant block-instant
                   :sid     s
                   :pid     p
                   :auth_id auth_id
                   :state   fuel}
          f       (<? (parse-fn db fn-str "txn" nil))
          res     (f ctx)]
      (if (channel? res)
        (<? res) res))))


(comment

  (def db nil)


  (def db2 nil)


  (def parsed (parse db nil nil 100 "(inc (inc (max-pred-val \"_block/instant\")))"))



  (parsed {:db      db
           :subject nil
           :auth    nil
           :credits 100
           :state   (atom {:stack   []
                           :credits 100})})


  (cons 1 (cons 7 [2 3]))


  (def test-fn "(max [1 2 3 4])")

  db

  (->> (parse-code-str test-fn)
       #_(parse-form db))



  (fn? (get default-fn-map 'max)))





