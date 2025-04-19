(ns shlex.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader StringReader]
           [java.util ArrayDeque]))

;; Utility functions
(defn- char-seq
  "Convert a reader to a sequence of characters."
  [^BufferedReader reader]
  (lazy-seq
    (let [c (.read reader)]
      (if (neg? c)
        nil
        (cons (char c) (char-seq reader))))))

(defn- make-deque
  "Create a new ArrayDeque."
  []
  (ArrayDeque.))

(defn- deque-push
  "Push an item onto the deque."
  [^ArrayDeque deque item]
  (.addFirst deque item)
  deque)

(defn- deque-pop
  "Pop an item from the deque, or return nil if empty."
  [^ArrayDeque deque]
  (when-not (.isEmpty deque)
    (.removeFirst deque)))

;; Default character sets
(def default-wordchars
  (set (concat (map char "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_"))))

(def posix-wordchars
  (set (concat default-wordchars
               (map char "ßàáâãäåæçèéêëìíîïðñòóôõöøùúûüýþÿÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖØÙÚÛÜÝÞ"))))

(def default-whitespace
  #{\space \tab \return \newline})

(def default-quotes
  #{\' \"})

(def default-escape
  #{\\})

(def default-escapedquotes
  #{\"})

(def default-commenters
  #{\#})

(def default-punctuation-chars
  #{\( \) \; \< \> \| \&})

;; Lexer state constructor
(defn make-lexer
  "Create a new lexer state."
  [{:keys [instream infile posix punctuation-chars]
    :or {posix false punctuation-chars ""}}]
  (let [instream (cond
                   (string? instream) (StringReader. instream)
                   instream instream
                   :else (io/reader *in*))
        punctuation-chars (if (true? punctuation-chars)
                            default-punctuation-chars
                            (set punctuation-chars))
        wordchars (cond-> (if posix posix-wordchars default-wordchars)
                    (seq punctuation-chars) (clojure.set/difference punctuation-chars)
                    (seq punctuation-chars) (clojure.set/union #{\~ \- \. \/ \* \? \=}))]
    {:instream instream
     :infile infile
     :posix posix
     :eof (if posix nil "")
     :commenters default-commenters
     :wordchars wordchars
     :whitespace default-whitespace
     :whitespace-split false
     :quotes default-quotes
     :escape default-escape
     :escapedquotes default-escapedquotes
     :state \space
     :pushback (make-deque)
     :pushback-chars (make-deque)
     :token ""
     :filestack (make-deque)
     :source nil
     :lineno 1
     :debug 0
     :punctuation-chars punctuation-chars}))

;; Lexer state manipulation
(defn- update-lexer
  "Update lexer state immutably."
  [lexer & kvs]
  (reduce (fn [m [k v]] (assoc m k v)) lexer (partition 2 kvs)))

(defn push-token
  "Push a token onto the pushback stack."
  [lexer token]
  (when (pos? (:debug lexer))
    (println (str "shlex: pushing token " (pr-str token))))
  (update-lexer lexer :pushback (deque-push (:pushback lexer) token)))

(defn push-source
  "Push a new input source onto the filestack."
  [lexer newstream newfile]
  (let [newstream (if (string? newstream)
                    (StringReader. newstream)
                    newstream)]
    (when (pos? (:debug lexer))
      (println (str "shlex: pushing to " (or newfile "stream") " " (pr-str newstream))))
    (update-lexer lexer
                  :filestack (deque-push (:filestack lexer)
                                         [(:infile lexer) (:instream lexer) (:lineno lexer)])
                  :infile newfile
                  :instream newstream
                  :lineno 1
                  :state \space)))

(defn pop-source
  "Pop the current input source from the filestack."
  [lexer]
  (let [[infile instream lineno] (deque-pop (:filestack lexer))]
    (when (pos? (:debug lexer))
      (println (str "shlex: popping to " (pr-str instream) ", line " lineno)))
    (.close ^BufferedReader (:instream lexer))
    (update-lexer lexer
                  :infile infile
                  :instream instream
                  :lineno lineno
                  :state \space)))

(defn- read-char
  "Read the next character from the lexer."
  [lexer]
  (if-let [c (deque-pop (:pushback-chars lexer))]
    [c lexer]
    (let [c (.read ^BufferedReader (:instream lexer))]
      [(if (neg? c) nil (char c))
       (if (and c (= c \newline))
         (update-lexer lexer :lineno inc)
         lexer)])))

(defn read-token
  "Read a single token from the input stream."
  [lexer]
  (loop [{:keys [state token posix quotes escape escapedquotes whitespace
                 wordchars punctuation-chars commenters debug] :as lexer} lexer
         quoted false
         escapedstate \space]
    (let [[nextchar lexer] (read-char lexer)]
      (when (>= debug 3)
        (println (str "shlex: in state " (pr-str state) " I see character: " (pr-str nextchar))))
      (cond
        (nil? state)
        [(if (and posix (not quoted) (empty? token)) nil token)
         (update-lexer lexer :token "")]

        (= state \space)
        (cond
          (nil? nextchar)
          (recur (assoc lexer :state nil) quoted escapedstate)

          (whitespace nextchar)
          (do
            (when (>= debug 2)
              (println "shlex: I see whitespace in whitespace state"))
            (if (or (seq token) (and posix quoted))
              [(if (and posix (not quoted) (empty? token)) nil token)
               (update-lexer lexer :token "")]
              (recur lexer quoted escapedstate)))

          (commenters nextchar)
          (let [reader ^BufferedReader (:instream lexer)]
            (.readLine reader)
            (recur (update-lexer lexer :lineno inc) quoted escapedstate))

          (and posix (escape nextchar))
          (recur (assoc lexer :state nextchar) quoted \a)

          (wordchars nextchar)
          (recur (update-lexer lexer :state \a :token (str nextchar)) quoted escapedstate)

          (punctuation-chars nextchar)
          (recur (update-lexer lexer :state \c :token (str nextchar)) quoted escapedstate)

          (quotes nextchar)
          (recur (update-lexer lexer
                               :state nextchar
                               :token (if posix "" (str nextchar)))
                 true
                 escapedstate)

          (:whitespace-split lexer)
          (recur (update-lexer lexer :state \a :token (str nextchar)) quoted escapedstate)

          :else
          (let [new-token (str nextchar)]
            (if (or (seq token) (and posix quoted))
              [(if (and posix (not quoted) (empty? token)) nil new-token)
               (update-lexer lexer :token "")]
              (recur (assoc lexer :token new-token) quoted escapedstate))))

        (quotes state)
        (cond
          (nil? nextchar)
          (throw (IllegalArgumentException. "No closing quotation"))

          (= nextchar state)
          (if posix
            (recur (assoc lexer :state \a) quoted escapedstate)
            [(if (and posix (not quoted) (empty? token)) nil token)
             (update-lexer lexer :state \space :token "")])

          (and posix (escape nextchar) (escapedquotes state))
          (recur (assoc lexer :state nextchar) quoted state)

          :else
          (recur (update-lexer lexer :token (str token nextchar)) quoted escapedstate))

        (escape state)
        (cond
          (nil? nextchar)
          (throw (IllegalArgumentException. "No escaped character"))

          (and (quotes escapedstate) (not= nextchar state) (not= nextchar escapedstate))
          (recur (update-lexer lexer :token (str token state nextchar)) quoted escapedstate)

          :else
          (recur (update-lexer lexer :state escapedstate :token (str token nextchar))
                 quoted
                 escapedstate))

        (#{ \a \c } state)
        (cond
          (nil? nextchar)
          [(if (and posix (not quoted) (empty? token)) nil token)
           (update-lexer lexer :state nil :token "")]

          (whitespace nextchar)
          (do
            (when (>= debug 2)
              (println "shlex: I see whitespace in word state"))
            (if (or (seq token) (and posix quoted))
              [(if (and posix (not quoted) (empty? token)) nil token)
               (update-lexer lexer :state \space :token "")]
              (recur lexer quoted escapedstate)))

          (commenters nextchar)
          (let [reader ^BufferedReader (:instream lexer)]
            (.readLine reader)
            (if posix
              (if (or (seq token) (and posix quoted))
                [(if (and posix (not quoted) (empty? token)) nil token)
                 (update-lexer lexer :state \space :token "" :lineno inc)]
                (recur (update-lexer lexer :lineno inc) quoted escapedstate))
              (recur (update-lexer lexer :lineno inc) quoted escapedstate)))

          (= state \c)
          (if (punctuation-chars nextchar)
            (recur (update-lexer lexer :token (str token nextchar)) quoted escapedstate)
            (let [lexer (if-not (whitespace nextchar)
                          (update-lexer lexer :pushback-chars
                                        (deque-push (:pushback-chars lexer) nextchar))
                          lexer)]
              [(if (and posix (not quoted) (empty? token)) nil token)
               (update-lexer lexer :state \space :token "")]))

          (and posix (quotes nextchar))
          (recur (assoc lexer :state nextchar) true escapedstate)

          (and posix (escape nextchar))
          (recur (assoc lexer :state nextchar) quoted \a)

          (or (wordchars nextchar) (quotes nextchar) (:whitespace-split lexer))
          (recur (update-lexer lexer :token (str token nextchar)) quoted escapedstate)

          :else
          (let [lexer (if (seq punctuation-chars)
                        (update-lexer lexer :pushback-chars
                                      (deque-push (:pushback-chars lexer) nextchar))
                        (update-lexer lexer :pushback
                                      (deque-push (:pushback lexer) (str nextchar))))]
            (when (>= debug 2)
              (println "shlex: I see punctuation in word state"))
            (if (or (seq token) (and posix quoted))
              [(if (and posix (not quoted) (empty? token)) nil token)
               (update-lexer lexer :state \space :token "")]
              (recur lexer quoted escapedstate))))))))

(defn get-token
  "Get the next token from the lexer."
  [lexer]
  (if-let [tok (deque-pop (:pushback lexer))]
    (do
      (when (pos? (:debug lexer))
        (println (str "shlex: popping token " (pr-str tok))))
      [tok lexer])
    (loop [{:keys [source eof filestack] :as lexer} lexer]
      (let [[raw lexer] (read-token lexer)]
        (cond
          (and source (= raw source))
          (let [[newfile newstream] ((:sourcehook lexer identity) (first (read-token lexer)))]
            (if newstream
              (recur (push-source lexer newstream newfile))
              (recur lexer)))

          (= raw eof)
          (if (empty? filestack)
            [eof lexer]
            (recur (pop-source lexer)))

          :else
          (do
            (when (pos? (:debug lexer))
              (println (str "shlex: token=" (if (= raw eof) "EOF" (pr-str raw)))))
            [raw lexer]))))))

;; Source hook
(defn sourcehook
  "Handle source file inclusion."
  [newfile]
  (let [newfile (if (= \" (first newfile))
                  (subs newfile 1 (dec (count newfile)))
                  newfile)]
    [newfile (io/reader newfile)]))

;; Split function
(defn split
  "Split a string into shell-like tokens."
  ([s] (split s {}))
  ([s {:keys [comments posix] :or {comments false posix true}}]
   (let [lexer (make-lexer {:instream s :posix posix})
         lexer (assoc lexer :whitespace-split true)
         lexer (if comments lexer (assoc lexer :commenters #{}))]
     (loop [lexer lexer
            tokens []]
       (let [[token new-lexer] (get-token lexer)]
         (if (= token (:eof lexer))
           tokens
           (recur new-lexer (conj tokens token))))))))

;; Quote function
(defn quote
  "Shell-escape a string."
  [s]
  (if (empty? s)
    "''"
    (if (re-find #"[^\w@%+=:,./-]" s)
      (str "'" (str/replace s "'" "'\"'\"'") "'")
      s)))

;; Token sequence
(defn token-seq
  "Return a lazy sequence of tokens from the lexer."
  [lexer]
  (lazy-seq
    (let [[token new-lexer] (get-token lexer)]
      (when-not (= token (:eof lexer))
        (cons token (token-seq new-lexer))))))

;; Main function for testing
(defn -main
  [& args]
  (if (seq args)
    (with-open [rdr (io/reader (first args))]
      (doseq [token (token-seq (make-lexer {:instream rdr :infile (first args)}))]
        (println (str "Token: " (pr-str token)))))
    (doseq [token (token-seq (make-lexer {}))]
      (println (str "Token: " (pr-str token))))))
