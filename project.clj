(defproject galdolber/hildebrand "0.4.4-2"
  :description "High-level, asynchronous AWS client library"
  :url "https://github.com/nervous-systems/hildebrand"
  :license {:name "Unlicense" :url "http://unlicense.org/UNLICENSE"}
  ;;:scm {:name "git" :url "https://github.com/nervous-systems/hildebrand"}
  ;;:deploy-repositories [["clojars" {:creds :gpg}]]
  ;;:signing {:gpg-key "moe@nervous.io"}
  :global-vars {*warn-on-reflection* true
                *print-meta* true}
  :source-paths ["src"]
  :plugins [[lein-cljsbuild "1.1.4"]
            [lein-npm "0.6.2"]]
  :dependencies
  [[org.clojure/clojure "1.9.0-alpha11"]
   ;;[org.clojure/clojurescript "1.9.229"]
   [io.nervous/eulalie "0.6.10"]]
  :exclusions [org.clojure/clojure]
  :cljsbuild
  {:builds [{:id "main"
             :source-paths ["src"]
             :compiler {:output-to "hildebrand.js"
                        :target :nodejs
                        :hashbang false
                        :optimizations :none
                        :source-map true}}
            {:id "test-none"
             :source-paths ["src" "test"]
             :compiler {:output-to "target/test-none/hildebrand-test.js"
                        :output-dir "target/test-none"
                        :target :nodejs
                        :optimizations :none
                        :main "hildebrand.test.runner"}}
            {:id "test-advanced"
             :source-paths ["src" "test"]
             :notify-command ["node" "target/test-advanced/hildebrand-test.js"]
             :compiler {:output-to "target/test-advanced/hildebrand-test.js"
                        :output-dir "target/test-advanced"
                        :target :nodejs
                        :optimizations :advanced}}]}
  :profiles {:dev {:source-paths ["src" "test"]}})
