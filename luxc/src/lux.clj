(ns lux
  (:gen-class)
  (:require [lux.base :as & :refer [|let |do return return* |case]]
            [lux.compiler :as &compiler]
            [lux.repl :as &repl]
            [clojure.string :as string]
            :reload-all)
  (:import (java.io File)))

(def unit-separator (str (char 31)))

(defn ^:private separate-paths
  "(-> Text (List Text))"
  [resources-dirs]
  (-> resources-dirs
      (string/replace unit-separator "\n")
      string/split-lines
      &/->list))

(defn -main [& args]
  (|case (&/->list args)
    (&/$Cons "release" (&/$Cons platform (&/$Cons program-module (&/$Cons resources-dirs (&/$Cons source-dirs (&/$Cons target-dir (&/$Nil)))))))
    (time (&compiler/compile-program platform &/$Release program-module (separate-paths resources-dirs) (separate-paths source-dirs) target-dir))

    (&/$Cons "debug" (&/$Cons platform (&/$Cons program-module (&/$Cons resources-dirs (&/$Cons source-dirs (&/$Cons target-dir (&/$Nil)))))))
    (time (&compiler/compile-program platform &/$Debug program-module (separate-paths resources-dirs) (separate-paths source-dirs) target-dir))

    (&/$Cons "repl" (&/$Cons resources-dirs (&/$Cons source-dirs (&/$Cons target-dir (&/$Nil)))))
    (&repl/repl (separate-paths resources-dirs)
                (separate-paths source-dirs)
                target-dir)

    _
    (println "Can't understand command.")))
