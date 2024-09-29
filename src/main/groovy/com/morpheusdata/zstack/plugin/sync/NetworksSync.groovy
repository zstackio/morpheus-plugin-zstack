package com.morpheusdata.zstack.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.Network
import com.morpheusdata.model.projection.NetworkIdentityProjection
import com.morpheusdata.zstack.plugin.ZstackPlugin
import com.morpheusdata.zstack.plugin.utils.ZStackComputeUtility
import groovy.util.logging.Slf4j

@Slf4j
class NetworksSync {

    private Cloud cloud
    private MorpheusContext morpheusContext
    private ZstackPlugin plugin
    private HttpApiClient apiClient

    public NetworksSync(ZstackPlugin zstackPlugin, Cloud cloud, HttpApiClient apiClient) {
        this.plugin = zstackPlugin
        this.cloud = cloud
        this.morpheusContext = zstackPlugin.morpheus
        this.apiClient = apiClient
    }

    def execute() {
        log.info "BEGIN: execute NetworksSync: ${cloud.id}"
        def rtn = [success: false]
        try {
            def authConfig = plugin.getAuthConfig(cloud)
            def listResults = ZStackComputeUtility.listNetworks(apiClient, authConfig)
            if (listResults.success == true) {

                def domainRecords = morpheusContext.async.cloud.network.listIdentityProjections(cloud.id)

                SyncTask<NetworkIdentityProjection, Map, CloudPool> syncTask = new SyncTask<>(domainRecords, listResults.results.inventories)

                syncTask.addMatchFunction { NetworkIdentityProjection domainObject, Map cloudItem ->
                    (domainObject.externalId == cloudItem?.uuid)
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkIdentityProjection, Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<NetworkIdentityProjection, Map>> updateItemMap = updateItems.collectEntries {
                        [(it.existingItem.id): it]
                    }
                    morpheusContext.async.cloud.network.listById(updateItems?.collect {
                        it.existingItem.id
                    }).map { Network network ->
                        SyncTask.UpdateItemDto<NetworkIdentityProjection, Map> matchItem = updateItemMap[network.id]
                        return new SyncTask.UpdateItem<NetworkIdentityProjection, Map>(existingItem: network, masterItem: matchItem.masterItem)
                    }
                }.onAdd { itemsToAdd ->
                    addMissingNetworks(cloud, itemsToAdd)
                }.onUpdate { List<SyncTask.UpdateItem<Network, Map>> updateItems ->
                    updateMatchedNetworks(cloud, updateItems)
                }.onDelete { removeItems ->
                    morpheusContext.async.cloud.network.remove(removeItems).blockingGet()
                }.start()
            }
        } catch (e) {
            log.info "NetworksSync error in execute : ${e}", e
        }
        log.info "END: execute NetworksSync: ${cloud.id}"
    }

    private addMissingNetworks(Cloud cloud, List addList) {
        log.info "addMissingNetworks: ${cloud} ${addList.size()}"
        def networkAdds = []
        def networkTypes = plugin.cloudProvider.getNetworkTypes()

        for (cloudItem in addList) {
            try {
                def networkConfig = [
                        owner      : new Account(id: cloud.owner.id),
                        category   : "zstack.network.${cloud.id}",
                        name       : cloudItem.name,
                        description: cloudItem.description,
                        displayName: cloudItem.name,
                        code       : "zstack.network.${cloud.id}.${cloudItem.uuid}",
                        uniqueId   : cloudItem.uuid,
                        externalId : cloudItem.uuid,
                        dhcpServer : true,
                        refType    : 'ComputeZone',
                        refId      : cloud.id,
                        active     : cloud.defaultNetworkSyncActive
                ]

                Network networkAdd = new Network(networkConfig)
                switch (cloudItem.category) {
                    case "Private":
                        networkAdd.type = networkTypes?.find { it.code == "zstack-private-network" }
                        break
                    case "Public":
                        networkAdd.type = networkTypes?.find { it.code == "zstack-public-network" }
                        break
                    case "System":
                        networkAdd.type = networkTypes?.find { it.code == "zstack-system-network" }
                        break
                }
                log.info "cloudItem.category: ${cloudItem.category}"
                log.info "networkAdd.type: ${networkAdd.type}"
                log.info "networkAdd.type.name: ${networkAdd.type.name}"

                if (cloudItem.dns?.size() > 0) {
                    networkAdd.dnsPrimary = cloudItem.dns[0]
                }
                if (cloudItem.dns?.size() > 1) {
                    networkAdd.dnsSecondary = cloudItem.dns[1]
                }
                if (cloudItem.ipRanges?.size() > 0) {
                    networkAdd.cidr = cloudItem.ipRanges[0].networkCidr
                    networkAdd.gateway = cloudItem.ipRanges[0].gateway
                    networkAdd.netmask = cloudItem.ipRanges[0].netmask
                }
                networkAdds << networkAdd
            } catch (e) {
                log.info "Error in creating network: ${e}", e
            }

        }
        morpheusContext.async.cloud.network.create(networkAdds).blockingGet()
    }

    private updateMatchedNetworks(Cloud cloud, List updateList) {
        log.info "updateMatchedNetworks: ${cloud} ${updateList.size()}"
        List<Network> itemsToUpdate = []
        for (update in updateList) {
            Network currentServer = update.existingItem
            def matchedServer = update.masterItem
            if (currentServer) {
                def save = false

                if (currentServer.name != matchedServer.name) {
                    currentServer.name = matchedServer.name
                    save = true
                }

                if (currentServer.description != matchedServer.description) {
                    currentServer.description = matchedServer.description
                    save = true
                }

                if (matchedServer.dns?.size() > 0 && currentServer.dnsPrimary != matchedServer.dns[0]) {
                    currentServer.dnsPrimary = matchedServer?.dns[0]
                    save = true
                }
                if (matchedServer.dns?.size() > 1 && currentServer.dnsSecondary != matchedServer.dns[1]) {
                    currentServer.dnsSecondary = matchedServer?.dns[1]
                    save = true
                }

                if (matchedServer.ipRanges?.size() > 0 && currentServer.cidr != matchedServer.ipRanges[0].networkCidr) {
                    currentServer.cidr = matchedServer.ipRanges[0]?.networkCidr
                    currentServer.gatewaymatchedServer.ipRanges[0]?.gateway
                    currentServer.netmaskmatchedServer.ipRanges[0]?.netmask
                    save = true
                }

                if (save) {
                    itemsToUpdate << currentServer
                }
            }
        }

        if (itemsToUpdate.size() > 0) {
            morpheusContext.async.cloud.network.save(itemsToUpdate).blockingGet()
        }
    }
}
