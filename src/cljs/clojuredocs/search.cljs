(ns clojuredocs.search
  (:require [om.core :as om :include-macros true]
            [dommy.core :as dommy]
            [cljs.core.async :as async
             :refer [<! >! chan close! sliding-buffer put! alts! timeout pipe mult tap]]
            [clojuredocs.ajax :refer [ajax]]
            [clojuredocs.anim :as anim]
            [clojure.string :as str]
            [cljs.reader :as reader]
            [sablono.core :as sab :refer-macros [html]]
            [clojuredocs.util :as util])
  (:require-macros [dommy.macros :refer [node sel1]]))

(defn ellipsis [n s]
  (if (> (- (count s) 3) n)
    (str (->> s
              (take (- n 3))
              (apply str))
         "...")
    s))

(defn handle-search-active-state [owner]
  (if (empty? (om/get-state owner :text))
    (dommy/remove-class! (sel1 :body) :search-active)
    (dommy/add-class! (sel1 :body) :search-active)))

;; Landing page autocomplete

(defn $ac-see-alsos [see-alsos]
  (when-not (empty? see-alsos)
    (let [limit 5
          num-left (- (count see-alsos) limit)
          see-alsos (take limit see-alsos)]
      [:div.see-alsos
       [:span.see-also-label "see also:"]
       [:ul
        (for [{:keys [ns name href] :as sa} see-alsos]
          [:li
           [:a {:href href :class "var-link"}
            [:span.namespace ns]
            "/"
            [:span.name name]]])]
       (when (> num-left 0)
         [:span.remaining-label
          (str "+ " num-left " more")])])))

(defn $ac-entry-var [{:keys [href name ns doc see-alsos type examples-count]}]
  [:div.ac-entry
   [:span.ac-type type " / " examples-count " ex"  #_[:br] #_(util/pluralize examples-count "Example" "Examples")]
   [:h4
    [:a {:href href} name " (" ns ")"]]
   [:p (ellipsis 100 doc)]
   ($ac-see-alsos see-alsos)])

(defn $ac-entry-ns [{:keys [href name ns doc desc see-alsos type]}]
  [:div.ac-entry
   [:span.ac-type type]
   [:h4
    [:a {:href href} name]]
   [:p (ellipsis 100 (or doc desc))]
   ($ac-see-alsos see-alsos)])

(defn $ac-entry-page [{:keys [href name desc type href]}]
  [:div.ac-entry
   [:span.ac-type type]
   [:h4
    [:a {:href href} name]]
   [:p (ellipsis 100 desc)]])

(defn $ac-entry [{:keys [type] :as ac-entry}]
  (cond
    (get #{"function" "macro" "var" "special-form"} type)
    ($ac-entry-var ac-entry)

    (= "namespace" type) ($ac-entry-ns ac-entry)

    (= "page" type) ($ac-entry-page ac-entry)
    :else (.log js/console (str "Couldn't render ac entry:" type))))

(defn put-text [e text-chan owner]
  (let [text (.. e -target -value)]
    (put! text-chan text)
    (om/set-state! owner :loading? true)
    (om/set-state! owner :text text)))

(defn search-submit [{:keys [href]}]
  (when href
    (util/navigate-to href))
  false)

(defn search-keydown [e app owner ac-results]
  (when app
    (let [ctrl? (.-ctrlKey e)
          key-code (.-keyCode e)
          {:keys [highlighted-index]} @app]
      (when (= 27 key-code)
        (om/set-state! owner :text nil)
        (om/update! app :ac-results nil)
        (aset (om/get-node owner "input") "value" nil))
      (let [f (cond
                (and ctrl? (= 78 key-code)) inc ; ctrl-n
                (= 40 key-code) inc             ; down arrow
                (and ctrl? (= 80 key-code)) dec ; ctrl-p
                (= 38 key-code) dec             ; up arrow
                :else identity)]
        (when (and (not (= identity f))
                   (not (and (= inc f)
                             (= highlighted-index (dec (count ac-results)))))
                   (not (and (= dec f)
                             (= highlighted-index 0))))
          (om/transact! app :highlighted-index f))
        (when (not (= identity f))
          false)))))

(defn $quick-search [{:keys [highlighted-index search-loading? ac-results] :as app} owner]
  (reify
    om/IDidUpdate
    (did-update [_ _ _]
      (handle-search-active-state owner))
    om/IRenderState
    (render-state [this {:keys [text-chan text]}]
      (sab/html
        [:form.search
         {:autoComplete "off"
          :on-submit #(search-submit (nth ac-results (or highlighted-index 0)))}
         [:input.form-control
          {:class (when search-loading? " loading")
           :placeholder "Looking for? (ctrl-s)"
           :name "query"
           :autoComplete "off"
           :ref "input"
           :on-input #(put-text % text-chan owner)
           :on-key-down #(search-keydown % app owner ac-results)}]]))))

(defn $ac-results [{:keys [highlighted-index ac-results results-empty?]
                    :or {highlighted-index 0}
                    :as app}
                   owner]
  (reify
    om/IDidUpdate
    (did-update [_ prev-props prev-state]
      (when (> (count ac-results) 0)
        (let [$el (om/get-node owner (:highlighted-index app))]
          (when (and (not= (:highlighted-index prev-props)
                           (:highlighted-index app))
                     $el)
            (anim/scroll-into-view $el {:pad 30})))))
    om/IRender
    (render [this]
      (sab/html
        [:ul.ac-results
         (if results-empty?
           [:li.null-state "Nothing Found"]
           (map-indexed
             (fn [i {:keys [href type] :as res}]
               [:li {:on-click #(when href (util/navigate-to href))
                     :class (when (= i highlighted-index)
                              "highlighted")
                     :ref i}
                ($ac-entry res)])
             ac-results))]))))

(defn handle-search-scroll-to [owner prev-state]
  (when (and (= 0 (count (:text prev-state)))
             (= 1 (count (om/get-state owner :text))))
    (anim/scroll-to (om/get-node owner "input") {:pad 35})))

(defn $quick-lookup [{:keys [highlighted-index ac-results search-loading? results-empty?]
                      :or {highlighted-index 0}
                      :as app}
                     owner]
  (reify
    om/IDidUpdate
    (did-update [_ prev-props prev-state]
      (when (and (not= (:highlighted-index prev-props)
                       (:highlighted-index app))
                 (> (count ac-results) 0))
        (anim/scroll-into-view (om/get-node owner (:highlighted-index app)) {:pad 30}))
      (handle-search-scroll-to owner prev-state)
      (handle-search-active-state owner))
    om/IRenderState
    (render-state [this {:keys [text-chan text]}]
      (sab/html
        [:form.search
         {:autoComplete "off"
          :on-submit #(search-submit (nth ac-results highlighted-index))}
         [:input {:class (str "form-control" (when search-loading? " loading"))
                  :placeholder "Looking for?"
                  :name "query"
                  :autoComplete "off"
                  :autoFocus "autofocus"
                  :ref "input"
                  :on-input #(put-text % text-chan owner)
                  :on-key-down #(search-keydown % app owner ac-results)}]
         [:div.not-finding]
         [:div.not-finding {:class "not-finding"}
          "Can't find what you're looking for? "
          [:a.search-feedback
           {:href (str "/search-feedback" (when text (str "?query=" (util/url-encode text))))}
           "Help make ClojureDocs better"]
          "."]
         [:ul.ac-results
          (if results-empty?
            [:li.null-state "Nothing Found"]
            (map-indexed
              (fn [i {:keys [href type] :as res}]
                [:li {:on-click #(when href (util/navigate-to href))
                      :class (when (= i highlighted-index)
                               "highlighted")
                      :ref i}
                 ($ac-entry res)])
              ac-results))]]))))

(defn submit-feedback [owner query clojure-level text]
  (om/set-state! owner :loading? true)
  (om/set-state! owner :error-message nil)
  (ajax
    {:method :post
     :path (str "/search-feedback")
     :data {:query query
            :clojure-level clojure-level
            :text text}
     :success (fn [_]
                (util/navigate-to "/search-feedback/success"))
     :error (fn [_]
              (om/set-state! owner
                :error-message
                "There was a problem sending your feedback, try again.")
              (om/set-state! owner :loading? false))})
  false)

(defn $search-feedback [app owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (let [query (om/get-state owner :query)]
        (om/set-state! owner
          :text (when-not (empty? query)
                  (str
                    "Hey ClojureDocs, I searched for \""
                    query
                    "\", but couldn't find what I was looking for. Here's a description of what I would have liked to find:")))))
    om/IRenderState
    (render-state [_ {:keys [text loading? clojure-level query error-message]}]
      (sab/html
        [:form {:on-submit #(submit-feedback owner query clojure-level text)}
         [:div.form-group
          [:label.clojure-level
           "Level of Clojuring"]
          [:div.radio
           [:label.radio
            [:input {:type "radio"
                     :name "clojure-level"
                     :value "beginner"
                     :on-click #(om/set-state! owner :clojure-level "beginner")
                     :disabled (if loading? "disabled")}]
            "I haven't written any Clojure"]
           [:label.radio
            [:input {:type "radio"
                     :name "clojure-level"
                     :value "intermediate"
                     :on-click #(om/set-state! owner :clojure-level "intermediate")
                     :disabled (if loading? "disabled")}]
            "I've done a few things in Clojure"]
           [:label.radio
            [:input.radio
             {:type "radio"
              :name "clojure-level"
              :value "advanced"
              :on-click #(om/set-state! owner :clojure-level "advanced")
              :disabled (if loading? "disabled")}]
            "I'm comfortable contributing to Clojure projects"]]]
         [:div.form-group
          [:label {:for "feedback"} "Feedback"]
          [:textarea {:class "form-control"
                      :autoFocus "autofocus"
                      :rows 10
                      :name "feedback"
                      :value text
                      :on-input #(om/set-state! owner :text (.. % -target -value))
                      :disabled (if loading? "disabled")}]]
         [:div.form-group
          [:span {:class (str "error-message" (when-not error-message " hidden"))}
           [:i.fa.fa-exclamation-circle]
           error-message]
          [:button.btn.btn-default.pull-right
           {:disabled (if loading? "disabled")}
           "Send Feedback"]
          [:img {:class (str "pull-right loading" (when-not loading? " hidden"))
                 :src "/img/loading.gif"}]]]))))
