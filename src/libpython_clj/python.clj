(ns libpython-clj.python
  (:require [libpython-clj.jna :as libpy]
            [tech.jna.base :as jna-base]
            [tech.jna :as jna]
            [tech.resource :as resource]
            [tech.resource.gc :as resource-gc]
            [tech.parallel.require :as parallel-req])
  (:import [tech.resource GCSoftReference]
           [com.sun.jna Pointer]
           [com.sun.jna.ptr PointerByReference]
           [java.lang AutoCloseable]))


(set! *warn-on-reflection* true)


(defonce taoensso-logger
  (future {:info  (parallel-req/require-resolve 'tech.jna.timbre-log/log-info)
           :warn (parallel-req/require-resolve 'tech.jna.timbre-log/log-warn)
           :error  (parallel-req/require-resolve 'tech.jna.timbre-log/log-error)
           }))


(defn log-level
  [level msg]
  (if-let [logger (try (get @taoensso-logger level)
                       (catch Throwable e nil))]
    (logger msg)
    (println (format "%s: %s" (name level) msg))))


(defn log-error
  [log-str]
  (log-level :error log-str))


(defn log-info
  [log-str]
  (log-level :info log-str))


(defn logthrow-error
  [log-str & [data]]
  (throw (ex-info log-str data)))


(defrecord LibPython []
  AutoCloseable
  (close [this]
))


(defonce ^:dynamic *python* (atom nil))



(defn wide-str-ptr
  ^Pointer [^String data]
  (let [^Pointer retval (jna/malloc (+ 2 (count data)))]
    (.setString retval 0 data "UTF-16")
    retval))

(defn ->ptr-ptr
  ^PointerByReference [^Pointer value]
  (PointerByReference. value))


(def ^:dynamic *program-name* nil)


(defn get-instance
  "Instance is guaranteed to be alive as long as caller has a gc reference to it"
  [& [^String program-name]]
  (if-let [retval (when-let [^GCSoftReference refdata @*python*]
                    (.get refdata))]
    retval
    (locking *python*
      (log-info "Creating python libary bindings")
      (let [retval (do ;; Disable signals
                     (libpy/Py_InitializeEx 0)
                     ;;Set program name
                     (when-let [program-name (or program-name *program-name*)]
                       (resource/stack-resource-context
                        (libpy/PySys_SetArgv 0 (-> program-name
                                                   (wide-str-ptr)
                                                   (->ptr-ptr)))))
                     (->LibPython))]
        (reset! *python* (resource-gc/soft-reference
                          retval
                          #(do
                             (log-info "Destroying python libary bindings")
                             (let [finalize-val (long (libpy/Py_FinalizeEx))]
                                 (when-not (= 0 finalize-val)
                                   (log-error (format "Py_Finalize failure: %s" finalize-val)))))))
        retval))))


(defn wrap-pyobject
  [pyobj]
  (resource/track pyobj (partial libpy/Py_DecRef (jna/as-ptr pyobj)) [:gc]))
