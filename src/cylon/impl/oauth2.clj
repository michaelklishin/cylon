(ns cylon.impl.oauth2
  (:require
   [com.stuartsierra.component :as component]
   [clojure.tools.logging :refer :all]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hiccup.core :refer (html h)]
   [modular.bidi :refer (WebService)]
   [bidi.bidi :refer (path-for)]
   [ring.middleware.params :refer (wrap-params)]
   [org.httpkit.client :refer (request) :rename {request http-request}]
   [cheshire.core :refer (encode decode-stream)]
   [cylon.authorization :refer (Authorizer)]
   [cylon.session :refer (create-session! get-session assoc-session!)]
   [ring.middleware.cookies :refer (wrap-cookies cookies-request cookies-response)]
   [ring.util.codec :refer (url-encode)]
   [schema.core :as s]
   [cylon.user :refer (verify-user)]
   [cylon.session :refer (create-session! assoc-session! ->cookie get-session-value get-cookie-value)]
   [cylon.totp :refer (OneTimePasswordStore get-totp-secret totp-token)]
   [clj-jwt.core :refer (to-str jwt sign str->jwt verify encoded-claims)]
   [clj-time.core :refer (now plus days)]
   ))



(defprotocol Scopes
  (valid-scope? [_ scope]))

(defrecord AuthServer [store scopes iss]
  Scopes
  (valid-scope? [_ scope] (contains? scopes scope))

  WebService
  (request-handlers [this]
    {::get-authenticate-form
     (->
      (fn [req]
        {:status 200
         :body (html
                [:body
                 [:h1 "Azondi MQTT Broker API Server"]
                 [:p "The application with client id " (-> req :query-params (get "client_id"))
                  " is requesting access to the Azondi API on your behalf. Please login if you are happy to authorize this application."]
                 [:form {:method :post
                         :action (path-for (:modular.bidi/routes req) ::post-authenticate-form)}
                  [:p
                   [:label {:for "user"} "User"]
                   [:input {:name "user" :id "user" :type "text" :value "juan"}]]
                  [:p
                   [:label {:for "password"} "Password"]
                   [:input {:name "password" :id "password" :type "password"}]]

                  ;; TODO - Hidden fields - I think we should first
                  ;; redirect to a oauth2 handler which validates the
                  ;; request, if the request is valid, then tries to
                  ;; authenticate the user against an existing session,
                  ;; if no existing session then redirects to a login
                  ;; form such as this. Then we wouldn't need to 'hide'
                  ;; these fields in the form.
                  [:input {:name "client_id" :type "hidden" :value (-> req :query-params (get "client_id"))}]
                  [:input {:name "scope" :type "hidden" :value (-> req :query-params (get "scope"))}]
                  [:input {:name "state" :type "hidden" :value (-> req :query-params (get "state"))}]

                  [:p [:input {:type "submit"}]]
                  [:p [:a {:href (path-for (:modular.bidi/routes req) :cylon.impl.signup/signup-form)} "Signup"]]]])})
      wrap-params)

     ::post-authenticate-form
     (-> (fn [req]
           (let [params (-> req :form-params)
                 identity (get params "user")
                 password (get params "password")
                 client-id (get params "client_id")
                 scope (get params "scope")
                 state (get params "state")
                 scopes (set (str/split scope #"[\s]+"))]

             ;; openid-connect core 3.1.2.2
             ;;(if (contains? scopes "openid"))

             (if (and identity
                      (not-empty identity)
                      (verify-user (:user-domain this) (.trim identity) password))

               (let [session (create-session! (:session-store this) {:cylon/identity identity})]
                 (if (and ; we want one clause here, so we only have one then and one else clause to code
                      (satisfies? OneTimePasswordStore (:user-domain this))
                      (when-let [secret (get-totp-secret (:user-domain this) identity password)]
                        (assoc-session! (:session-store this)
                                        (:cylon.session/key session)
                                        :totp-secret secret)
                        true ; it does, but just in case assoc-session! semantics change
                        ))

                   ;; So it's 2FA
                   (do
                     ;; Let's remember the client-id and state in the
                     ;; session, we'll need them later
                     (assoc-session! (:session-store this)
                                     (:cylon.session/key session)
                                     :client-id client-id)

                     (assoc-session! (:session-store this)
                                     (:cylon.session/key session)
                                     :state state)

                     {:status 302
                      :headers {"Location" (path-for (:modular.bidi/routes req) ::get-totp-code)}
                      :cookies {"session-id" (->cookie session)}})

                   ;; So it's not 2FA, continue with OAuth exchange
                   ;; Generate the temporary code that we'll exchange for an access token later
                   (let [code (str (java.util.UUID/randomUUID))]

                     ;; Remember the code for the possible exchange - TODO expiry these
                     (swap! store assoc
                            {:client-id client-id :code code}
                            {:created (java.util.Date.)
                             :cylon/identity identity})

                     {:status 302
                      :headers {"Location"
                                (format
                                 "http://localhost:8010/oauth/grant?code=%s&state=%s"
                                 code state
                                 )}
                      :cookies {"session-id" (->cookie session)}})))
               ;; Fail
               {:status 302
                :headers {"Location" (str "/login/oauth/authorize?client_id=" client-id)}
                :body "Try again"}
               )

             )
           )
         wrap-params wrap-cookies)

     ::get-totp-code
     (fn [req]
       {:status 200
        :body (html
               [:h1 "Please can I have your auth code"]

               (let [secret (get-session-value req "session-id" (:session-store this) :totp-secret)]
                 [:p "Secret is " secret]
                 [:p "Hint, Type this: " (totp-token secret)])

               [:form {:method :post
                       :action (path-for (:modular.bidi/routes req)
                                         ::post-totp-code)}
                [:input {:type "text" :id "code" :name "code"}]])})

     ::post-totp-code
     (-> (fn [req]
           (let [code (-> req :form-params (get "code"))
                 secret (get-session-value req "session-id" (:session-store this) :totp-secret)]
             (if (= code (totp-token secret))
               ;; Success, set up the exchange
               (let [session (get-session (:session-store this) (get-cookie-value req "session-id"))
                     client-id (get session :client-id)
                     state (get session :state)
                     identity (get session :cylon/identity)

                     code (str (java.util.UUID/randomUUID))]

                 ;; Remember the code for the possible exchange - TODO expire these
                 (swap! store assoc
                        {:client-id client-id :code code}
                        {:created (java.util.Date.)
                         :cylon/identity identity})

                 {:status 302
                  :headers {"Location"
                            (format
                             "http://localhost:8010/oauth/grant?code=%s&state=%s"
                             code state
                             )}})

               ;; Failed, have another go!
               {:status 302
                :headers {"Location"
                          (path-for (:modular.bidi/routes req) ::get-totp-code)}
                }

               )))
         wrap-params wrap-cookies)

     ::exchange-code-for-access-token
     (-> (fn [req]
           (let [params (:form-params req)
                 code (get params "code")
                 client-id (get params "client_id")]
             (if-let [{identity :cylon/identity}
                      (get @store
                           ;; I don't think this key has to include client-id
                           ;; - it can just be 'code'.
                           {:client-id client-id :code code})]

               (let [{access-token :cylon.session/key}
                     (create-session! (:access-token-store this) {:scopes #{:superuser/read-users :repo :superuser/gist :admin}})
                     claim {:iss iss
                            :sub identity
                            :aud client-id
                            :exp (plus (now) (days 1)) ; expiry
                            :iat (now)}]

                 (info "Claim is %s" claim)

                 {:status 200
                  :body (encode {"access_token" access-token
                                 "scope" "repo gist openid profile email"
                                 "token_type" "Bearer"
                                 "expires_in" 3600
                                 ;; TODO Refresh token (optional)
                                 ;; ...
                                 ;; OpenID Connect ID Token
                                 "id_token" (-> claim
                                                jwt
                                                (sign :HS256 "secret") to-str)
                                 })})
               {:status 400
                :body "You did not supply a valid code!"})))
         wrap-params)})

  (routes [this]
    ["/" {"authorize" {:get ::get-authenticate-form
                       :post ::post-authenticate-form}
          "totp" {:get ::get-totp-code
                  :post ::post-totp-code}
          "access_token" {:post ::exchange-code-for-access-token}}])

  (uri-context [this] "/login/oauth"))

(defn new-auth-server [& {:as opts}]
  (component/using
   (->> opts
        (merge {:store (atom {})})
        (s/validate {:scopes {s/Keyword {:description s/Str}}
                     :store s/Any
                     :iss s/Str ; uri actually, see openid-connect ch 2.
                     })
        map->AuthServer)
   [:access-token-store
    :session-store
    :user-domain]))

;; --------

(defprotocol TempState
  (expect-state [_ state])
  (expecting-state? [this state]))

(defrecord Application [client-id secret store]
  WebService
  (request-handlers [this]
    {::grant
     (->
      (fn [req]
        (let [params (:query-params req)
              state (get params "state")]

          (if (not (expecting-state? this state))
            {:status 400 :body "Unexpected user state"}

            ;; otherwise
            (let [code (get params "code")

                  ;; Exchange the code for an access token
                  at-resp
                  @(http-request
                    {:method :post
                     :url "http://localhost:8020/login/oauth/access_token"
                     :headers {"content-type" "application/x-www-form-urlencoded"}
                     ;; Exchange the code for an access token - application/x-www-form-urlencoded format

                     ;; TODO: From reading OAuth2 4.1.2 I
                     ;; don't think we should use client_id -
                     ;; that looks to be a github thing.

                     :body (format "client_id=%s&client_secret=%s&code=%s"
                                   client-id secret code)}
                    #(if (:error %)
                       %
                       (update-in % [:body] (comp decode-stream io/reader))))]

              (if-let [error (:error at-resp)]
                {:status 403
                 :body (format "Something went wrong: status of underlying request, error was %s"
                               error)
                 }
                (if (not= (:status at-resp) 200)
                  {:status 403
                   :body (format "Something went wrong: status of underlying request %s" (:status at-resp))}


                  (let [app-session-id (-> req cookies-request :cookies (get "app-session-id") :value)
                        original-uri (:original-uri (get-session (:session-store this) app-session-id))
                        access-token (get (:body at-resp) "access_token")
                        id-token (-> (get (:body at-resp) "id_token") str->jwt)
                        ]
                    (if (verify id-token "secret")
                      (do
                        (infof "Verified id_token: %s" id-token)
                        (assert original-uri (str "Failed to get original-uri from session " app-session-id))
                        (assoc-session! (:session-store this) app-session-id :access-token access-token)
                        (infof "Claims are %s" (:claims id-token))
                        (assoc-session! (:session-store this) app-session-id :cylon/identity (-> id-token :claims :sub))

                        {:status 302
                         :headers {"Location" original-uri}
                         :body (str "Logged in, and we got an access token: " (:body at-resp))

                         })
                      ;; Error response - id_token failed verification
                      ))))))))
      wrap-params wrap-cookies)})
  (routes [this] ["/grant" {:get ::grant}])
  (uri-context [this] "/oauth")

  Authorizer
  ;; Return the access-token, if you can!
  (authorized? [this req scope]
    (let [app-session-id (-> req cookies-request :cookies (get "app-session-id") :value)]
      (select-keys (get-session (:session-store this) app-session-id) [:access-token :cylon/identity])))

  ;; TODO Deprecate this!
  TempState
  (expect-state [this state]
    (swap! store update-in [:expected-states] conj state))
  (expecting-state? [this state]
    (if (contains? (:expected-states @store) state)
      (do
        (swap! store update-in [:expected-states] disj state)
        true))))

(defn new-application
  "Represents an OAuth2 application. This component provides all the web
  routes necessary to provide signup, login and password resets. It also
  acts as an Authorizer, which returns an OAuth2 access token from a
  call to authorized?"
  [& {:as opts}]
  (component/using
   (->> opts
        (merge {:store (atom {:expected-states #{}})
                :secret "sekfuhalskuehfalk"})
        (s/validate {:client-id s/Str
                     :secret s/Str
                     :store s/Any
                     :required-scopes #{s/Keyword}
                     })
        map->Application)
   [:session-store]))

(defn authorize [app req]
  (let [original-uri (apply format "%s://%s%s" ((juxt (comp name :scheme) (comp #(get % "host") :headers) :uri) req))
        ;; We need a session to store the original uri
        session (create-session!
                 (:session-store app)
                 {:original-uri original-uri})
        state (str (java.util.UUID/randomUUID))
        ]
    (expect-state app state)
    (cookies-response
     {:status 302
      :headers {"Location"
                (format "http://localhost:8020/login/oauth/authorize?client_id=%s&state=%s&scope=%s"
                        (:client-id app)
                        state
                        (url-encode "openid profile email")
                        )}
      :cookies {"app-session-id"
                {:value (:cylon.session/key session)
                 :expires (.toGMTString
                           (doto (new java.util.Date)
                             (.setTime (:cylon.session/expiry session))
                             ))}}})))



(defrecord OAuth2AccessTokenAuthorizer []
  Authorizer
  (authorized? [this request scope]
    (if (valid-scope? (:auth-server this) scope)

      (let [access-token (second (re-matches #"\Qtoken\E\s+(.*)" (get (:headers request) "authorization")))
            session (get-session (:access-token-store this) access-token)
            scopes (:scopes session)]

        (infof "session is %s, scopes is %s" session scopes)

        (when scopes
          (scopes scope)))

      ;; Not a valid scope

      (throw (ex-info "Not a valid scope!" {:scope scope}))

      )))

(defn new-oauth2-access-token-authorizer [& {:as opts}]
  (component/using (->OAuth2AccessTokenAuthorizer) [:access-token-store :auth-server])
)
