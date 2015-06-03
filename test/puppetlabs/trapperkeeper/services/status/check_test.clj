(ns puppetlabs.trapperkeeper.services.status.check-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.services.status.check :refer :all]))

(deftest disk-writable?-test
  (let [temp-dir (System/getProperty "java.io.tmpdir")]
    (testing "disk-writable?"
      (testing "writes to a temp file, checks what was written, and deletes the file."
        (let [!file (atom nil)
              !content (atom nil)]
          (with-redefs [spit (fn [file content]
                               (reset! !file file)
                               (reset! !content content)
                               nil)
                        slurp (fn [file]
                                (is (= @!file file))
                                @!content)]
            (is (disk-writable? temp-dir))
            (is (not (.exists @!file))))))

      (testing "returns false if what was read isn't what was written"
        (with-redefs [spit (constantly nil)
                      slurp (constantly "foo")]
          (is (not (disk-writable? temp-dir)))))

      (testing "returns false if an exception was thrown by spit"
        (let [!file (atom nil)]
          (with-redefs [spit (fn [file _]
                               (reset! !file file)
                               (throw (Exception. "this isn't real")))]
            (is (false? (disk-writable? temp-dir))))

          (testing "but still deletes the file"
            (is (not (.exists @!file)))))))))
