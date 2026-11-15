(ns s-exp.jumble.page.theme
  "Light or dark appearance chosen by the user, kept in a cookie and rendered
  by the server, so pages never flash the other theme. Without a choice the
  system preference applies."
  (:require [s-exp.jumble.web.auth :as auth]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.view.icons :as icons]
            [s-exp.jumble.web.params :as params]
            [s-exp.jumble.web.response :as response]))

(set! *warn-on-reflection* true)

(def ^:private themes #{"light" "dark"})
(def ^:private cookie "jumble_theme")
(def ^:private one-year (* 365 24 60 60))

(defn chosen
  "The theme chosen with this request's cookies, or nil."
  [{:keys [cookies ctx]}]
  (themes (get-in cookies [(auth/cookie-name (get-in ctx [:settings :base-url] "") cookie) :value])))

(def ^:private switch
  "Switches at once, then remembers. The current theme is the chosen one, or
  the system's when none was chosen."
  (str "const root = document.documentElement; "
       "const dark = root.dataset.theme ? root.dataset.theme === 'dark' "
       ": matchMedia('(prefers-color-scheme: dark)').matches; "
       "const next = dark ? 'light' : 'dark'; "
       "root.dataset.theme = next; el.elements.theme.value = next; "
       "@post(el.action, {contentType: 'form'})"))

(defn toggle
  "Header form switching theme. Without JS it posts the other theme than the
  chosen one (dark when none was) and comes back to the current page."
  [request]
  [:form.theme-toggle {:method "post" :action "/theme" :data-on:submit switch}
   [:input {:type "hidden" :name "theme" :value (if (= "dark" (chosen request)) "light" "dark")}]
   [:input {:type "hidden" :name "return_to" :value (auth/request-path request)}]
   [:button.icon-button {:type "submit" :aria-label (i18n/t "Switch between light and dark")
                         :title (i18n/t "Switch between light and dark")}
    icons/moon icons/sun]])

(defn choose
  [{:keys [ctx params] :as request}]
  (let [base-url (get-in ctx [:settings :base-url])
        theme (themes (params/one params "theme"))
        response (if (response/datastar? request)
                   {:status 204 :body ""}
                   (response/redirect (auth/local-path (params/one params "return_to"))))]
    (cond-> response
      theme (assoc :cookies {(auth/cookie-name base-url cookie) (auth/cookie base-url theme one-year)}))))

(def routes
  {[:post "/theme"] #'choose})
