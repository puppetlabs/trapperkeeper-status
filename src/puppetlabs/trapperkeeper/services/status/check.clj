(ns puppetlabs.trapperkeeper.services.status.check
  "Shared status check functions."
  (:import [java.io File IOException]))

(defn disk-writable?
  "Given a directory, check whether the FS that the directory is in seems
  healthy by writing 4k random letters to a file in that directory, and then
  read them back in. If what was read matches what was written, then return
  true; if there's a mismatch or anything goes wrong in the process, return
  false.

  Since this check involves writing to disk, it may not be appropriate to put at
  a high status check level like :critical or :info."
  [directory]
  (let [rand-letter #(char (rand-nth (range 97 123)))
        fname (str "tk-disk-check-" (apply str (repeatedly 10 rand-letter)) ".txt")]
    (if-let [file (File. directory fname)]
      (let [payload (apply str (repeatedly 4095 rand-letter))]
        (try
          (when (.exists file)
            (.delete file))
          (spit file payload)
          (= (slurp file) payload)
          (catch Exception _
            false)
          (finally
            (try (.delete file)
              (catch IOException _
                false)))))
      false)))
