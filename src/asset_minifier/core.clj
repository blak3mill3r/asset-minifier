(ns asset-minifier.core
  (:require [clojure.java.io :refer [file reader writer]])
  (:import com.yahoo.platform.yui.compressor.CssCompressor
           java.util.zip.GZIPOutputStream
           java.io.FileInputStream
           java.io.FileOutputStream
           java.io.File
           org.apache.commons.io.IOUtils
           java.util.logging.Level
           [com.google.javascript.jscomp
            CompilationLevel
            CompilerOptions
            JSSourceFile]))

(defn- delete-target [target]
  (let [f (file target)]
    (when (.exists f)
      (.delete (file target)))))

(defn- find-assets [f ext]
  (if (.isDirectory f)
    (->> f
         file-seq
         (filter (fn [file] (-> file .getName (.endsWith ext)))))
    [f]))

(defn- aggregate [path ext]
  (if (coll? path)
   (flatten
     (for [item path]
      (let [f (file item)]
        (find-assets f ext))))
    (let [f (file path)]
      (find-assets f ext))))

(defn- compression-details [sources target]
  (let [tmp (File/createTempFile (.getName target) ".gz")]
    (with-open [in  (FileInputStream. target)
                out (FileOutputStream. tmp)
                outGZIP (GZIPOutputStream. out)]
      (IOUtils/copy in outGZIP)
      {:original-szie (str "Uncompressed size: " (->> sources (map #(.length %)) (apply +)) " bytes")
       :compressed-size (str "Compressed size: "(.length target) " bytes minified (" (.length tmp) " bytes gzipped)")})))

(defn- minify-css-file [source target {:keys [linebreak] :or {linebreak -1}}]
  (with-open [rdr (reader source)
              wrt (writer target)]
    (-> rdr (CssCompressor.) (.compress wrt linebreak))))

(defn minify-css [path target & [opts]]
  (delete-target target)
  (let [assets (aggregate path ".css")
        tmp    (File/createTempFile "temp-sources" ".css")
        target (file target)]
   (with-open [wrt (writer tmp :append true)]
     (doseq [file assets]
       (.append wrt (slurp file))))
    (minify-css-file tmp target opts)
    (compression-details assets target)))

(defn- set-optimization [options optimization]
  (-> optimization
      {:advanced CompilationLevel/ADVANCED_OPTIMIZATIONS
       :simple CompilationLevel/SIMPLE_OPTIMIZATIONS
       :whitespace CompilationLevel/WHITESPACE_ONLY}
      (.setOptionsForCompilationLevel options))
  options)

(defn- compile-js [compiler assets externs optimization]
  (let [options  (-> (CompilerOptions.)
                     (doto (.setOutputCharset "UTF-8"))
                     (set-optimization optimization))]

    (.compile compiler
      (map #(JSSourceFile/fromFile %) externs)
      (map #(JSSourceFile/fromFile %) assets)
      options)
    {:warnings (map #(.toString %) (.getWarnings compiler))
     :errors   (map #(.toString %) (.getErrors compiler))}))

(defn minify-js [path target & [{:keys [quiet? externs optimization]
                                 :or {quiet? false
                                      externs []
                                      optimization :simple}}]]
  (if quiet?
    (com.google.javascript.jscomp.Compiler/setLoggingLevel Level/OFF)
    (com.google.javascript.jscomp.Compiler/setLoggingLevel Level/SEVERE))
  (delete-target target)
  (let [assets   (aggregate path ".js")
        compiler (com.google.javascript.jscomp.Compiler.)
        result   (compile-js compiler assets externs optimization)]

    (with-open [wrt (writer target)]
      (.append wrt (.toSource compiler)))
    (merge result (compression-details assets (file target)))))