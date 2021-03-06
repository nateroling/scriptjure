;;; scriptjure -- a library for generating javascript from Clojure s-exprs

;; by Allen Rohner, http://arohner.blogspot.com
;;                  http://www.reasonr.com  
;; October 7, 2009

;; Copyright (c) Allen Rohner, 2009. All rights reserved.  The use
;; and distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this
;; distribution.  By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license.  You must not
;; remove this notice, or any other, from this software.

;; This library generates javascript from Clojure s-exprs. To use it, 
;; (js (fn foo [x] (var x (+ 3 5)) (return x)))
;;  returns a string, "function foo (x) { var x = (3 + 5); return x; }"
;;
;; See the README and the tests for more information on what is supported.
;; 
;; The library is intended to generate javascript glue code in Clojure
;; webapps. One day it might become useful enough to write entirely
;; JS libraries in clojure, but it's not there yet.
;;
;;

(ns #^{:author "Allen Rohner"
       :doc "A library for generating javascript from Clojure."}
       com.reasonr.scriptjure
  (:require [clojure.contrib.str-utils2 :as str])
  (:use clojure.walk))

(defmulti emit (fn [ expr ] (do ; (println "emit-dispatch: " expr (type expr)) 
				(type expr))))

(defmulti emit-special (fn [ & args] (identity (first args))))

(def statement-separator ";\n")

(defn statement [expr]
  (if (not (= statement-separator (str/tail expr (count statement-separator))))
    (str expr statement-separator)
    expr))

(defn comma-list [coll]
  (str "(" (str/join ", " coll) ")"))

(defmethod emit nil [expr]
  "null")

(defmethod emit java.lang.Integer [expr]
  (str expr))

(defmethod emit clojure.lang.Ratio [expr]
  (str (float expr)))

(defmethod emit clojure.lang.Keyword [expr]
  (str (name expr)))

(defmethod emit java.lang.String [expr]
  (str \" expr \"))

(defmethod emit clojure.lang.Symbol [expr]
  (str expr))

(defmethod emit :default [expr]
  (str expr))

(def special-forms (set ['var '. 'if 'funcall 'fn 'set! 'return 'delete 'new 'do]))

(def infix-operators (set ['+ '- '/ '* '% '== '=== '< '> '<= '>= '!= '<< '>> '<<< '>>> '!== '& '^ '| '&& '||]))

(defn special-form? [expr]
  (contains? special-forms expr))

(defn infix-operator? [expr]
  (contains? infix-operators expr))

(defn emit-infix [type [operator & args]]
  (when (< (count args) 2)
    (throw (Exception. "not supported yet")))
  (str "(" (emit (first args)) " " operator " " (emit (second args)) ")" ))

(defmethod emit-special 'var [type [var name expr]]
  (str "var " (emit name) " = " (emit expr)))

(defmethod emit-special 'funcall [type [name & args]]
  (str (emit name) (comma-list (map emit args))))

(defn emit-method [obj method args]
  (str (emit obj) "." (emit method) (comma-list (map emit args))))

(defmethod emit-special '. [type [period method obj & args]]
  (emit-method obj method args))

(defmethod emit-special 'if [type [if test true-form & false-form]]
  (statement (str "function () { if (" (emit test) ") { \n"
       (str "return " (statement (emit true-form)))
       "\n }"
       (when (first false-form)
	 (str " else { \n"
	      (str "return " (statement (emit (first false-form))))
	      " }"))
       " }()")))
       
(defmethod emit-special 'dot-method [type [method obj & args]]
  (let [method (symbol (str/drop (str method) 1))]
    (emit-method obj method args)))

(defmethod emit-special 'return [type [return expr]]
  (str "return " (emit expr)))

(defmethod emit-special 'delete [type [return expr]]
  (str "delete " (emit expr)))

(defmethod emit-special 'set! [type [set! var val]]
  (str (emit var) " = " (emit val)))

(defmethod emit-special 'new [type [new class & args]]
  (str "new " (emit class) (comma-list (map emit args))))

(defn emit-do [exprs]
  (cond
   (= (count exprs) 0) ""
   (= (count exprs) 1) ((comp statement  emit) (first exprs))
   true (let [statements (map (comp statement emit) exprs)]
          (statement
           (str "function () {\n"
                (str/join "" (butlast statements))
                "return "
                (last statements)
                "}()")))))

(defmethod emit-special 'do [type [ do & exprs]]
  (emit-do exprs))

(defn emit-function [name sig body]
  (assert (or (symbol? name) (nil? name)))
  (assert (vector? sig))
  (str "function " name (comma-list sig) " {\n" (apply str (map (comp statement emit) body)) " }\n"))

(defmethod emit-special 'fn [type [fn & expr]]
  (if (symbol? (first expr))
    (let [name (first expr)
	  signature (second expr)
	  body (rest (rest expr))]
      (emit-function name signature body))
    (let [signature (first expr)
	  body (rest expr)]
      (emit-function nil signature body))))

(defmethod emit clojure.lang.Cons [expr]
  (emit (list* expr)))

(defmethod emit clojure.lang.IPersistentList [expr]
  (if (symbol? (first expr))
    (let [head (symbol (name (first expr)))  ; remove any ns resolution
	  expr (conj (rest expr) head)]
      (cond 
	(and (= (str/get (str head) 0) \.) (> (count (str head)) 1)) (emit-special 'dot-method expr)
	(special-form? head) (emit-special head expr)
	(infix-operator? head) (emit-infix head expr)
	:else (emit-special 'funcall expr)))
    (throw (new Exception (str "invalid form: " expr)))))

(defmethod emit clojure.lang.IPersistentVector [expr]
  (str "[" (str/join ", " (map emit expr)) "]"))

;(defmethod emit clojure.lang.LazySeq [expr]
;  (emit (into [] expr)))

(defmethod emit clojure.lang.IPersistentMap [expr]
  (letfn [(json-pair [pair] (str (emit (key pair)) ": " (emit (val pair))))]
    (str "{" (str/join ", " (map json-pair (seq expr))) "}")))

(defn _js [forms]
  (let [code (if (> (count forms) 1)
	       (emit-do forms)
	       (emit (first forms)))]
    ;(println "js " forms " => " code)
    code))

(defn- unquote?
  "Tests whether the form is (unquote ...)."
  [form]
  (and (seq? form) (symbol? (first form)) (= (symbol (name (first form))) 'clj)))

(defn handle-unquote [form]
  (second form))

(declare inner-walk outer-walk)

(defn- inner-walk [form]
  (cond 
    (unquote? form) (handle-unquote form)
    :else (walk inner-walk outer-walk form)))

(defn- outer-walk [form]
  (cond
    (symbol? form) (list 'quote form)
    (seq? form) (list* 'list form)
    :else form))

(defmacro quasiquote [form]
  (let [post-form (walk inner-walk outer-walk form)]
    post-form))

(defmacro js 
  "takes one or more forms. Returns a string of the forms translated into javascript"
  [& forms]
  `(_js (quasiquote ~forms)))

