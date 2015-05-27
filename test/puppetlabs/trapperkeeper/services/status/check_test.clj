(ns puppetlabs.trapperkeeper.services.status.check-test
  (:require [clojure.java.jdbc :as sql]
            [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.services.status.check :refer :all]))

(deftest db-up?-test
  (testing "db-up?"
    (testing "connects to a database, runs an arithmetic query, and checks the result"
      (let [!query (atom nil)
            !db (atom nil)
            db-spec {:conninfo "postgresql://user:pass@localhost:5432/db"}]
        (with-redefs [sql/query (fn [db sql-vec]
                                  (reset! !db db)
                                  (reset! !query sql-vec)
                                  [{:answer 42}])]
          (is (db-up? db-spec))
          (is (= db-spec @!db))
          (is (= ["SELECT (a - b) AS answer FROM (VALUES ((7 * 7), 7)) AS x(a, b)"]
                 @!query)))))

    (testing "returns false if the query function throws an exception"
      (with-redefs [sql/query (fn [_ _] (throw (Exception. "what DB?")))]
        (is (false? (db-up? nil)))))

    (testing "returns false if the arithmetic doesn't check out"
      (with-redefs [sql/query (fn [_ _] [{:answer 49}])]
        (is (false? (db-up? nil)))))))

(deftest disk-writable?-test
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
          (is (disk-writable?))
          (is (not (.exists @!file))))))

    (testing "returns false if what was read isn't what was written"
      (with-redefs [spit (constantly nil)
                    slurp (constantly "foo")]
        (is (not (disk-writable?)))))

    (testing "returns false if an exception was thrown by spit"
      (let [!file (atom nil)]
        (with-redefs [spit (fn [file _]
                             (reset! !file file)
                             (throw (Exception. "this isn't real")))]
          (is (false? (disk-writable?))))

        (testing "but still deletes the file"
          (is (not (.exists @!file))))))))
