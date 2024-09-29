package com.morpheusdata.zstack.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.projection.CloudPoolIdentity
import com.morpheusdata.zstack.plugin.ZstackPlugin
import com.morpheusdata.zstack.plugin.utils.ZStackComputeUtility
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable


@Slf4j
class ClustersSync {

    private Cloud cloud
    private MorpheusContext morpheusContext
    private ZstackPlugin plugin
    private HttpApiClient apiClient

    public ClustersSync(ZstackPlugin zstackPlugin, Cloud cloud, HttpApiClient apiClient) {
        this.plugin = zstackPlugin
        this.cloud = cloud
        this.morpheusContext = zstackPlugin.morpheus
        this.apiClient = apiClient
    }

    def execute() {
        log.info "BEGIN: execute ClustersSync: ${cloud.id}"
        try {
            def authConfig = plugin.getAuthConfig(cloud)
            def listResults = ZStackComputeUtility.listClusters(apiClient, authConfig)
            if (listResults.success) {
                Observable<CloudPoolIdentity> domainRecords = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, "zstack.cluster.${cloud.id}", null)
                ZStackComputeUtility.InventoriesResult<ZStackComputeUtility.ClusterInventory> result = listResults.results
                List<ZStackComputeUtility.ClusterInventory> clusterInventoryList = result.inventories

                SyncTask<CloudPoolIdentity, Map, CloudPool> syncTask = new SyncTask<>(domainRecords, clusterInventoryList as Collection<Map>)
                log.info "clusterInventoryList ${syncTask}"
                syncTask.addMatchFunction { domainObject, apiItem ->
                    log.info "externalId ${domainObject.externalId}"
                    log.info "apiItem ${apiItem}"
                    log.info "xxxxxxxxxxxxxx ${(domainObject.externalId == apiItem.uuid)}"
                    (domainObject.externalId == apiItem.uuid)
                }.onDelete { removeItems ->
                    removeMissingResourcePools(removeItems)
                }.onUpdate { updateItems ->
                    updateMatchedResourcePools(updateItems)
                }.onAdd { itemsToAdd ->
                    log.info "itemsToAdd ${itemsToAdd}"
                    addMissingResourcePools(itemsToAdd)
                }.withLoadObjectDetails { updateItems ->
                    log.info "updateItems ${updateItems}"
                    Map<Long, SyncTask.UpdateItemDto<CloudPoolIdentity, Map>> updateItemMap = updateItems.collectEntries {
                        [(it.existingItem.id): it]
                    }

                    updateItemMap.each { key, value ->
                        log.info "Map Key (ExistingItem ID): ${key}"
                        log.info "Map Value (UpdateItemDto): ${value}"
                        log.info "Map Value (masterItem): ${value.masterItem}"

                    }

                    morpheusContext.async.cloud.pool.listById(updateItems.collect {
                        it.existingItem.id
                    } as List<Long>).map { CloudPool cloudPool ->
                        SyncTask.UpdateItemDto<CloudPool, Map> matchItem = updateItemMap[cloudPool.id]
                        return new SyncTask.UpdateItem<CloudPool, Map>(existingItem: cloudPool, masterItem: matchItem.masterItem)
                    }
                }.start()
            }
        } catch (e) {
            log.info "Error in execute : ${e}", e
        }
        log.info "END: execute ClustersSync: ${cloud.id}"
    }

    def addMissingResourcePools(List addList) {
        log.info "addMissingResourcePools ${cloud} ${addList.size()}"
        def adds = []

        for (cloudItem in addList) {
            def poolConfig = [
                    owner     : cloud.owner,
                    type      : 'Cluster',
                    name      : cloudItem.name,
                    externalId: cloudItem.uuid,
                    uniqueId  : cloudItem.uuid,
                    internalId: cloudItem.uuid,
                    rawData   : cloudItem.toString(),
                    refType   : 'ComputeZone',
                    refId     : cloud.id,
                    cloud     : cloud,
                    category  : "zstack.cluster.${cloud.id}",
                    code      : "zstack.cluster.${cloud.id}.${cloudItem.uuid}",
                    readOnly  : true,
                    active    : cloud.defaultPoolSyncActive
            ]

            def add = new CloudPool(poolConfig)
            adds << add
        }

        if (adds) {
            morpheusContext.async.cloud.pool.bulkCreate(adds).blockingGet()
        }
    }

    private updateMatchedResourcePools(List updateList) {
        log.info "updateList: ${updateList}"

        def updates = []

        for (update in updateList) {
            def matchItem = update.masterItem
            def existing = update.existingItem
            log.info "matchItem: ${matchItem}"
            log.info "existing name: ${existing.name}"
            log.info "existing externalId: ${existing.externalId}"
            log.info "existing displayName: ${existing.displayName}"


            Boolean save = false

            if (existing.name != matchItem.name) {
                existing.name = matchItem.name
                save = true
            }
            if (save) {
                updates << existing
            }
        }
        if (updates) {
            morpheusContext.async.cloud.pool.bulkSave(updates).blockingGet()
        }
    }

    private removeMissingResourcePools(List<CloudPoolIdentity> removeList) {
        log.debug "removeMissingResourcePools: ${removeList?.size()}"
        morpheusContext.async.cloud.pool.bulkRemove(removeList).blockingGet()
    }
}
