(ns status-im.contexts.communities.events
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [legacy.status-im.data-store.chats :as data-store.chats]
            [legacy.status-im.mailserver.core :as mailserver]
            [react-native.platform :as platform]
            [react-native.share :as share]
            [schema.core :as schema]
            [status-im.constants :as constants]
            [status-im.contexts.chat.messenger.messages.link-preview.events :as link-preview.events]
            status-im.contexts.communities.actions.community-options.events
            status-im.contexts.communities.actions.leave.events
            [status-im.navigation.events :as navigation]
            [taoensso.timbre :as log]
            [utils.i18n :as i18n]
            [utils.re-frame :as rf]))

(defn <-request-to-join-community-rpc
  [r]
  (set/rename-keys r
                   {:communityId :community-id
                    :publicKey   :public-key
                    :chatId      :chat-id}))

(defn <-requests-to-join-community-rpc
  [requests key-fn]
  (reduce #(assoc %1 (key-fn %2) (<-request-to-join-community-rpc %2)) {} requests))

(defn <-chats-rpc
  [chats]
  (reduce-kv (fn [acc k v]
               (assoc acc
                      (name k)
                      (-> v
                          (assoc :can-post? (:canPost v))
                          (dissoc :canPost)
                          (update :members walk/stringify-keys))))
             {}
             chats))

(defn <-categories-rpc
  [categ]
  (reduce-kv #(assoc %1 (name %2) %3) {} categ))

(defn <-rpc
  [c]
  (-> c
      (set/rename-keys {:canRequestAccess            :can-request-access?
                        :canManageUsers              :can-manage-users?
                        :canDeleteMessageForEveryone :can-delete-message-for-everyone?
                        :canJoin                     :can-join?
                        :requestedToJoinAt           :requested-to-join-at
                        :isMember                    :is-member?
                        :adminSettings               :admin-settings
                        :tokenPermissions            :token-permissions
                        :communityTokensMetadata     :tokens-metadata
                        :introMessage                :intro-message
                        :muteTill                    :muted-till})
      (update :admin-settings
              set/rename-keys
              {:pinMessageAllMembersEnabled :pin-message-all-members-enabled?})
      (update :members walk/stringify-keys)
      (update :chats <-chats-rpc)
      (update :token-permissions seq)
      (update :categories <-categories-rpc)
      (assoc :token-images
             (reduce (fn [acc {sym :symbol image :image}]
                       (assoc acc sym image))
                     {}
                     (:communityTokensMetadata c)))))

(rf/reg-event-fx :communities/handle-community
 (fn [{:keys [db]}
      [community-js]]
   (when community-js
     (let [{:keys [token-permissions
                   token-permissions-check joined id]
            :as   community} (<-rpc community-js)
           has-channel-perm? (fn [id-perm-tuple]
                               (let [{:keys [type]} (second id-perm-tuple)]
                                 (or (= type constants/community-token-permission-can-view-channel)
                                     (=
                                      type
                                      constants/community-token-permission-can-view-and-post-channel))))]
       {:db (assoc-in db [:communities id] community)
        :fx [[:dispatch [:communities/initialize-permission-addresses id]]
             (when (not joined)
               [:dispatch [:chat.ui/spectate-community id]])
             (when (nil? token-permissions-check)
               [:dispatch [:communities/check-permissions-to-join-community id]])
             (when (some has-channel-perm? token-permissions)
               [:dispatch [:communities/check-all-community-channels-permissions id]])]}))))

(rf/defn handle-removed-chats
  [{:keys [db]} chat-ids]
  {:db (reduce (fn [db chat-id]
                 (update db :chats dissoc chat-id))
               db
               chat-ids)})

(defn- handle-my-request
  [db {:keys [community-id state deleted] :as request}]
  (let [{:keys [name]} (get-in db [:communities community-id])]
    (cond (and (= constants/community-request-to-join-state-pending state) (not deleted))
          (assoc-in db [:communities/my-pending-requests-to-join community-id] request)
          (and (= constants/community-request-to-join-state-accepted state) (not deleted))
          (do (rf/dispatch [:toasts/upsert
                            {:id   :joined-community
                             :type :positive
                             :text (i18n/label :t/joined-community {:community name})}])
              (update-in db [:communities/my-pending-requests-to-join] dissoc community-id))
          :else (update-in db [:communities/my-pending-requests-to-join] dissoc community-id))))

(defn handle-admin-request
  [db {:keys [id community-id deleted] :as request}]
  (if deleted
    (update-in db [:communities/requests-to-join community-id] dissoc id)
    (assoc-in db [:communities/requests-to-join community-id id] request)))

(rf/defn handle-requests-to-join
  [{:keys [db]} requests]
  (let [my-public-key (get-in db [:profile/profile :public-key])]
    {:db (reduce (fn [db {:keys [public-key] :as request}]
                   (let [my-request? (= my-public-key public-key)]
                     (if my-request?
                       (handle-my-request db request)
                       (handle-admin-request db request))))
                 db
                 requests)}))

(rf/defn handle-communities
  {:events [:community/fetch-success]}
  [{:keys [db]} communities]
  {:fx
   (->> communities
        (map #(vector :dispatch [:communities/handle-community %])))})

(rf/reg-event-fx :communities/request-to-join-result
 (fn [{:keys [db]} [community-id request-id response-js]]
   {:db         (update-in db [:communities/requests-to-join community-id] dissoc request-id)
    :dispatch-n [[:sanitize-messages-and-process-response response-js]
                 [:activity-center.notifications/mark-as-read request-id]]}))

(rf/reg-event-fx :communities/decline-request-to-join-pressed
 (fn [_ [community-id request-id]]
   {:json-rpc/call
    [{:method      "wakuext_declineRequestToJoinCommunity"
      :params      [{:id request-id}]
      :js-response true
      :on-success  #(rf/dispatch [:communities/request-to-join-result community-id request-id %])
      :on-error    #(log/error "failed to decline " community-id request-id)}]}))

(rf/reg-event-fx :communities/accept-request-to-join-pressed
 (fn [_ [community-id request-id]]
   {:json-rpc/call
    [{:method      "wakuext_acceptRequestToJoinCommunity"
      :params      [{:id request-id}]
      :js-response true
      :on-success  #(rf/dispatch [:communities/request-to-join-result community-id request-id %])
      :on-error    #(log/error "failed to accept requests-to-join" community-id request-id %)}]}))

(rf/reg-event-fx :communities/get-user-requests-to-join-success
 (fn [{:keys [db]} [requests]]
   {:db (assoc db
               :communities/my-pending-requests-to-join
               (<-requests-to-join-community-rpc requests :communityId))}))

(rf/reg-event-fx :communities/get-user-requests-to-join
 (fn [_]
   {:json-rpc/call [{:method     "wakuext_myPendingRequestsToJoin"
                     :params     []
                     :on-success #(rf/dispatch [:communities/get-user-requests-to-join-success %])
                     :on-error   #(log/error "failed to get requests to join community")}]}))

(rf/reg-event-fx :communities/fetched-collapsed-categories-success
 (fn [{:keys [db]} [categories]]
   {:db (assoc db
               :communities/collapsed-categories
               (reduce
                (fn [acc {:keys [communityId categoryId]}]
                  (assoc-in acc [communityId categoryId] true))
                {}
                categories))}))

(rf/reg-event-fx :community/fetch
 (fn [_]
   {:json-rpc/call [{:method     "wakuext_communities"
                     :params     []
                     :on-success #(rf/dispatch [:community/fetch-success %])
                     :on-error   #(log/error "failed to fetch communities" %)}
                    {:method      "wakuext_checkAndDeletePendingRequestToJoinCommunity"
                     :params      []
                     :js-response true
                     :on-success  #(rf/dispatch [:sanitize-messages-and-process-response %])
                     :on-error    #(log/info "failed to fetch communities" %)}
                    {:method     "wakuext_collapsedCommunityCategories"
                     :params     []
                     :on-success #(rf/dispatch [:communities/fetched-collapsed-categories-success %])
                     :on-error   #(log/error "failed to fetch collapsed community categories" %)}]}))

(defn initialize-permission-addresses
  [{:keys [db]} [community-id]]
  (when community-id
    (let [accounts        (get-in db [:wallet :accounts])
          sorted-accounts (sort-by :position (vals accounts))
          addresses       (set (map :address sorted-accounts))]
      {:db (update-in db
                      [:communities community-id]
                      assoc
                      :previous-permission-addresses addresses
                      :selected-permission-addresses addresses
                      :airdrop-address               (:address (first sorted-accounts)))})))

(rf/reg-event-fx :communities/initialize-permission-addresses
 initialize-permission-addresses)

(defn update-previous-permission-addresses
  [{:keys [db]} [community-id]]
  (when community-id
    (let [accounts                      (get-in db [:wallet :accounts])
          sorted-accounts               (sort-by :position (vals accounts))
          selected-permission-addresses (get-in db
                                                [:communities community-id
                                                 :selected-permission-addresses])
          selected-accounts             (filter #(contains? selected-permission-addresses (:address %))
                                                sorted-accounts)
          current-airdrop-address       (get-in db [:communities community-id :airdrop-address])]
      {:db (update-in db
                      [:communities community-id]
                      assoc
                      :previous-permission-addresses selected-permission-addresses
                      :airdrop-address               (if (contains? selected-permission-addresses
                                                                    current-airdrop-address)
                                                       current-airdrop-address
                                                       (:address (first selected-accounts))))})))

(rf/reg-event-fx :communities/update-previous-permission-addresses
 update-previous-permission-addresses)

(defn toggle-selected-permission-address
  [{:keys [db]} [address community-id]]
  {:db (update-in db
                  [:communities community-id :selected-permission-addresses]
                  (fn [selected-addresses]
                    (if (contains? selected-addresses address)
                      (disj selected-addresses address)
                      (conj selected-addresses address))))})

(rf/reg-event-fx :communities/toggle-selected-permission-address
 toggle-selected-permission-address)

(rf/reg-event-fx :communities/reset-selected-permission-addresses
 (fn [{:keys [db]} [community-id]]
   (when community-id
     {:db (assoc-in db
           [:communities community-id :selected-permission-addresses]
           (get-in db [:communities community-id :previous-permission-addresses]))})))

(rf/reg-event-fx :communities/share-community-channel-url-with-data
 (fn [_ [chat-id]]
   (let [{:keys [community-id channel-id]} (data-store.chats/decode-chat-id chat-id)
         title                             (i18n/label :t/channel-on-status)]
     {:json-rpc/call
      [{:method     "wakuext_shareCommunityChannelURLWithData"
        :params     [{:CommunityID community-id :ChannelID channel-id}]
        :on-success (fn [url]
                      (share/open
                       (if platform/ios?
                         {:activityItemSources [{:placeholderItem {:type    "text"
                                                                   :content title}
                                                 :item            {:default {:type    "url"
                                                                             :content url}}
                                                 :linkMetadata    {:title title}}]}
                         {:title     title
                          :subject   title
                          :message   url
                          :url       url
                          :isNewTask true})))
        :on-error   (fn [err]
                      (log/error "failed to retrieve community channel url with data"
                                 {:error   err
                                  :chat-id chat-id
                                  :event   "share-community-channel-url-with-data"}))}]})))

(rf/reg-event-fx :communities/set-airdrop-address
 (fn [{:keys [db]} [address community-id]]
   {:db (assoc-in db [:communities community-id :airdrop-address] address)}))

(defn community-fetched
  [{:keys [db]} [community-id community]]
  (when community
    {:db (update db :communities/fetching-community dissoc community-id)
     :fx [[:dispatch [:communities/handle-community community]]
          [:dispatch
           [:chat.ui/cache-link-preview-data (link-preview.events/community-link community-id)
            community]]]}))

(rf/reg-event-fx :chat.ui/community-fetched community-fetched)

(defn community-failed-to-fetch
  [{:keys [db]} [community-id]]
  {:db (update db :communities/fetching-community dissoc community-id)})

(rf/reg-event-fx :chat.ui/community-failed-to-fetch community-failed-to-fetch)

(defn fetch-community
  [{:keys [db]} [community-id]]
  (when (and community-id (not (get-in db [:communities/fetching-community community-id])))
    {:db            (assoc-in db [:communities/fetching-community community-id] true)
     :json-rpc/call [{:method     "wakuext_fetchCommunity"
                      :params     [{:CommunityKey    community-id
                                    :TryDatabase     true
                                    :WaitForResponse true}]
                      :on-success (fn [community]
                                    (rf/dispatch [:chat.ui/community-fetched community-id community]))
                      :on-error   (fn [err]
                                    (rf/dispatch [:chat.ui/community-failed-to-fetch community-id])
                                    (log/error {:message
                                                "Failed to request community info from mailserver"
                                                :error err}))}]}))

(schema/=> fetch-community
  [:=>
   [:catn
    [:cofx :schema.re-frame/cofx]
    [:args
     [:schema [:catn [:community-id [:? :string]]]]]]
   [:maybe
    [:map
     [:db map?]
     [:json-rpc/call :schema.common/rpc-call]]]])

(rf/reg-event-fx :communities/fetch-community fetch-community)

(defn spectate-community-success
  [{:keys [db]} [{:keys [communities]}]]
  (when-let [community (first communities)]
    {:db (-> db
             (assoc-in [:communities (:id community) :spectated] true)
             (assoc-in [:communities (:id community) :spectating] false))
     :fx [[:dispatch [:communities/handle-community community]]
          [:dispatch [::mailserver/request-messages]]]}))

(rf/reg-event-fx :chat.ui/spectate-community-success spectate-community-success)

(defn spectate-community-failed
  [{:keys [db]} [community-id]]
  {:db (assoc-in db [:communities community-id :spectating] false)})

(rf/reg-event-fx :chat.ui/spectate-community-failed spectate-community-failed)

(defn spectate-community
  [{:keys [db]} [community-id]]
  (let [{:keys [spectated spectating joined]} (get-in db [:communities community-id])]
    (when (and (not joined) (not spectated) (not spectating))
      {:db            (assoc-in db [:communities community-id :spectating] true)
       :json-rpc/call [{:method     "wakuext_spectateCommunity"
                        :params     [community-id]
                        :on-success [:chat.ui/spectate-community-success]
                        :on-error   (fn [err]
                                      (log/error {:message
                                                  "Failed to spectate community"
                                                  :error err})
                                      (rf/dispatch [:chat.ui/spectate-community-failed
                                                    community-id]))}]})))

(schema/=> spectate-community
  [:=>
   [:catn
    [:cofx :schema.re-frame/cofx]
    [:args
     [:schema [:catn [:community-id [:? :string]]]]]]
   [:maybe
    [:map
     [:db map?]
     [:json-rpc/call :schema.common/rpc-call]]]])

(rf/reg-event-fx :chat.ui/spectate-community spectate-community)

(rf/defn navigate-to-serialized-community
  [_ {:keys [community-id]}]
  {:serialization/deserialize-and-compress-key
   {:serialized-key community-id
    :on-success     #(rf/dispatch [:communities/navigate-to-community-overview %])
    :on-error       #(log/error {:message      "Failed to decompress community-id"
                                 :error        %
                                 :community-id community-id})}})

(rf/reg-event-fx :communities/navigate-to-community-overview
 (fn [cofx [deserialized-key]]
   (if (string/starts-with? deserialized-key constants/serialization-key)
     (navigate-to-serialized-community cofx deserialized-key)
     (rf/merge
      cofx
      {:fx [[:dispatch [:communities/fetch-community deserialized-key]]
            [:dispatch [:navigate-to :community-overview deserialized-key]]]}
      (navigation/pop-to-root :shell-stack)))))

(rf/reg-event-fx :communities/navigate-to-community-chat
 (fn [{:keys [db]} [chat-id pop-to-root?]]
   (let [{:keys [community-id]} (get-in db [:chats chat-id])]
     {:fx [(when community-id
             [:dispatch [:communities/fetch-community community-id]])
           (if pop-to-root?
             [:dispatch [:chat/pop-to-root-and-navigate-to-chat chat-id]]
             [:dispatch [:chat/navigate-to-chat chat-id]])]})))
