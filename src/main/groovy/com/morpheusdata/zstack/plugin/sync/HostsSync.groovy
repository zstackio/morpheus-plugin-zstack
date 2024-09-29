package com.morpheusdata.zstack.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.zstack.plugin.ZstackPlugin
import com.morpheusdata.zstack.plugin.utils.ZStackComputeUtility
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class HostsSync {

    private Cloud cloud
    private MorpheusContext morpheusContext
    private ZstackPlugin plugin
    private HttpApiClient apiClient

    public HostsSync(ZstackPlugin zstackPlugin, Cloud cloud, HttpApiClient apiClient) {
        this.plugin = zstackPlugin
        this.cloud = cloud
        this.morpheusContext = zstackPlugin.morpheus
        this.apiClient = apiClient
    }

    def execute() {
        log.info "BEGIN: execute HostsSync: ${cloud.id}"
        try {
            def authConfig = plugin.getAuthConfig(cloud)
            def listResults = ZStackComputeUtility.listHypervisors(apiClient, authConfig)
            if (listResults.success == true) {

                def queryResults = [:]
                def poolListProjections = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, '', null).filter { poolProjection ->
                    return poolProjection.internalId != null && poolProjection.type == 'Cluster'
                }.toList().blockingGet()
                queryResults.clusters = morpheusContext.async.cloud.pool.listById(poolListProjections.collect {
                    it.id
                }).toList().blockingGet()
                log.info "queryResults.clusters: ${queryResults.clusters}"


                Observable<ComputeServer> domainRecords = morpheusContext.async.computeServer.listIdentityProjections(cloud.id, null).filter { ComputeServerIdentityProjection projection ->
                    projection.category == "zstack.host.${cloud.id}"
                }

                SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(domainRecords, listResults.results.inventories)

                syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
                    log.info "host xxxxxxxxxxxxxx ${(domainObject.externalId == cloudItem?.uuid)}"
                    (domainObject.externalId == cloudItem?.uuid)
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap = updateItems.collectEntries {
                        [(it.existingItem.id): it]
                    }
                    morpheusContext.async.computeServer.listById(updateItems?.collect {
                        it.existingItem.id
                    }).map { ComputeServer server ->
                        SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map> matchItem = updateItemMap[server.id]
                        return new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: matchItem.masterItem)
                    }
                }.onAdd { itemsToAdd ->
                    addMissingHosts(cloud, queryResults.clusters, itemsToAdd)
                }.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
                    updateMatchedHosts(cloud, queryResults.clusters, updateItems)
                }.onDelete { removeItems ->
                    removeMissingHosts(cloud, removeItems)
                }.start()
            }
        } catch (e) {
            log.info "HostsSync error in execute : ${e}", e
        }
        log.info "END: execute HostsSync: ${cloud.id}"
    }

    private addMissingHosts(Cloud cloud, List clusters, List addList) {
        log.info "addMissingHosts: ${cloud} ${addList.size()}"

        for (cloudItem in addList) {
            try {
                def clusterObj = clusters?.find { pool -> pool.externalId == cloudItem.clusterUuid }
                def hostStatus = cloudItem.status
                def powerState = ComputeServer.PowerState.on
                switch (hostStatus) {
                    case "Connecting":
                        hostStatus = "provisioning"
                        powerState = ComputeServer.PowerState.paused
                        break
                    case "Connected":
                        hostStatus = "provisioned"
                        powerState = ComputeServer.PowerState.on
                        break
                    case "Disconnected":
                        hostStatus = "outline"
                        powerState = ComputeServer.PowerState.off
                        break
                }
                def serverConfig = [
                        account          : cloud.owner,
                        category         : "zstack.host.${cloud.id}",
                        cloud            : cloud,
                        name             : cloudItem.name,
                        resourcePool     : new ComputeZonePool(id: clusterObj.id),
                        externalId       : cloudItem.uuid,
                        uniqueId         : cloudItem.uuid,
                        sshUsername      : cloudItem.username,
                        status           : hostStatus,
                        provision        : false,
                        serverType       : 'hypervisor',
                        computeServerType: new ComputeServerType(code: 'zstack-hypervisor'),
                        serverOs         : new OsType(code: String.format("%s %s", cloudItem.osDistribution, cloudItem.osVersion)),
                        osType           : "linux",
                        hostname         : cloudItem.managementIp,
                        externalIp       : cloudItem.managementIp,
                        sshHost          : cloudItem.managementIp,
                        maxMemory        : cloudItem.totalMemoryCapacity,
                        maxCpu           : cloudItem.totalCpuCapacity,
                        usedMemory       : (cloudItem.totalMemoryCapacity - cloudItem.availableMemoryCapacity),
                        usedCpu          : (cloudItem.totalCpuCapacity - cloudItem.availableCpuCapacity),
                        powerState       : powerState
                ]

                def newServer = new ComputeServer(serverConfig)
                if (!morpheusContext.async.computeServer.bulkCreate([newServer]).blockingGet()) {
                    log.info "Error in creating host server ${newServer}"
                }
            } catch (e) {
                log.info "Error in creating host: ${e}", e
            }
        }
    }

    private updateMatchedHosts(Cloud cloud, List clusters, List updateList) {
        log.debug "updateMatchedHosts: ${cloud} ${updateList.size()} ${clusters}"

        def clusterExternalIds = updateList.collect { it.masterItem.clusterUuid }.unique()
        List<CloudPool> zoneClusters = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, null, null).filter {
            it.type == 'Cluster' && it.externalId in clusterExternalIds
        }.toList().blockingGet()
        log.info "clusterExternalIds:${clusterExternalIds} ,zoneClusters: ${zoneClusters}"

        for (update in updateList) {
            ComputeServer currentServer = update.existingItem
            def matchedServer = update.masterItem
            if (currentServer) {
                def save = false
                def clusterObj = zoneClusters?.find { pool -> pool.externalId == matchedServer.clusterUuid }

                if (currentServer.resourcePool?.id != clusterObj.id) {
                    currentServer.resourcePool = new ComputeZonePool(id: clusterObj.id)
                    save = true
                }
                if (currentServer.name != matchedServer.name) {
                    currentServer.name = matchedServer.name
                    save = true
                }

                if (currentServer.externalIp != matchedServer.managementIp) {
                    currentServer.externalIp = matchedServer.managementIp
                    save = true
                }

                if (currentServer?.serverOs?.code != String.format("%s %s", matchedServer.osDistribution, matchedServer.osVersion)) {
                    currentServer.serverOs = new OsType(code: String.format("%s %s", matchedServer.osDistribution, matchedServer.osVersion))
                    save = true
                }

                def hostStatus = matchedServer.status
                def powerState = ComputeServer.PowerState.on
                switch (hostStatus) {
                    case "Connecting":
                        hostStatus = "provisioning"
                        powerState = ComputeServer.PowerState.paused
                        break
                    case "Connected":
                        hostStatus = "provisioned"
                        powerState = ComputeServer.PowerState.on
                        break
                    case "Disconnected":
                        hostStatus = "outline"
                        powerState = ComputeServer.PowerState.off
                        break
                }
                if (currentServer.status != hostStatus) {
                    currentServer.status = hostStatus
                    save = true
                }

                if (currentServer.powerState != powerState) {
                    currentServer.powerState = powerState
                    save = true
                }

                if (currentServer.sshUsername != matchedServer.username) {
                    currentServer.sshUsername = matchedServer.username
                    save = true
                }

                if (currentServer.sshHost != matchedServer.managementIp) {
                    currentServer.sshHost = matchedServer.managementIp
                    save = true
                }

                if (currentServer.usedMemory != (matchedServer.totalMemoryCapacity - matchedServer.availableMemoryCapacity)) {
                    currentServer.usedMemory = (matchedServer.totalMemoryCapacity - matchedServer.availableMemoryCapacity)
                    save = true
                }

                if (currentServer.usedCpu != (matchedServer.totalCpuCapacity - matchedServer.availableCpuCapacity)) {
                    currentServer.usedCpu = (matchedServer.totalCpuCapacity - matchedServer.availableCpuCapacity)
                    save = true
                }

                if (save) {
                    morpheusContext.async.computeServer.bulkSave([currentServer]).blockingGet()
                }
            }
        }
    }

    private removeMissingHosts(Cloud cloud, List removeList) {
        log.debug "removeMissingHosts: ${cloud} ${removeList.size()}"
        morpheusContext.async.computeServer.bulkRemove(removeList).blockingGet()
    }
}
