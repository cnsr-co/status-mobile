(ns quo.components.list-items.user
  (:require
    [quo.components.avatars.user-avatar.view :as user-avatar]
    [quo.components.icon :as icons]
    [quo.components.markdown.text :as text]
    [quo.components.messages.author.view :as author]
    [quo.components.selectors.selectors.view :as selectors]
    [quo.foundations.colors :as colors]
    [react-native.core :as rn]))

(def container-style
  {:margin-horizontal  8
   :padding-vertical   8
   :padding-horizontal 12
   :border-radius      12
   :flex               1
   :flex-direction     :row
   :align-items        :center})

(defn action-icon
  [{:keys [type on-press on-check disabled? checked?]} theme]
  [rn/touchable-opacity
   {:on-press (when on-press on-press)}
   (case type
     :options
     [icons/icon :i/options
      {:size  20
       :color (colors/theme-colors colors/neutral-50 colors/neutral-40 theme)}]
     :checkbox
     [selectors/view
      {:type                :checkbox
       :checked?            checked?
       :accessibility-label :user-list-toggle-check
       :disabled?           disabled?
       :on-change           (when on-check on-check)}]
     :close
     [text/text "not implemented"]
     [rn/view])])

(defn user
  [{:keys [short-chat-key primary-name secondary-name photo-path online? contact? verified?
           untrustworthy? on-press on-long-press accessory customization-color theme]}]
  [rn/touchable-highlight
   {:style               container-style
    :underlay-color      (colors/resolve-color customization-color theme 5)
    :accessibility-label :user-list
    :on-press            (when on-press on-press)
    :on-long-press       (when on-long-press on-long-press)}
   [:<>
    [user-avatar/user-avatar
     {:full-name       primary-name
      :profile-picture photo-path
      :online?         online?
      :size            :small}]
    [rn/view {:style {:margin-horizontal 8 :flex 1}}
     [author/view
      {:primary-name   primary-name
       :secondary-name secondary-name
       :contact?       contact?
       :verified?      verified?
       :untrustworthy? untrustworthy?
       :size           15}]
     (when short-chat-key
       [text/text
        {:size  :paragraph-2
         :style {:color (colors/theme-colors colors/neutral-50 colors/neutral-40)}}
        short-chat-key])]
    (when accessory
      [action-icon accessory theme])]])
