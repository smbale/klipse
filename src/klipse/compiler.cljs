(ns klipse.compiler
  (:require 
    cljsjs.codemirror
    cljsjs.codemirror.mode.clojure
    cljsjs.codemirror.addon.edit.matchbrackets
    cljsjs.codemirror.addon.display.placeholder
    [klipse.utils :refer [add-url-parameter url-parameters debounce]]
    [klipse.io :as io]
    [gadjett.core :as gadjett :refer-macros [deftrack dbg]]
    [goog.dom :as gdom]
    [replumb.core :as replumb]
    [om.next :as om :refer-macros [defui]]
    [om.dom :as dom]
    [cljs.js :as cljs]))

(enable-console-print!)


;; =============================================================================
;; Compiler functions

;; create cljs.user
(set! (.. js/window -cljs -user) #js {})

(defn load-inlined [opts cb]
  (cb {:lang :clj :source ""}))

(deftrack _compilation [s]
  (cljs/compile-str (cljs/empty-state) s
                    "cljs-in"
                    {:load load-inlined}
                    (fn [{:keys [value error]}]
                      (let [status (if error :error :ok)
                            res (if error 
                                  (.. error -cause -message)
                                  value)]
                        [status res]))
                    ))

(def repl-opts-noop (merge (replumb/options :browser
                                             ["/dbg/js" "/js/compiled/out"]
                                             io/no-op)
                            {:warning-as-error false
                             :verbose false}))

(deftrack _eval [s]
  (let [{:keys [form warning error value success?]} (replumb/read-eval-call repl-opts-noop identity (str "(do " s ")"))
        status (if error :error :ok)
        res (or value (.. error -cause))]
    [status res]))

(deftrack _evaluation-js [s]
  (let [[status res] (_eval s)]
    (.log js/console res)
    [status (.stringify js/JSON res nil 4)]))

(deftrack _evaluation-clj [s]
  (let [[status res] (_eval s)]
    [status (str res)]))


;; =============================================================================
;; Reads

(defn read [{:keys [state]} key params]
  {:value (get @state key "")})


;; =============================================================================
;; Mutations

(defmulti mutate om/dispatch)

(defmethod mutate 'input/save [{:keys [state]} _ {:keys [value]}]
  {:action (fn [] (swap! state assoc :input value))})

(defmethod mutate 'cljs/compile [{:keys [state]} _ {:keys [value]}]
  {:action (fn [] (swap! state update :compilation (partial _compilation value)))})

(defmethod mutate 'js/eval [{:keys [state]} _ {:keys [value]}]
  {:action (fn [] (swap! state update :evaluation-js (partial _evaluation-js value)))})

(defmethod mutate 'clj/eval [{:keys [state]} _ {:keys [value]}]
  {:action (fn [] (swap! state update :evaluation-clj (partial _evaluation-clj value)))})

(deftrack create-url-with-input [input]
  (doto (add-url-parameter :cljs_in input)
        print
        js/alert))

(deftrack process-input [compiler s]
  (when-not (clojure.string/blank? s)
    (om/transact! compiler 
         [(list 'input/save     {:value s})
          (list 'cljs/compile   {:value s})
          (list 'js/eval        {:value s})
          (list 'clj/eval       {:value s})])))


;; =============================================================================
;; CodeMirror

(def config-editor 
  {:lineNumbers true
   :matchBrackets true 
   :autoCloseBrackets true
   :mode "clojure"})
               
(defn set-option [editor option value]
  (.setOption editor option value))

(defn get-value 
  ([editor] (.getValue editor))
  ([editor sep] (.getValue editor sep)))

(defn ctrl-enter [editor]
  (js/console.log (.getValue (.getDoc editor))))

(defn create-editor [compiler config]
  (let [editor (js/CodeMirror.fromTextArea
                  (js/document.getElementById "code") 
                  (clj->js config))
        idle-time 3000
        fn-process #(process-input compiler (get-value editor))] 
    (fn-process)
    (.on editor "change" (debounce fn-process idle-time))
    (set-option editor "extraKeys" 
        #js {"Ctrl-S" #(create-url-with-input (get-value editor))
             "Ctrl-Enter" fn-process})))

;; =============================================================================
;; Components

(defn logo [status base]
  (as->
    (case status
      :ok "ok"
      :error "error"
      "base") $
    (str "img/" base "-" $ ".png")))

(defn input-ui [compiler input full-width?]
  (dom/section #js {:id "input-ui"
                    :className (if full-width? "full-width" "half-width")}
               (dom/img #js {:src "img/cljs.png"
                             :width 40
                             :className "what"})
               (dom/textarea #js {:autoFocus true
                                  :value input
                                  :id "code"
                                  :placeholder ";; Write your clojurescript expression \n;; and press Ctrl-Enter or wait for 3 sec to experiment the magic..."})))

(defn compile-cljs-ui [{:keys [compilation]} full-width?]
  (let [[status result] compilation
        status-class (if (= :ok status) "ok" "error")]
    (dom/section #js {:id "compile-cljs-ui"
                      :className (if full-width? "full-width" "half-width")}
                 (dom/img #js {:src "img/js.png"
                               :width 35
                               :className "what"})
                 (dom/textarea #js {:value result
                                    :className status-class
                                    :placeholder ";; Press Ctrl-Enter or wait for 3 sec to transpile..."
                                    :readOnly true}))))

(defn evaluate-clj-ui [{:keys [evaluation-clj]} full-width?]
  (let [[status result] evaluation-clj
        status-class (if (= :ok status) "ok" "error")]
    (dom/section #js {:id "evaluate-clj-ui"
                      :className (if full-width? "full-width" "half-width")}
                 (dom/img #js {:src (logo status "cljs")
                               :width 40
                               :className (str "what " status-class)})
                 (dom/textarea #js {:value result
                                    :className status-class
                                    :placeholder ";; Press Ctrl-Enter or wait for 3 sec to eval in clojure..."
                                    :readOnly true}))))

(defn evaluate-js-ui [{:keys [evaluation-js]} full-width?]
  (let [[status result] evaluation-js
        status-class (if (= :ok status) "ok" "error")]
    (dom/section #js {:id "evaluate-js-ui"
                      :className (if full-width? "full-width" "half-width")}
                 (dom/img #js {:src (logo status "js")
                               :width 35
                               :className (str "what " status-class)})
                 (dom/textarea #js {:value result
                                    :className status-class
                                    :placeholder ";; Press Ctrl-Enter or wait for 3 sec to eval in js..."
                                    :readOnly true}))))


(defui CompilerUI

  static om/IQuery
  (query [this] 
         '[:compilation :evaluation-js :evaluation-clj])

  Object

  (componentDidMount [this]
                     (create-editor this config-editor))

  (render [this]
          (let [{:keys [js_only eval_only cljs_in]} (url-parameters)
                full-width (or js_only eval_only)]
            (as->
              (om/props this) $
              (dom/div #js {:className "container"}
                       (input-ui this cljs_in full-width)
                       (when-not eval_only
                         (compile-cljs-ui $ full-width))
                       (when-not js_only
                         (evaluate-clj-ui $ full-width))
                       (when-not (or eval_only js_only)
                         (evaluate-js-ui $ full-width))
                       )))))


;; =============================================================================
;; Init

(defonce app-state (atom
  {:input ""
   :compilation ""
   :evaluation-js ""
   :evaluation-clj ""}))

(def parser (om/parser {:read read 
                        :mutate mutate}))

(def reconciler 
  (om/reconciler 
    {:state app-state 
     :parser parser}))

(om/add-root! reconciler CompilerUI (gdom/getElement "compiler"))
