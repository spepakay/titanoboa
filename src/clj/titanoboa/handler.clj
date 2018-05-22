(ns titanoboa.handler
  (:require [compojure.core :refer [GET POST PATCH PUT defroutes routes context]]
            [compojure.route :as route :refer [not-found resources]]
            [compojure.coercions :refer [as-int]]
            [clj-http.client :as client :refer [request]]
            [clj-http.util :as http-util]
            [ring.middleware.format :refer [wrap-restful-format]]
            [ring.middleware.format-params :refer [wrap-restful-params]]
            [ring.middleware.format-response :refer [wrap-restful-response]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.params :refer [wrap-params]]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [include-js include-css]]
            [prone.middleware :refer [wrap-exceptions]]
            [titanoboa.util :as util]
            [titanoboa.repo :as repo :refer [keyify]]
            [titanoboa.exp :as exp]
            [titanoboa.processor :as processor]
            [titanoboa.system :as system]
            [titanoboa.api :as api]
            [titanoboa.channel :as channel]
            [titanoboa.dependencies :as deps]
            [cognitect.transit :as transit]
            [clojure.tools.logging :as log]
            [titanoboa.auth :as auth]
            [titanoboa.database :as db]
            [ring.util.response :as resp])
  (:import [java.net URI]
           [com.mchange.v2.c3p0 ComboPooledDataSource]))

#_(def broadcast (channel/map->SystemStateBroadcast {:session (:session session)
                                           :exchange-name "heartbeat"
                                           :node-id (.getHostAddress (java.net.InetAddress/getLocalHost))
                                           :state-fn (fn [] {:systems (merge-with merge (into {} (system/live-systems)) systems-catalogue)
                                                             :jobs (api/get-jobs-states)})
                                           :broadcast-interval 5000
                                           :msg-exipre "5000"}))


#_(resolve  (:a (clojure.edn/read-string "{:a titanoboa.system.rabbitmq/distributed-core-system}")))
#_((resolve  (clojure.edn/read-string "titanoboa.system.rabbitmq/distributed-core-system")) {})
;; /cluster/nodes/127.0.1.1:3000/systems/:core
;;TODO consider instead of passing config as tehis fnction's parameter injecting necessary config directly into request via additional ring middleware?
(defn get-secured-routes [{:keys [steps-repo-path jobs-repo-path systems-catalogue archive-ds-ks node-id] :as config}]
  (routes
    (GET "/user" req {:body (:auth-user req)})
    (POST "/user/logout" req {:status 401 :session {}})
    (context "/repo/stepdefinitions" [] ;;TODO following should be loaded from atom which would be asynchronously updated from file system on change
      (GET "/" [] {:body (repo/get-all-head-defs steps-repo-path)})
      (GET "/heads" [] {:body (into {} (repo/list-head-defs steps-repo-path))})
      (GET "/revisions" [] {:body (into {} (repo/list-all-revisions steps-repo-path))})
      (GET "/:def-name" [def-name] {:body (repo/get-head-def steps-repo-path def-name)})
      (GET "/:def-name/:revision" [def-name revision] {:body (repo/get-def-rev steps-repo-path def-name (Integer. revision))})
      (POST "/:def-name" [step-type definition] {:body (repo/save! (assoc definition :type (util/tokey step-type)) steps-repo-path :type)}))
    (context "/repo/jobdefinitions" [] ;;TODO following should be loaded from atom which would be asynchronously updated from file system on change
      (GET "/" [] {:body (keyify :name (repo/get-all-head-defs jobs-repo-path))})
      (GET "/heads" [] {:body (into {} (repo/list-head-defs jobs-repo-path))})
      (GET "/revisions" [] {:body (into {} (repo/list-all-revisions jobs-repo-path))})
      (GET "/:def-name" [def-name] {:body (repo/get-head-def jobs-repo-path def-name)})
      (GET "/:def-name/:revision" [def-name revision] {:body (repo/get-def-rev jobs-repo-path def-name (Integer. revision))})
      (POST "/:def-name" [def-name definition] {:body (repo/save! (assoc definition :name def-name) jobs-repo-path)})
      (POST "/:def-name/repl" [def-name snippet] {:body {:result (exp/eval-snippet snippet (symbol def-name))}}))
    (context "/systems" []
      (GET "/" [] {:body (merge-with merge (into {} (system/live-systems)) systems-catalogue)})
      (GET "/live" [] {:body (system/live-systems)})
      (GET "/catalogue" [] {:body systems-catalogue})
      (GET "/jobs" [] {:body (api/get-jobs-states)}))
    (context "/systems/:system" [system]
      (PATCH "/" [action wcount scope]
        {:body
         (case action
           :stop (do (system/stop-system! (util/tokey (http-util/url-decode system))) {:status 200})
           ;;:start (system/start-system! (util/tokey system) systems-catalogue config)
           :start (do (system/start-system-bundle! (util/tokey (http-util/url-decode system)) systems-catalogue config (int (or wcount 0)) scope) {:status 200})
           :restart {:status 200 :body (system/restart-system! (util/tokey (http-util/url-decode system)) systems-catalogue config)})})
      (POST "/workers" [] {:body (system/start-workers! (util/tokey (http-util/url-decode system)) systems-catalogue 1)});; TODO add PATCH to stop/start workers?
      (POST "/jobs" [conf] (do (log/debug "Recieved request to start a job on system [" (http-util/url-decode system) "] with config ["conf"]")
                               {:status 201 :body {:jobid (processor/start-job! (util/tokey (http-util/url-decode system)) conf)}})))
    (context "/cluster" []
      (GET "/" []  {:status 404})
      (GET "/id" [] {:status 404})
      (GET "/jobs" [] {:body (api/get-jobs-states)})
      (GET "/dependencies" [] (if (deps/get-deps-path-property)
                                {:status 200 :body {:dependencies (deps/get-deps-file-content)}}
                                {:status 404 :body {:result "dependencies path was not set"}}))
      (PATCH "/dependencies" [old-content new-content] (if (deps/write-deps-file! old-content new-content)
                                                         {:status 200 :body {:result :ok}}
                                                         {:status 409 :body {:result :stale}})))
    (context "/cluster/nodes" []
      (GET "/" [] {:body
                     {node-id
                       {:systems (merge-with merge (into {} (system/live-systems)) systems-catalogue) :last-hearbeat-age 0 :source true :state :live}}}))
    (context "/archive" []
      (GET "/jobs" [limit :<< as-int offset :<< as-int order] (do (log/debug "Recieved request to list jobs, limit is ["limit"]")
                                                                  (if archive-ds-ks
                                                                    {:body (db/list-jobs (get-in @system/systems-state archive-ds-ks) (or limit 50) (or offset 0) order)}
                                                                    {:status 404 :body {}})))
      (GET "/jobs/:jobid" [jobid] (if archive-ds-ks  {:body (db/get-job (get-in @system/systems-state archive-ds-ks) jobid)}
                                                     {:status 404 :body {}})))))

(defn get-public-routes [{:keys [auth-ds-ks auth-conf auth?] :as config}]
  (routes
    (route/resources "/")
    (GET "/" [] (resp/content-type (resp/resource-response "index.html" {:root "public"}) "text/html"))
    (POST "/create-auth-token" [name password] (if (and auth? auth-ds-ks auth-conf)
                                                 (let [[ok? res] (auth/create-auth-token (get-in @system/systems-state auth-ds-ks)
                                                                                              auth-conf name password)]
                                                        (if ok?
                                                          {:status 201 :body (assoc res :name name) :session {:token (:token res)}}
                                                          {:status 401 :body res}))
                                                 {:status 404 :body {}}))))

(def *req (atom nil))

(defn simple-logging-middleware [handler]
  (fn [request]
    (log/debug "Retrieved Http request:" request " with following params: " (:params request))
    #_(reset! *req request);;FIXME comment out this line for production use!
    ;;    (log/info (:params request))
    (handler request)))


(defn get-app-routes [{:keys [auth?] :as config}]
  (if auth?
    (routes
      (-> (get-public-routes config)
          simple-logging-middleware
          auth/wrap-auth-token
          simple-logging-middleware)
      (-> (get-secured-routes config)
          auth/wrap-authentication
          simple-logging-middleware
          auth/wrap-auth-token))
    (routes
      (-> (get-public-routes config)
          simple-logging-middleware)
      (-> (get-secured-routes config)
          simple-logging-middleware))))

(defn prepare-cookies
  "Removes the domain and secure keys from cookies map.
  Also converts the expires date to a string in the ring response map."
  [resp]
  (let [prepare #(-> (update-in % [1 :expires] str)
                     (update-in [1] dissoc :domain :secure))]
    (assoc resp :cookies (into {} (map prepare (:cookies resp))))))

(defn slurp-binary
  "Reads len bytes from InputStream is and returns a byte array."
  [^java.io.InputStream is len]
  (with-open [rdr is]
    (let [buf (byte-array len)]
      (.read rdr buf)
      buf)))


;;TODO handle java.lang.Object
(defn get-ring-app [config]
  (-> (get-app-routes config)
      ;;simple-logging-middleware
      (wrap-restful-format {:formats [:transit-json :transit-msgpack :edn :json-kw]
                            :response-options {:transit-json
                                                {:handlers {titanoboa.exp.Expression exp/transit-write-handler
                                                            clojure.lang.Var (transit/write-handler (constantly "s") #(str %))
                                                            titanoboa.exp.SerializedVar (transit/write-handler (constantly "s") #(:symbol %))
                                                            java.util.GregorianCalendar (transit/write-handler (constantly "m") #(.getTimeInMillis %) #(str (.getTimeInMillis %)))
                                                            java.io.File (transit/write-handler (constantly "s") #(.getCanonicalPath %))
                                                            java.lang.Exception (transit/write-handler (constantly "s") #(str %))
                                                            clojure.lang.Fn (transit/write-handler (constantly "s") #(str %))
                                                            clojure.lang.Atom (transit/write-handler (constantly "s") #(str %))
                                                            clojure.core.async.impl.channels.ManyToManyChannel (transit/write-handler (constantly "s") #(str %))}}}
                            :params-options {:transit-json
                                                {:handlers {"titanoboa.exp.Expression" exp/transit-read-handler}}}})
      wrap-params
      (auth/wrap-auth-cookie "SoSecret12345678")))

#_(def app
  (let [handler (wrap-defaults #'routes site-defaults)]
    (if (env :dev) (-> handler wrap-exceptions wrap-reload) handler)))
