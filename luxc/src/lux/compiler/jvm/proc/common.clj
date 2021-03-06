(ns lux.compiler.jvm.proc.common
  (:require (clojure [string :as string]
                     [set :as set]
                     [template :refer [do-template]])
            clojure.core.match
            clojure.core.match.array
            (lux [base :as & :refer [|do return* return |let |case]]
                 [type :as &type]
                 [lexer :as &lexer]
                 [parser :as &parser]
                 [analyser :as &analyser]
                 [optimizer :as &o]
                 [host :as &host])
            [lux.type.host :as &host-type]
            [lux.host.generics :as &host-generics]
            [lux.analyser.base :as &a]
            [lux.compiler.jvm.base :as &&])
  (:import (org.objectweb.asm Opcodes
                              Label
                              ClassWriter
                              MethodVisitor
                              AnnotationVisitor)))

;; [Resources]
(defn ^:private compile-array-new [compile ?values special-args]
  (|do [:let [(&/$Cons ?length (&/$Nil)) ?values]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?length)
        :let [_ (doto *writer*
                  &&/unwrap-long
                  (.visitInsn Opcodes/L2I))]
        :let [_ (.visitTypeInsn *writer* Opcodes/ANEWARRAY "java/lang/Object")]]
    (return nil)))

(defn ^:private compile-array-get [compile ?values special-args]
  (|do [:let [(&/$Cons ?array (&/$Cons ?idx (&/$Nil))) ?values
              ;; (&/$Nil) special-args
              ]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?array)
        :let [_ (.visitTypeInsn *writer* Opcodes/CHECKCAST "[Ljava/lang/Object;")]
        _ (compile ?idx)
        :let [_ (doto *writer*
                  &&/unwrap-long
                  (.visitInsn Opcodes/L2I))]
        :let [_ (.visitInsn *writer* Opcodes/AALOAD)]
        :let [$is-null (new Label)
              $end (new Label)
              _ (doto *writer*
                  (.visitInsn Opcodes/DUP)
                  (.visitJumpInsn Opcodes/IFNULL $is-null)
                  (.visitLdcInsn (int 1))
                  (.visitLdcInsn "")
                  (.visitInsn Opcodes/DUP2_X1) ;; I?2I?
                  (.visitInsn Opcodes/POP2) ;; I?2
                  (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxRT" "sum_make" "(ILjava/lang/Object;Ljava/lang/Object;)[Ljava/lang/Object;")
                  (.visitJumpInsn Opcodes/GOTO $end)
                  (.visitLabel $is-null)
                  (.visitInsn Opcodes/POP)
                  (.visitLdcInsn (int 0))
                  (.visitInsn Opcodes/ACONST_NULL)
                  (.visitLdcInsn &/unit-tag)
                  (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxRT" "sum_make" "(ILjava/lang/Object;Ljava/lang/Object;)[Ljava/lang/Object;")
                  (.visitLabel $end))]]
    (return nil)))

(defn ^:private compile-array-put [compile ?values special-args]
  (|do [:let [(&/$Cons ?array (&/$Cons ?idx (&/$Cons ?elem (&/$Nil)))) ?values
              ;; (&/$Nil) special-args
              ]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?array)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "[Ljava/lang/Object;")
                  (.visitInsn Opcodes/DUP))]
        _ (compile ?idx)
        :let [_ (doto *writer*
                  &&/unwrap-long
                  (.visitInsn Opcodes/L2I))]
        _ (compile ?elem)
        :let [_ (.visitInsn *writer* Opcodes/AASTORE)]]
    (return nil)))

(defn ^:private compile-array-remove [compile ?values special-args]
  (|do [:let [(&/$Cons ?array (&/$Cons ?idx (&/$Nil))) ?values
              ;; (&/$Nil) special-args
              ]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?array)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "[Ljava/lang/Object;")
                  (.visitInsn Opcodes/DUP))]
        _ (compile ?idx)
        :let [_ (doto *writer*
                  &&/unwrap-long
                  (.visitInsn Opcodes/L2I))]
        :let [_ (doto *writer*
                  (.visitInsn Opcodes/ACONST_NULL)
                  (.visitInsn Opcodes/AASTORE))]]
    (return nil)))

(defn ^:private compile-array-size [compile ?values special-args]
  (|do [:let [(&/$Cons ?array (&/$Nil)) ?values
              ;; (&/$Nil) special-args
              ]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?array)
        :let [_ (.visitTypeInsn *writer* Opcodes/CHECKCAST "[Ljava/lang/Object;")]
        :let [_ (doto *writer*
                  (.visitInsn Opcodes/ARRAYLENGTH)
                  (.visitInsn Opcodes/I2L)
                  &&/wrap-long)]]
    (return nil)))

(do-template [<name> <op>]
  (defn <name> [compile ?values special-args]
    (|do [:let [(&/$Cons ?input (&/$Cons ?mask (&/$Nil))) ?values]
          ^MethodVisitor *writer* &/get-writer
          _ (compile ?input)
          :let [_ (&&/unwrap-long *writer*)]
          _ (compile ?mask)
          :let [_ (&&/unwrap-long *writer*)]
          :let [_ (doto *writer*
                    (.visitInsn <op>)
                    &&/wrap-long)]]
      (return nil)))

  ^:private compile-i64-and Opcodes/LAND
  ^:private compile-i64-or  Opcodes/LOR
  ^:private compile-i64-xor Opcodes/LXOR
  )

(do-template [<name> <op>]
  (defn <name> [compile ?values special-args]
    (|do [:let [(&/$Cons ?input (&/$Cons ?shift (&/$Nil))) ?values]
          ^MethodVisitor *writer* &/get-writer
          _ (compile ?input)
          :let [_ (&&/unwrap-long *writer*)]
          _ (compile ?shift)
          :let [_ (doto *writer*
                    &&/unwrap-long
                    (.visitInsn Opcodes/L2I))]
          :let [_ (doto *writer*
                    (.visitInsn <op>)
                    &&/wrap-long)]]
      (return nil)))

  ^:private compile-i64-left-shift           Opcodes/LSHL
  ^:private compile-i64-arithmetic-right-shift          Opcodes/LSHR
  ^:private compile-i64-logical-right-shift Opcodes/LUSHR
  )

(defn ^:private compile-lux-is [compile ?values special-args]
  (|do [:let [(&/$Cons ?left (&/$Cons ?right (&/$Nil))) ?values]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?left)
        _ (compile ?right)
        :let [$then (new Label)
              $end (new Label)
              _ (doto *writer*
                  (.visitJumpInsn Opcodes/IF_ACMPEQ $then)
                  ;; else
                  (.visitFieldInsn Opcodes/GETSTATIC "java/lang/Boolean" "FALSE" "Ljava/lang/Boolean;")
                  (.visitJumpInsn Opcodes/GOTO $end)
                  (.visitLabel $then)
                  (.visitFieldInsn Opcodes/GETSTATIC "java/lang/Boolean" "TRUE" "Ljava/lang/Boolean;")
                  (.visitLabel $end))]]
    (return nil)))

(defn ^:private compile-lux-try [compile ?values special-args]
  (|do [:let [(&/$Cons ?op (&/$Nil)) ?values]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?op)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "lux/Function")
                  (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxRT" "runTry" "(Llux/Function;)[Ljava/lang/Object;"))]]
    (return nil)))

(do-template [<name> <opcode> <unwrap> <wrap>]
  (defn <name> [compile ?values special-args]
    (|do [:let [(&/$Cons ?x (&/$Cons ?y (&/$Nil))) ?values]
          ^MethodVisitor *writer* &/get-writer
          _ (compile ?x)
          :let [_ (doto *writer*
                    <unwrap>)]
          _ (compile ?y)
          :let [_ (doto *writer*
                    <unwrap>)
                _ (doto *writer*
                    (.visitInsn <opcode>)
                    <wrap>)]]
      (return nil)))

  ^:private compile-i64-add   Opcodes/LADD &&/unwrap-long &&/wrap-long
  ^:private compile-i64-sub   Opcodes/LSUB &&/unwrap-long &&/wrap-long
  
  ^:private compile-int-mul   Opcodes/LMUL &&/unwrap-long &&/wrap-long
  ^:private compile-int-div   Opcodes/LDIV &&/unwrap-long &&/wrap-long
  ^:private compile-int-rem   Opcodes/LREM &&/unwrap-long &&/wrap-long
  
  ^:private compile-frac-add  Opcodes/DADD &&/unwrap-double &&/wrap-double
  ^:private compile-frac-sub  Opcodes/DSUB &&/unwrap-double &&/wrap-double
  ^:private compile-frac-mul  Opcodes/DMUL &&/unwrap-double &&/wrap-double
  ^:private compile-frac-div  Opcodes/DDIV &&/unwrap-double &&/wrap-double
  ^:private compile-frac-rem  Opcodes/DREM &&/unwrap-double &&/wrap-double
  )

(do-template [<name> <cmpcode> <cmp-output> <unwrap>]
  (defn <name> [compile ?values special-args]
    (|do [:let [(&/$Cons ?x (&/$Cons ?y (&/$Nil))) ?values]
          ^MethodVisitor *writer* &/get-writer
          _ (compile ?x)
          :let [_ (doto *writer*
                    <unwrap>)]
          _ (compile ?y)
          :let [_ (doto *writer*
                    <unwrap>)
                $then (new Label)
                $end (new Label)
                _ (doto *writer*
                    (.visitInsn <cmpcode>)
                    (.visitLdcInsn (int <cmp-output>))
                    (.visitJumpInsn Opcodes/IF_ICMPEQ $then)
                    (.visitFieldInsn Opcodes/GETSTATIC (&host-generics/->bytecode-class-name "java.lang.Boolean") "FALSE"  (&host-generics/->type-signature "java.lang.Boolean"))
                    (.visitJumpInsn Opcodes/GOTO $end)
                    (.visitLabel $then)
                    (.visitFieldInsn Opcodes/GETSTATIC (&host-generics/->bytecode-class-name "java.lang.Boolean") "TRUE" (&host-generics/->type-signature "java.lang.Boolean"))
                    (.visitLabel $end))]]
      (return nil)))

  ^:private compile-i64-eq  Opcodes/LCMP   0 &&/unwrap-long
  
  ^:private compile-int-lt  Opcodes/LCMP  -1 &&/unwrap-long

  ^:private compile-frac-eq Opcodes/DCMPG  0 &&/unwrap-double
  ^:private compile-frac-lt Opcodes/DCMPG -1 &&/unwrap-double
  )

(do-template [<name> <instr>]
  (defn <name> [compile ?values special-args]
    (|do [:let [(&/$Nil) ?values]
          ^MethodVisitor *writer* &/get-writer
          :let [_ (doto *writer*
                    (.visitLdcInsn <instr>)
                    &&/wrap-double)]]
      (return nil)))

  ^:private compile-frac-smallest Double/MIN_VALUE
  ^:private compile-frac-min (* -1.0 Double/MAX_VALUE)
  ^:private compile-frac-max Double/MAX_VALUE

  ^:private compile-frac-not-a-number Double/NaN
  ^:private compile-frac-positive-infinity Double/POSITIVE_INFINITY
  ^:private compile-frac-negative-infinity Double/NEGATIVE_INFINITY
  )

(defn ^:private compile-frac-encode [compile ?values special-args]
  (|do [:let [(&/$Cons ?input (&/$Nil)) ?values]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?input)
        :let [_ (doto *writer*
                  &&/unwrap-double
                  (.visitMethodInsn Opcodes/INVOKESTATIC "java/lang/Double" "toString" "(D)Ljava/lang/String;"))]]
    (return nil)))

(defn ^:private compile-frac-decode [compile ?values special-args]
  (|do [:let [(&/$Cons ?input (&/$Nil)) ?values]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?input)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "java/lang/String")
                  (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxRT" "decode_frac" "(Ljava/lang/String;)[Ljava/lang/Object;"))]]
    (return nil)))

(defn ^:private compile-int-char [compile ?values special-args]
  (|do [:let [(&/$Cons ?x (&/$Nil)) ?values]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?x)
        :let [_ (doto *writer*
                  &&/unwrap-long
                  (.visitInsn Opcodes/L2I)
                  (.visitInsn Opcodes/I2C)
                  (.visitMethodInsn Opcodes/INVOKESTATIC "java/lang/String" "valueOf" "(C)Ljava/lang/String;"))]]
    (return nil)))

(do-template [<name> <unwrap> <op> <wrap>]
  (defn <name> [compile ?values special-args]
    (|do [:let [(&/$Cons ?input (&/$Nil)) ?values]
          ^MethodVisitor *writer* &/get-writer
          _ (compile ?input)
          :let [_ (doto *writer*
                    <unwrap>
                    (.visitInsn <op>)
                    <wrap>)]]
      (return nil)))

  ^:private compile-frac-int &&/unwrap-double Opcodes/D2L &&/wrap-long
  ^:private compile-int-frac &&/unwrap-long   Opcodes/L2D &&/wrap-double
  )

(defn ^:private compile-text-eq [compile ?values special-args]
  (|do [:let [(&/$Cons ?x (&/$Cons ?y (&/$Nil))) ?values]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?x)
        _ (compile ?y)
        :let [_ (doto *writer*
                  (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/lang/Object" "equals" "(Ljava/lang/Object;)Z")
                  (&&/wrap-boolean))]]
    (return nil)))

(defn ^:private compile-text-lt [compile ?values special-args]
  (|do [:let [(&/$Cons ?x (&/$Cons ?y (&/$Nil))) ?values]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?x)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "java/lang/String"))]
        _ (compile ?y)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "java/lang/String"))]
        :let [$then (new Label)
              $end (new Label)
              _ (doto *writer*
                  (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/lang/String" "compareTo" "(Ljava/lang/String;)I")
                  (.visitLdcInsn (int -1))
                  (.visitJumpInsn Opcodes/IF_ICMPEQ $then)
                  (.visitFieldInsn Opcodes/GETSTATIC (&host-generics/->bytecode-class-name "java.lang.Boolean") "FALSE" (&host-generics/->type-signature "java.lang.Boolean"))
                  (.visitJumpInsn Opcodes/GOTO $end)
                  (.visitLabel $then)
                  (.visitFieldInsn Opcodes/GETSTATIC (&host-generics/->bytecode-class-name "java.lang.Boolean") "TRUE" (&host-generics/->type-signature "java.lang.Boolean"))
                  (.visitLabel $end))]]
    (return nil)))

(defn compile-text-concat [compile ?values special-args]
  (|do [:let [(&/$Cons ?x (&/$Cons ?y (&/$Nil))) ?values]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?x)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "java/lang/String"))]
        _ (compile ?y)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "java/lang/String"))]
        :let [_ (doto *writer*
                  (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/lang/String" "concat" "(Ljava/lang/String;)Ljava/lang/String;"))]]
    (return nil)))

(defn compile-text-clip [compile ?values special-args]
  (|do [:let [(&/$Cons ?text (&/$Cons ?from (&/$Cons ?to (&/$Nil)))) ?values]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?text)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "java/lang/String"))]
        _ (compile ?from)
        :let [_ (doto *writer*
                  &&/unwrap-long
                  (.visitInsn Opcodes/L2I))]
        _ (compile ?to)
        :let [_ (doto *writer*
                  &&/unwrap-long
                  (.visitInsn Opcodes/L2I))]
        :let [_ (doto *writer*
                  (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxRT" "text_clip" "(Ljava/lang/String;II)[Ljava/lang/Object;"))]]
    (return nil)))

(do-template [<name> <method>]
  (defn <name> [compile ?values special-args]
    (|do [:let [(&/$Cons ?text (&/$Cons ?part (&/$Cons ?start (&/$Nil)))) ?values]
          ^MethodVisitor *writer* &/get-writer
          _ (compile ?text)
          :let [_ (doto *writer*
                    (.visitTypeInsn Opcodes/CHECKCAST "java/lang/String"))]
          _ (compile ?part)
          :let [_ (doto *writer*
                    (.visitTypeInsn Opcodes/CHECKCAST "java/lang/String"))]
          _ (compile ?start)
          :let [_ (doto *writer*
                    &&/unwrap-long
                    (.visitInsn Opcodes/L2I))]
          :let [_ (doto *writer*
                    (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/lang/String" <method> "(Ljava/lang/String;I)I"))]
          :let [$not-found (new Label)
                $end (new Label)
                _ (doto *writer*
                    (.visitInsn Opcodes/DUP)
                    (.visitLdcInsn (int -1))
                    (.visitJumpInsn Opcodes/IF_ICMPEQ $not-found)
                    (.visitInsn Opcodes/I2L)
                    &&/wrap-long
                    (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxRT" "make_some" "(Ljava/lang/Object;)[Ljava/lang/Object;")
                    (.visitJumpInsn Opcodes/GOTO $end)
                    (.visitLabel $not-found)
                    (.visitInsn Opcodes/POP)
                    (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxRT" "make_none" "()[Ljava/lang/Object;")
                    (.visitLabel $end))]]
      (return nil)))

  ^:private compile-text-index      "indexOf"
  )

(do-template [<name> <class> <method>]
  (defn <name> [compile ?values special-args]
    (|do [:let [(&/$Cons ?text (&/$Nil)) ?values]
          ^MethodVisitor *writer* &/get-writer
          _ (compile ?text)
          :let [_ (doto *writer*
                    (.visitTypeInsn Opcodes/CHECKCAST "java/lang/String")
                    (.visitMethodInsn Opcodes/INVOKEVIRTUAL <class> <method> "()I")
                    (.visitInsn Opcodes/I2L)
                    &&/wrap-long)]]
      (return nil)))

  ^:private compile-text-size "java/lang/String" "length"
  ^:private compile-text-hash "java/lang/Object" "hashCode"
  )

(defn ^:private compile-text-replace-all [compile ?values special-args]
  (|do [:let [(&/$Cons ?text (&/$Cons ?pattern (&/$Cons ?replacement (&/$Nil)))) ?values]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?text)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "java/lang/String"))]
        _ (compile ?pattern)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "java/lang/String"))]
        _ (compile ?replacement)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "java/lang/String"))]
        :let [_ (doto *writer*
                  (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/lang/String" "replace" "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;"))]]
    (return nil)))

(defn ^:private compile-text-contains? [compile ?values special-args]
  (|do [:let [(&/$Cons ?text (&/$Cons ?sub (&/$Nil))) ?values]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?text)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "java/lang/String"))]
        _ (compile ?sub)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "java/lang/String"))]
        :let [_ (doto *writer*
                  (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/lang/String" "contains" "(Ljava/lang/CharSequence;)Z")
                  &&/wrap-boolean)]]
    (return nil)))

(defn ^:private compile-text-char [compile ?values special-args]
  (|do [:let [(&/$Cons ?text (&/$Cons ?idx (&/$Nil))) ?values]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?text)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "java/lang/String"))]
        _ (compile ?idx)
        :let [_ (doto *writer*
                  &&/unwrap-long
                  (.visitInsn Opcodes/L2I)
                  (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxRT" "text_char" "(Ljava/lang/String;I)[Ljava/lang/Object;"))]]
    (return nil)))

(defn ^:private compile-io-log [compile ?values special-args]
  (|do [:let [(&/$Cons ?x (&/$Nil)) ?values]
        ^MethodVisitor *writer* &/get-writer
        :let [_ (doto *writer*
                  (.visitFieldInsn Opcodes/GETSTATIC "java/lang/System" "out" "Ljava/io/PrintStream;"))]
        _ (compile ?x)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "java/lang/String")
                  (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/io/PrintStream" "println" "(Ljava/lang/String;)V")
                  (.visitLdcInsn &/unit-tag))]]
    (return nil)))

(defn ^:private compile-io-error [compile ?values special-args]
  (|do [:let [(&/$Cons ?message (&/$Nil)) ?values]
        ^MethodVisitor *writer* &/get-writer
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/NEW "java/lang/Error")
                  (.visitInsn Opcodes/DUP))]
        _ (compile ?message)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "java/lang/String")
                  (.visitMethodInsn Opcodes/INVOKESPECIAL "java/lang/Error" "<init>" "(Ljava/lang/String;)V")
                  (.visitInsn Opcodes/ATHROW))]]
    (return nil)))

(defn ^:private compile-io-exit [compile ?values special-args]
  (|do [:let [(&/$Cons ?code (&/$Nil)) ?values]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?code)
        :let [_ (doto *writer*
                  &&/unwrap-long
                  (.visitInsn Opcodes/L2I)
                  (.visitMethodInsn Opcodes/INVOKESTATIC "java/lang/System" "exit" "(I)V")
                  (.visitInsn Opcodes/ACONST_NULL))]]
    (return nil)))

(defn ^:private compile-io-current-time [compile ?values special-args]
  (|do [:let [(&/$Nil) ?values]
        ^MethodVisitor *writer* &/get-writer
        :let [_ (doto *writer*
                  (.visitMethodInsn Opcodes/INVOKESTATIC "java/lang/System" "currentTimeMillis" "()J")
                  &&/wrap-long)]]
    (return nil)))

(do-template [<name> <method>]
  (defn <name> [compile ?values special-args]
    (|do [:let [(&/$Cons ?input (&/$Nil)) ?values]
          ^MethodVisitor *writer* &/get-writer
          _ (compile ?input)
          :let [_ (doto *writer*
                    &&/unwrap-double
                    (.visitMethodInsn Opcodes/INVOKESTATIC "java/lang/Math" <method> "(D)D")
                    &&/wrap-double)]]
      (return nil)))

  ^:private compile-math-cos "cos"
  ^:private compile-math-sin "sin"
  ^:private compile-math-tan "tan"
  ^:private compile-math-acos "acos"
  ^:private compile-math-asin "asin"
  ^:private compile-math-atan "atan"
  ^:private compile-math-exp "exp"
  ^:private compile-math-log "log"
  ^:private compile-math-ceil "ceil"
  ^:private compile-math-floor "floor"
  )

(do-template [<name> <method>]
  (defn <name> [compile ?values special-args]
    (|do [:let [(&/$Cons ?input (&/$Cons ?param (&/$Nil))) ?values]
          ^MethodVisitor *writer* &/get-writer
          _ (compile ?input)
          :let [_ (doto *writer*
                    &&/unwrap-double)]
          _ (compile ?param)
          :let [_ (doto *writer*
                    &&/unwrap-double)]
          :let [_ (doto *writer*
                    (.visitMethodInsn Opcodes/INVOKESTATIC "java/lang/Math" <method> "(DD)D")
                    &&/wrap-double)]]
      (return nil)))

  ^:private compile-math-pow "pow"
  )

(defn ^:private compile-atom-new [compile ?values special-args]
  (|do [:let [(&/$Cons ?init (&/$Nil)) ?values]
        ^MethodVisitor *writer* &/get-writer
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/NEW "java/util/concurrent/atomic/AtomicReference")
                  (.visitInsn Opcodes/DUP))]
        _ (compile ?init)
        :let [_ (doto *writer*
                  (.visitMethodInsn Opcodes/INVOKESPECIAL "java/util/concurrent/atomic/AtomicReference" "<init>" "(Ljava/lang/Object;)V"))]]
    (return nil)))

(defn ^:private compile-atom-read [compile ?values special-args]
  (|do [:let [(&/$Cons ?atom (&/$Nil)) ?values]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?atom)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "java/util/concurrent/atomic/AtomicReference"))]
        :let [_ (doto *writer*
                  (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/util/concurrent/atomic/AtomicReference" "get" "()Ljava/lang/Object;"))]]
    (return nil)))

(defn ^:private compile-atom-compare-and-swap [compile ?values special-args]
  (|do [:let [(&/$Cons ?atom (&/$Cons ?old (&/$Cons ?new (&/$Nil)))) ?values]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?atom)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "java/util/concurrent/atomic/AtomicReference"))]
        _ (compile ?old)
        _ (compile ?new)
        :let [_ (doto *writer*
                  (.visitMethodInsn Opcodes/INVOKEVIRTUAL "java/util/concurrent/atomic/AtomicReference" "compareAndSet" "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                  &&/wrap-boolean)]]
    (return nil)))

(defn ^:private compile-box-new [compile ?values special-args]
  (|do [:let [(&/$Cons initS (&/$Nil)) ?values]
        ^MethodVisitor *writer* &/get-writer
        :let [_ (doto *writer*
                  (.visitLdcInsn (int 1))
                  (.visitTypeInsn Opcodes/ANEWARRAY "java/lang/Object"))]
        :let [_ (doto *writer*
                  (.visitInsn Opcodes/DUP)
                  (.visitLdcInsn (int 0)))]
        _ (compile initS)
        :let [_ (doto *writer*
                  (.visitInsn Opcodes/AASTORE))]]
    (return nil)))

(defn ^:private compile-box-read [compile ?values special-args]
  (|do [:let [(&/$Cons boxS (&/$Nil)) ?values
              ;; (&/$Nil) special-args
              ]
        ^MethodVisitor *writer* &/get-writer
        _ (compile boxS)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "[Ljava/lang/Object;")
                  (.visitLdcInsn (int 0))
                  (.visitInsn Opcodes/AALOAD))]]
    (return nil)))

(defn ^:private compile-box-write [compile ?values special-args]
  (|do [:let [(&/$Cons valueS (&/$Cons boxS (&/$Nil))) ?values
              ;; (&/$Nil) special-args
              ]
        ^MethodVisitor *writer* &/get-writer
        _ (compile boxS)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "[Ljava/lang/Object;")
                  (.visitLdcInsn (int 0)))]
        _ (compile valueS)
        :let [_ (doto *writer*
                  (.visitInsn Opcodes/AASTORE)
                  (.visitLdcInsn &/unit-tag))]]
    (return nil)))

(defn ^:private compile-process-parallelism [compile ?values special-args]
  (|do [:let [(&/$Nil) ?values]
        ^MethodVisitor *writer* &/get-writer
        :let [_ (doto *writer*
                  (.visitFieldInsn Opcodes/GETSTATIC "lux/LuxRT" "concurrency_level" "I")
                  (.visitInsn Opcodes/I2L)
                  &&/wrap-long)]]
    (return nil)))

(defn ^:private compile-process-schedule [compile ?values special-args]
  (|do [:let [(&/$Cons ?milliseconds (&/$Cons ?procedure (&/$Nil))) ?values]
        ^MethodVisitor *writer* &/get-writer
        _ (compile ?milliseconds)
        :let [_ (doto *writer*
                  &&/unwrap-long)]
        _ (compile ?procedure)
        :let [_ (doto *writer*
                  (.visitTypeInsn Opcodes/CHECKCAST "lux/Function"))]
        :let [_ (doto *writer*
                  (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxRT" "schedule" "(JLlux/Function;)Ljava/lang/Object;"))]]
    (return nil)))

(defn compile-proc [compile category proc ?values special-args]
  (case category
    "lux"
    (case proc
      "is"                   (compile-lux-is compile ?values special-args)
      "try"                  (compile-lux-try compile ?values special-args))

    "io"
    (case proc
      "log"                  (compile-io-log compile ?values special-args)
      "error"                (compile-io-error compile ?values special-args)
      "exit"                 (compile-io-exit compile ?values special-args)
      "current-time"         (compile-io-current-time compile ?values special-args)
      )

    "text"
    (case proc
      "="                    (compile-text-eq compile ?values special-args)
      "<"                    (compile-text-lt compile ?values special-args)
      "concat"               (compile-text-concat compile ?values special-args)
      "clip"                 (compile-text-clip compile ?values special-args)
      "index"                (compile-text-index compile ?values special-args)
      "size"                 (compile-text-size compile ?values special-args)
      "hash"                 (compile-text-hash compile ?values special-args)
      "replace-all"          (compile-text-replace-all compile ?values special-args)
      "char"                 (compile-text-char compile ?values special-args)
      "contains?"            (compile-text-contains? compile ?values special-args)
      )
    
    "i64"
    (case proc
      "and"                    (compile-i64-and compile ?values special-args)
      "or"                     (compile-i64-or compile ?values special-args)
      "xor"                    (compile-i64-xor compile ?values special-args)
      "left-shift"             (compile-i64-left-shift compile ?values special-args)
      "arithmetic-right-shift" (compile-i64-arithmetic-right-shift compile ?values special-args)
      "logical-right-shift"    (compile-i64-logical-right-shift compile ?values special-args)
      "="                      (compile-i64-eq compile ?values special-args)
      "+"                      (compile-i64-add compile ?values special-args)
      "-"                      (compile-i64-sub compile ?values special-args))
    
    "array"
    (case proc
      "new" (compile-array-new compile ?values special-args)
      "get" (compile-array-get compile ?values special-args)
      "put" (compile-array-put compile ?values special-args)
      "remove" (compile-array-remove compile ?values special-args)
      "size" (compile-array-size compile ?values special-args))

    "int"
    (case proc
      "*"       (compile-int-mul compile ?values special-args)
      "/"       (compile-int-div compile ?values special-args)
      "%"       (compile-int-rem compile ?values special-args)
      "<"       (compile-int-lt compile ?values special-args)
      "frac" (compile-int-frac compile ?values special-args)
      "char"    (compile-int-char compile ?values special-args)
      )

    "frac"
    (case proc
      "+"         (compile-frac-add compile ?values special-args)
      "-"         (compile-frac-sub compile ?values special-args)
      "*"         (compile-frac-mul compile ?values special-args)
      "/"         (compile-frac-div compile ?values special-args)
      "%"         (compile-frac-rem compile ?values special-args)
      "="         (compile-frac-eq compile ?values special-args)
      "<"         (compile-frac-lt compile ?values special-args)
      "smallest" (compile-frac-smallest compile ?values special-args)
      "max" (compile-frac-max compile ?values special-args)
      "min" (compile-frac-min compile ?values special-args)
      "not-a-number" (compile-frac-not-a-number compile ?values special-args)
      "positive-infinity" (compile-frac-positive-infinity compile ?values special-args)
      "negative-infinity" (compile-frac-negative-infinity compile ?values special-args)
      "int"    (compile-frac-int compile ?values special-args)
      "encode"    (compile-frac-encode compile ?values special-args)
      "decode"    (compile-frac-decode compile ?values special-args)
      )

    "math"
    (case proc
      "cos" (compile-math-cos compile ?values special-args)
      "sin" (compile-math-sin compile ?values special-args)
      "tan" (compile-math-tan compile ?values special-args)
      "acos" (compile-math-acos compile ?values special-args)
      "asin" (compile-math-asin compile ?values special-args)
      "atan" (compile-math-atan compile ?values special-args)
      "exp" (compile-math-exp compile ?values special-args)
      "log" (compile-math-log compile ?values special-args)
      "ceil" (compile-math-ceil compile ?values special-args)
      "floor" (compile-math-floor compile ?values special-args)
      "pow" (compile-math-pow compile ?values special-args)
      )

    "box"
    (case proc
      "new" (compile-box-new compile ?values special-args)
      "read" (compile-box-read compile ?values special-args)
      "write" (compile-box-write compile ?values special-args)
      )

    "atom"
    (case proc
      "new" (compile-atom-new compile ?values special-args)
      "read" (compile-atom-read compile ?values special-args)
      "compare-and-swap" (compile-atom-compare-and-swap compile ?values special-args)
      )

    "process"
    (case proc
      "parallelism" (compile-process-parallelism compile ?values special-args)
      "schedule" (compile-process-schedule compile ?values special-args)
      )
    
    ;; else
    (&/fail-with-loc (str "[Compiler Error] Unknown procedure: " [category proc]))))
