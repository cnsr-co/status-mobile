(ns status-im.contexts.wallet.account.tabs.about.view
  (:require
    [quo.core :as quo]
    [react-native.clipboard :as clipboard]
    [react-native.core :as rn]
    [status-im.config :as config]
    [status-im.contexts.profile.utils :as profile.utils]
    [status-im.contexts.wallet.account.tabs.about.style :as style]
    [status-im.contexts.wallet.common.utils :as utils]
    [utils.i18n :as i18n]
    [utils.re-frame :as rf]))

(defn about-options
  []
  (let [{:keys [address] :as account} (rf/sub [:wallet/current-viewing-account])
        networks                      (rf/sub [:wallet/network-preference-details])
        share-title                   (str (:name account) " " (i18n/label :t/address))
        multichain-address            (utils/get-multichain-address networks address)]
    [quo/action-drawer
     [[{:icon                :i/link
        :accessibility-label :view-on-eth
        :label               (i18n/label :t/view-on-eth)
        :right-icon          :i/external
        :on-press            #(rf/dispatch
                               [:wallet/navigate-to-chain-explorer-from-bottom-sheet
                                config/mainnet-chain-explorer-link
                                address])}
       {:icon                :i/link
        :accessibility-label :view-on-opt
        :label               (i18n/label :t/view-on-opt)
        :right-icon          :i/external
        :on-press            #(rf/dispatch
                               [:wallet/navigate-to-chain-explorer-from-bottom-sheet
                                config/optimism-mainnet-chain-explorer-link
                                address])}
       {:icon                :i/link
        :accessibility-label :view-on-arb
        :label               (i18n/label :t/view-on-arb)
        :right-icon          :i/external
        :on-press            #(rf/dispatch
                               [:wallet/navigate-to-chain-explorer-from-bottom-sheet
                                config/arbitrum-mainnet-chain-explorer-link
                                address])}
       {:icon                :i/copy
        :accessibility-label :copy-address
        :label               (i18n/label :t/copy-address)
        :on-press            (fn []
                               (clipboard/set-string multichain-address)
                               (rf/dispatch [:toasts/upsert
                                             {:type :positive
                                              :text (i18n/label :t/address-copied)}]))}
       {:icon                :i/qr-code
        :accessibility-label :show-address-qr
        :label               (i18n/label :t/show-address-qr)
        :on-press            #(rf/dispatch [:open-modal :wallet-share-address {:status :share}])}
       {:icon                :i/share
        :accessibility-label :share-address
        :label               (i18n/label :t/share-address)
        :on-press            (fn []
                               (rf/dispatch [:hide-bottom-sheet])
                               (js/setTimeout
                                #(rf/dispatch [:wallet/share-account
                                               {:title share-title :content multichain-address}])
                                600))}]]]))

(defn view
  []
  (let [{:keys [customization-color] :as profile} (rf/sub [:profile/profile-with-image])
        {:keys [address path watch-only?]}        (rf/sub [:wallet/current-viewing-account])
        networks                                  (rf/sub [:wallet/network-preference-details])]
    [rn/scroll-view
     {:style                   style/about-tab
      :content-container-style {:padding-bottom 20}}
     [quo/data-item
      {:description     :default
       :icon-right?     true
       :right-icon      :i/options
       :card?           true
       :label           :none
       :status          :default
       :size            :default
       :title           (if watch-only? (i18n/label :t/watched-address) (i18n/label :t/address))
       :custom-subtitle (fn [] [quo/address-text
                                {:networks networks
                                 :address  address
                                 :format   :long}])
       :container-style {:margin-bottom 12}
       :on-press        #(rf/dispatch [:show-bottom-sheet {:content about-options}])}]
     (when (not watch-only?)
       [quo/account-origin
        {:type                :default-keypair
         :stored              :on-device
         :profile-picture     (profile.utils/photo profile)
         :customization-color customization-color
         :derivation-path     path
         :user-name           (profile.utils/displayed-name profile)
         :on-press            #(js/alert "To be implemented")}])]))
