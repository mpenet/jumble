(ns s-exp.jumble.account.email
  "Plain text email carrying a sign-in link."
  (:require [ring.util.codec :as codec]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.email.mail :as mail]))

(set! *warn-on-reflection* true)

(defn sign-in-link
  "Email to `to` with the link signing in with `token`, valid `minutes`;
  carrying the join link's `join-token` when joining by it, to get a new
  link from if this one is spent."
  ([settings to token minutes] (sign-in-link settings to token minutes nil))
  ([{:keys [base-url app-name]} to token minutes join-token]
   (let [app-name (mail/one-line app-name)]
     {:to to
      :subject (i18n/t "Sign in to {app-name}" {:app-name app-name})
      :text (str (i18n/t "Use this link to sign in to {app-name}:" {:app-name app-name})
                 "\n\n"
                 base-url
                 "/login/email?"
                 (codec/form-encode (cond-> {"token" token} join-token (assoc "join" join-token)))
                 "\n\n"
                 (i18n/tn "It works once, within {n} minute. If you did not ask for it, you can\nignore this email: nobody can sign in without the link."
                          "It works once, within {n} minutes. If you did not ask for it, you can\nignore this email: nobody can sign in without the link."
                          minutes)
                 "\n")})))

(defn invitation
  "Email inviting `to` to sign in, from `inviter`: the moderator who added
  the address, or the member who invited it."
  [{:keys [base-url app-name]} inviter to]
  (let [app-name (mail/one-line app-name)]
    {:to to
     :subject (i18n/t "You are invited to {app-name}" {:app-name app-name})
     :text (str (i18n/t "{inviter} invited you to {app-name}, where members sell, give away and lend things to each other."
                        {:inviter (mail/one-line inviter) :app-name app-name})
                "\n\n"
                (i18n/t "Sign in with this address: {url}" {:url (str base-url "/login")})
                "\n\n"
                (i18n/t "If you do not know {inviter}, you can ignore this email." {:inviter (mail/one-line inviter)})
                "\n")}))
