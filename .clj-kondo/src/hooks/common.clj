(ns hooks.common
  (:require
   [clj-kondo.hooks-api :as hooks]
   [clojure.pprint]
   [clojure.set :as set]))

(defn with-macro-meta
  "When introducing internal nodes (let, defn, etc) it is important to provide a meta of an existing token
   as the current version of kondo will use the whole form by default."
  [new-node hook-node]
  (with-meta new-node (meta (first (:children hook-node)))))

;;; Kondo stores information about ignored linters as rewrite-clj forms matching how they are specified in the code, so
;;; either
;;;
;;;    <vector-node [<token-node :discouraged-namespace>]>
;;;
;;; or
;;;
;;;    <token-node :discouraged-namespace>
;;;
;;; these are stored in
;;;
;;;     (-> node meta :clj-kondo/ignore :linters).
;;;
;;; These are not a convenient shape(s) to work with, so the next few functions abstract that shape out and work with a
;;; set of keywords like
;;;
;;;    #{:discouraged-namespace}
;;;
;;; instead.

(defn- unwrap-ignored-linters [linters]
  (cond
    (or (hooks/vector-node? linters)
        (hooks/list-node? linters))
    (into #{}
          (map hooks/sexpr)
          (:children linters))

    (hooks/keyword-node? linters)
    #{(hooks/sexpr linters)}

    (and (set? linters)
         (every? keyword? linters))
    linters

    (and (sequential? linters)
         (every? keyword? linters))
    (into #{} linters)

    (keyword? linters)
    #{linters}))

(defn- wrap-ignored-linters [linters]
  (hooks/vector-node (mapv hooks/keyword-node linters)))

(defn ignored-linters
  "Return a set of keywords all ignored linters associated with a node by looking at its metadata."
  [node]
  (or (some-> node meta :clj-kondo/ignore :linters unwrap-ignored-linters)
      ;; deal with the old pre-2024.09 shape mentioned in https://github.com/clj-kondo/clj-kondo/issues/2414
      (some-> node :meta first hooks/sexpr :clj-kondo/ignore unwrap-ignored-linters)))

(defn update-ignored-linters
  "Update the `:clj-kondo/ignore` linters in `node`'s metadata. `linters` should be a set of keywords."
  [node f & args]
  (letfn [(apply-f [linters]
            (apply f linters args))
          (update-linters [linters]
            (-> (or (unwrap-ignored-linters linters)
                    #{})
                apply-f
                wrap-ignored-linters))]
    (vary-meta node update-in [:clj-kondo/ignore :linters] update-linters)))

(defn merge-ignored-linters
  "Merge the ignored linters from `source-nodes` into `node`. You can use this to propagate
  ignored linters from parent nodes to child nodes."
  [node & source-nodes]
  (if-let [linters (not-empty (transduce (map ignored-linters) set/union #{} source-nodes))]
    (update-ignored-linters node set/union linters)
    node))

;;; This stuff is to help debug hooks. Trace a function and it will pretty-print the before & after sexprs.
;;;
;;;    (hooks.common/trace #'calculate-bird-scarcity)

#_{:clj-kondo/ignore [:discouraged-var]}
(defn- trace* [f]
  {:pre [(fn? f)]}
  (fn traced-fn [node]
    (println \newline)
    (clojure.pprint/pprint (hooks/sexpr (:node node)))
    (let [node* (f node)]
      (println '=>)
      (clojure.pprint/pprint (hooks/sexpr (:node node*)))
      node*)))

;;; this doesn't seem to work with SCI. Use [[defn-traced]] instead.

;; (defn trace [varr]
;;   {:pre [(var? varr)]}
;;   (when-not (::traced? (meta varr))
;;     (alter-var-root varr trace*)
;;     (alter-meta! varr assoc ::traced? true)
;;     (println "Traced" varr)))

(defmacro defn-traced
  "Replace a hook [[defn]] with this to trace the before and after sexprs."
  [fn-name & fn-tail]
  `(do
     (defn fn# ~@fn-tail)
     (def ~fn-name (trace* fn#))))

;;;; Common hook definitions

(defn do*
  "This is the same idea as [[clojure.core/do]] but doesn't cause Kondo to complain about redundant dos or unused
  values."
  [{{[_ & args] :children, :as node} :node}]
  (let [node* (-> (hooks/list-node
                   (list*
                    (update-ignored-linters (hooks/token-node 'do) conj :redundant-do)
                    (for [arg args]
                      (update-ignored-linters arg conj :unused-value))))
                  (with-meta (meta node)))]
    {:node node*}))

(defn with-one-binding
  "Helper for macros that have a shape like

    (my-macro [x]
      ...)

    =>

    (let [x nil]
      ...)

  Binding is optional and `_` will be substituted if not supplied."
  [{{[_ {[x] :children} & body] :children} :node}]
  (let [node* (hooks/list-node
               (list*
                (hooks/token-node 'let)
                (hooks/vector-node
                 [(or x (hooks/token-node '_)) (hooks/token-node 'nil)])
                body))]
    {:node node*}))

(defn with-two-bindings
  "Helper for macros that have a shape like

    (my-macro [x y]
      ...)

    =>

    (let [x nil, y nil]
      ...)

  All bindings are optional and `_` will be substituted if not supplied."
  [{{[_ {[x y] :children} & body] :children} :node}]
  (let [node* (hooks/list-node
               (list*
                (hooks/token-node 'let)
                (hooks/vector-node
                 [(or x (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or y (hooks/token-node '_)) (hooks/token-node 'nil)])
                body))]
    {:node node*}))

(defn with-three-bindings
  "Helper for macros that have a shape like

    (my-macro [x y z]
      ...)

    =>

    (let [x nil, y nil, z nil]
      ...)

  All bindings are optional and `_` will be substituted if not supplied."
  [{{[_ {[x y z] :children} & body] :children} :node}]
  (let [node* (hooks/list-node
               (list*
                (hooks/token-node 'let)
                (hooks/vector-node
                 [(or x (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or y (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or z (hooks/token-node '_)) (hooks/token-node 'nil)])
                body))]
    {:node node*}))

(defn with-four-bindings
  "Helper for macros that have a shape like

    (my-macro [a b c d]
      ...)

    =>

    (let [a nil, b nil, c nil, d nil]
      ...)

  All bindings are optional and `_` will be substituted if not supplied."
  [{{[_ {[a b c d] :children} & body] :children} :node}]
  (let [node* (hooks/list-node
               (list*
                (hooks/token-node 'let)
                (hooks/vector-node
                 [(or a (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or b (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or c (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or d (hooks/token-node '_)) (hooks/token-node 'nil)])
                body))]
    {:node node*}))

(defn with-five-bindings
  "Helper for macros that have a shape like

    (my-macro [a b c d e]
      ...)

    =>

    (let [a nil, b nil, c nil, d nil, e nil]
      ...)

  All bindings are optional and `_` will be substituted if not supplied."
  [{{[_ {[a b c d e] :children} & body] :children} :node}]
  (let [node* (hooks/list-node
               (list*
                (hooks/token-node 'let)
                (hooks/vector-node
                 [(or a (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or b (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or c (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or d (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or e (hooks/token-node '_)) (hooks/token-node 'nil)])
                body))]
    {:node node*}))

(defn with-six-bindings
  "Helper for macros that have a shape like

    (my-macro [a b c d e f]
      ...)

    =>

    (let [a nil, b nil, c nil, d nil, e nil, f nil]
      ...)

  All bindings are optional and `_` will be substituted if not supplied."
  [{{[_ {[a b c d e f] :children} & body] :children} :node}]
  (let [node* (hooks/list-node
               (list*
                (hooks/token-node 'let)
                (hooks/vector-node
                 [(or a (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or b (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or c (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or d (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or e (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or f (hooks/token-node '_)) (hooks/token-node 'nil)])
                body))]
    {:node node*}))

(defn with-seven-bindings
  "Helper for macros that have a shape like

    (my-macro [a b c d e f g]
      ...)

    =>

    (let [a nil, b nil, c nil, d nil, e nil, f nil, g nil]
      ...)

  All bindings are optional and `_` will be substituted if not supplied."
  [{{[_ {[a b c d e f g] :children} & body] :children} :node}]
  (let [node* (hooks/list-node
               (list*
                (hooks/token-node 'let)
                (hooks/vector-node
                 [(or a (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or b (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or c (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or d (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or e (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or f (hooks/token-node '_)) (hooks/token-node 'nil)
                  (or g (hooks/token-node '_)) (hooks/token-node 'nil)])
                body))]
    {:node node*}))

(defn with-one-top-level-binding
  "Helper for macros that have a shape like

    (my-macro x
      ...)

    =>

    (let [x nil]
      ...)"
  [{{[_ x & body] :children} :node}]
  (let [node* (hooks/list-node
               (list*
                (hooks/token-node 'let)
                (hooks/vector-node
                 [x (hooks/token-node 'nil)])
                body))]
    {:node node*}))

(defn with-two-top-level-bindings
  "Helper for macros that have a shape like

    (my-macro x y
      ...)

    =>

    (let [x nil, y nil]
      ...)"
  [{{[_ x y & body] :children} :node}]
  (let [node* (hooks/list-node
               (list*
                (hooks/token-node 'let)
                (hooks/vector-node
                 [x (hooks/token-node 'nil)
                  y (hooks/token-node 'nil)])
                body))]
    {:node node*}))

(defn let-one-with-optional-value
  "This is exactly like [[clojure.core/let]] but the right-hand side of the binding, `value`, is optional, and only one
  binding can be established.

    (some-macro [x] ...)
    =>
    (let [x nil] ...)

    (some-macro [x 100] ...)
    =>
    (let [x 100] ...)"
  [{{[_ {[binding value] :children} & body] :children} :node}]
  (let [node* (hooks/list-node
               (list*
                (hooks/token-node 'let)
                (hooks/vector-node
                 [binding (or value (hooks/token-node 'nil))])
                body))]
    {:node node*}))

(defn- let-second-inner [body bindings]
  (let [binding-infos (for [[model {[binding value] :children}] (partition 2 bindings)]
                        {:model   model
                         :binding binding
                         :value   (or value
                                      (hooks/token-node 'nil))})]
    (-> (hooks/vector-node
         [(hooks/vector-node (map :model binding-infos))
          (-> (hooks/list-node (list* (hooks/token-node `let)
                                      (hooks/vector-node (mapcat (juxt :binding :value) binding-infos))
                                      body))
              (with-meta (meta body)))])
        (with-meta (meta body)))))

(defn let-second
  "Helper for macros that have a shape like

    (my-macro x [y]
    ...)

    where the let is for second arg.

    =>

    (let [y nil]
    ...)"
  [{:keys [node]}]
  (let [[_ first-arg-ref binding+opts & body] (:children node)]
    {:node (let-second-inner body [first-arg-ref binding+opts])}))

(defn let-with-optional-value-for-last-binding
  "This is exactly like [[clojure.core/let]] but the right-hand side of the *last* binding, `value`, is optional.

    (some-macro [x] ...)
    =>
    (let [x nil] ...)

    (some-macro [x 100] ...)
    =>
    (let [x 100] ...)"
  [{{[_ {bindings :children} & body] :children} :node}]
  (let [node* (hooks/list-node
               (list*
                (hooks/token-node 'let)
                (hooks/vector-node
                 (into []
                       (comp (partition-all 2)
                             (mapcat (fn [[binding value]]
                                       [binding (or value (hooks/token-node 'nil))])))
                       bindings))
                body))]
    {:node node*}))

(defn with-ignored-first-arg
  "For macros like

    (discard-setting-changes [setting-1 setting-2]
      ...)

    =>

    (do ...)

  where the first arg ought to be ignored for linting purposes."
  [{{[_ _x & body] :children} :node}]
  (let [node* (hooks/list-node
               (list*
                (hooks/token-node 'do)
                body))]
    {:node node*}))

(defn with-used-first-arg
  "For macros like

    (with-drivers (filter pred? some-drivers)
      ...)

    =>

    (let [_1234 (filter pred? some-drivers)]
      ...)

  where the first arg should be linted and appear to be used."
  [{{[_ arg & body] :children} :node}]
  (let [node* (hooks/list-node
               (list*
                (hooks/token-node 'let)
                (hooks/vector-node [(hooks/token-node (gensym "_"))
                                    arg])
                body))]
    {:node node*}))

(defn with-used-first-two-args
  "For macros like

    (with-limit-and-offset limit offset
      ...)

    =>

    (let [_123 limit
         [_456 offset]
      ...)

  where the first arg should be linted and appear to be used."
  [{{[_ x y & body] :children} :node}]
  (let [node* (hooks/list-node
               (list*
                (hooks/token-node 'let)
                (hooks/vector-node [(hooks/token-node (gensym "_")) x
                                    (hooks/token-node (gensym "_")) y])
                body))]
    {:node node*}))

(defn with-vec-first-binding
  "For macros like

    (with-temp-file [binding \"temp.txt\" \"content\"]
      (slurp binding))

    =>
    (let [binding nil]
      (slurp binding))

    where the first arg is a vector of bindings."
  [{{[_ {[x & _args] :children} & body] :children} :node}]
  (let [node* (hooks/list-node
               (list*
                (hooks/token-node 'let)
                (hooks/vector-node
                 [(or x (hooks/token-node '_)) (hooks/token-node 'nil)])
                body))]
    {:node node*}))

(defn node->qualified-symbol
  "If the node is a symbol, return it as a qualified symbol if it's not already. Otherwise return nil."
  [node]
  (try
    (when (hooks/token-node? node)
      (let [sexpr (hooks/sexpr node)]
        (when (symbol? sexpr)
          (let [resolved (hooks/resolve {:name sexpr})]
            (cond
              (and resolved (:ns resolved))
              (symbol (name (:ns resolved)) (name (:name resolved)))

             ;; if it wasn't resolved but is still qualified it's probably using the full namespace name rather than an
             ;; alias.
              (qualified-symbol? sexpr)
              sexpr)))))
    ;; some symbols like `*count/Integer` aren't resolvable.
    (catch Exception _
      nil)))

(defn format-string-specifier-count
  "Number of things like `%s` in a format string, not counting newlines (`%n`) or escaped percent signs (`%%`). For
  checking the number of args to something that takes a format string."
  [format-string]
  (count (re-seq #"(?<!%)%(?![%n])" format-string)))

(comment
  ;; should be 1
  (format-string-specifier-count "%s %%")

  ;; should be 2
  (format-string-specifier-count "%s %%%n%s")

  ;; should be 0
  (format-string-specifier-count "%n%%%%")

  ;; should be 1
  (format-string-specifier-count "%-02d"))

(defn add-lsp-ignore-unused-public-var-metadata
  "Add

    ^{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}

  metadata to a node to suppress LSP warnings."
  [node]
  (update-ignored-linters node conj :clojure-lsp/unused-public-var))
