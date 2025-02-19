(ns status-im.contexts.communities.events-test
  (:require [cljs.test :refer [deftest is testing]]
            [legacy.status-im.mailserver.core :as mailserver]
            matcher-combinators.test
            [status-im.contexts.chat.messenger.messages.link-preview.events :as link-preview.events]
            [status-im.contexts.communities.events :as events]))

(def community-id "community-id")

(deftest initialize-permission-addresses-test
  (let [initial-db  {:db {:wallet {:accounts {"0x1" {:address  "0x1"
                                                     :position 0}
                                              "0x2" {:address  "0x2"
                                                     :position 1}}}}}
        expected-db {:db (update-in (:db initial-db)
                                    [:communities community-id]
                                    assoc
                                    :previous-permission-addresses #{"0x1" "0x2"}
                                    :selected-permission-addresses #{"0x1" "0x2"}
                                    :airdrop-address               "0x1")}]
    (is (match? expected-db (events/initialize-permission-addresses initial-db [community-id])))))

(deftest toggle-selected-permission-address-test
  (let [initial-db {:db {:communities {community-id {:selected-permission-addresses #{"0x1" "0x2"}}}}}]
    (is (match? {:db {:communities {community-id {:selected-permission-addresses #{"0x1"}}}}}
                (events/toggle-selected-permission-address initial-db ["0x2" community-id])))
    (is (match? {:db {:communities {community-id {:selected-permission-addresses #{"0x1" "0x2" "0x3"}}}}}
                (events/toggle-selected-permission-address initial-db ["0x3" community-id])))))

(deftest update-previous-permission-addresses-test
  (let [wallet {:accounts {"0x1" {:address  "0x1"
                                  :position 0}
                           "0x2" {:address  "0x2"
                                  :position 1}
                           "0x3" {:address  "0x3"
                                  :position 2}}}]
    (let [initial-db  {:db {:wallet      wallet
                            :communities {community-id {:previous-permission-addresses #{"0x1" "0x2"}
                                                        :selected-permission-addresses #{"0x1" "0x2"
                                                                                         "0x3"}
                                                        :airdrop-address               "0x1"}}}}
          expected-db {:db {:wallet      wallet
                            :communities {community-id {:previous-permission-addresses #{"0x1" "0x2"
                                                                                         "0x3"}
                                                        :selected-permission-addresses #{"0x1" "0x2"
                                                                                         "0x3"}
                                                        :airdrop-address               "0x1"}}}}]
      (is
       (match? expected-db
               (events/update-previous-permission-addresses initial-db [community-id]))))
    (let [initial-db  {:db {:wallet      wallet
                            :communities {community-id {:previous-permission-addresses #{"0x1" "0x2"}
                                                        :selected-permission-addresses #{"0x2" "0x3"}
                                                        :airdrop-address               "0x1"}}}}
          expected-db {:db {:wallet      wallet
                            :communities {community-id {:previous-permission-addresses #{"0x2" "0x3"}
                                                        :selected-permission-addresses #{"0x2" "0x3"}
                                                        :airdrop-address               "0x2"}}}}]
      (is
       (match? expected-db
               (events/update-previous-permission-addresses initial-db [community-id]))))))

(deftest fetch-community
  (testing "with community id"
    (testing "update fetching indicator in db"
      (is (match?
           {:db {:communities/fetching-community {community-id true}}}
           (events/fetch-community {} [community-id]))))
    (testing "call the fetch community rpc method with correct community id"
      (is (match?
           {:json-rpc/call [{:method "wakuext_fetchCommunity"
                             :params [{:CommunityKey    community-id
                                       :TryDatabase     true
                                       :WaitForResponse true}]}]}
           (events/fetch-community {} [community-id])))))
  (testing "with no community id"
    (testing "do nothing"
      (is (match?
           nil
           (events/fetch-community {} []))))))

(deftest community-failed-to-fetch
  (testing "given a community id"
    (testing "remove community id from fetching indicator in db"
      (is (match?
           nil
           (get-in (events/community-failed-to-fetch {:db {:communities/fetching-community
                                                           {community-id true}}}
                                                     [community-id])
                   [:db :communities/fetching-community community-id]))))))

(deftest community-fetched
  (with-redefs [link-preview.events/community-link (fn [id] (str "community-link+" id))]
    (testing "given a community"
      (let [cofx {:db {:communities/fetching-community {community-id true}}}
            arg  [community-id {:id community-id}]]
        (testing "remove community id from fetching indicator in db"
          (is (match?
               nil
               (get-in (events/community-fetched cofx arg)
                       [:db :communities/fetching-community community-id]))))
        (testing "dispatch fxs"
          (is (match?
               {:fx [[:dispatch [:communities/handle-community {:id community-id}]]
                     [:dispatch
                      [:chat.ui/cache-link-preview-data "community-link+community-id"
                       {:id community-id}]]]}
               (events/community-fetched cofx arg))))))
    (testing "given a joined community"
      (let [cofx {:db {:communities/fetching-community {community-id true}}}
            arg  [community-id {:id community-id :joined true}]]
        (testing "dispatch fxs, do not spectate community"
          (is (match?
               {:fx [[:dispatch [:communities/handle-community {:id community-id}]]
                     [:dispatch
                      [:chat.ui/cache-link-preview-data "community-link+community-id"
                       {:id community-id}]]]}
               (events/community-fetched cofx arg))))))
    (testing "given a token-gated community"
      (let [cofx {:db {:communities/fetching-community {community-id true}}}
            arg  [community-id {:id community-id :tokenPermissions [1]}]]
        (testing "dispatch fxs, do not spectate community"
          (is (match?
               {:fx [[:dispatch [:communities/handle-community {:id community-id}]]
                     [:dispatch
                      [:chat.ui/cache-link-preview-data "community-link+community-id"
                       {:id community-id}]]]}
               (events/community-fetched cofx arg))))))
    (testing "given nil community"
      (testing "do nothing"
        (is (match?
             nil
             (events/community-fetched {} [community-id nil])))))))

(deftest spectate-community
  (testing "given a joined community"
    (testing "do nothing"
      (is (match?
           nil
           (events/spectate-community {:db {:communities {community-id {:joined true}}}}
                                      [community-id])))))
  (testing "given a spectated community"
    (testing "do nothing"
      (is (match?
           nil
           (events/spectate-community {:db {:communities {community-id {:spectated true}}}}
                                      [community-id])))))
  (testing "given a spectating community"
    (testing "do nothing"
      (is (match?
           nil
           (events/spectate-community {:db {:communities {community-id {:spectating true}}}}
                                      [community-id])))))
  (testing "given a community"
    (testing "mark community spectating"
      (is (match?
           {:db {:communities {community-id {:spectating true}}}}
           (events/spectate-community {:db {:communities {community-id {}}}} [community-id]))))
    (testing "call spectate community rpc with correct community id"
      (is (match?
           {:json-rpc/call [{:method "wakuext_spectateCommunity"
                             :params [community-id]}]}
           (events/spectate-community {:db {:communities {community-id {}}}} [community-id]))))))

(deftest spectate-community-failed
  (testing "mark community spectating false"
    (is (match?
         {:db {:communities {community-id {:spectating false}}}}
         (events/spectate-community-failed {} [community-id])))))

(deftest spectate-community-success
  (testing "given communities"
    (testing "mark first community spectating false"
      (is (match?
           {:db {:communities {community-id {:spectating false}}}}
           (events/spectate-community-success {} [{:communities [{:id community-id}]}]))))
    (testing "mark first community spectated true"
      (is (match?
           {:db {:communities {community-id {:spectated true}}}}
           (events/spectate-community-success {} [{:communities [{:id community-id}]}]))))
    (testing "dispatch fxs for first community"
      (is (match?
           {:fx [[:dispatch [:communities/handle-community {:id community-id}]]
                 [:dispatch [::mailserver/request-messages]]]}
           (events/spectate-community-success {} [{:communities [{:id community-id}]}])))))
  (testing "given empty community"
    (testing "do nothing"
      (is (match?
           nil
           (events/spectate-community-success {} [{:communities []}])))))
  (testing "given nil community"
    (testing "do nothing"
      (is (match?
           nil
           (events/spectate-community-success {} []))))))
