package com.morpheusdata.zstack.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.projection.CloudPoolIdentity
import com.morpheusdata.model.projection.DatastoreIdentity
import com.morpheusdata.zstack.plugin.ZstackPlugin
import com.morpheusdata.zstack.plugin.utils.ZStackComputeUtility
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class PrimaryStoragesSync {

    private Cloud cloud
    private MorpheusContext morpheusContext
    private ZstackPlugin plugin
    private HttpApiClient apiClient

    public PrimaryStoragesSync(ZstackPlugin zstackPlugin, Cloud cloud, HttpApiClient apiClient) {
        this.plugin = zstackPlugin
        this.cloud = cloud
        this.morpheusContext = zstackPlugin.morpheus
        this.apiClient = apiClient
    }

    def execute() {
        log.info "BEGIN: execute PrimaryStoragesSync: ${cloud.id}"

        try {
            def authConfig = plugin.getAuthConfig(cloud)

            def listResults = ZStackComputeUtility.listPrimaryStorages(apiClient, authConfig)
            if (listResults.success == true) {

                Observable domainRecords = morpheusContext.async.cloud.datastore.list(new DataQuery().withFilters(
                        new DataFilter('refId', cloud.id),
                        new DataFilter('refType', "ComputeZone")
                ))
                SyncTask<DatastoreIdentity, Map, CloudPool> syncTask = new SyncTask<>(domainRecords, listResults.results.inventories)
                syncTask.addMatchFunction { DatastoreIdentity domainObject, Map cloudItem ->
                    domainObject.externalId == cloudItem?.uuid
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<DatastoreIdentity, Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<DatastoreIdentity, Map>> updateItemMap = updateItems.collectEntries {
                        [(it.existingItem.id): it]
                    }
                    morpheusContext.async.cloud.datastore.listById(updateItems?.collect {
                        it.existingItem.id
                    }).map { Datastore datastore ->
                        SyncTask.UpdateItemDto<DatastoreIdentity, Map> matchItem = updateItemMap[datastore.id]
                        return new SyncTask.UpdateItem<Datastore, Map>(existingItem: datastore, masterItem: matchItem.masterItem)
                    }
                }.onAdd { itemsToAdd ->
                    def adds = []
                    itemsToAdd?.each { cloudItem ->
                        def datastoreConfig = [
                                owner      : new Account(id: cloud.owner.id),
                                name       : cloudItem.name,
                                externalId : cloudItem.uuid,
                                cloud      : cloud,
                                storageSize: cloudItem.totalPhysicalCapacity,
                                freeSpace  : cloudItem.availablePhysicalCapacity,
                                type       : cloudItem.type ?: 'general',
                                category   : "zstack-primary-storage.${cloud.id}",
                                drsEnabled : false,
                                online     : (cloudItem.status == "Connected"),
                                refType    : "ComputeZone",
                                refId      : cloud.id,
                                active     : cloud.defaultDatastoreSyncActive
                        ]
                        Datastore add = new Datastore(datastoreConfig)
                        if (cloudItem.attachedClusterUuids.size() > 0) {
                            def clusterObjs = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, '', null).filter { poolProjection ->
                                return poolProjection.internalId != null && poolProjection.type == 'Cluster' && cloudItem.attachedClusterUuids.contains(poolProjection.externalId)
                            }.toList().blockingGet()
                            clusterObjs.forEach({ cluster ->
                                log.info "clusterObj: ${cluster}"
                                log.info "clusterObj.uuid: ${cluster.externalId}"
                            })
                            add.assignedZonePools = clusterObjs
                        }

                        adds << add

                    }
                    morpheusContext.async.cloud.datastore.bulkCreate(adds).blockingGet()
                }.onUpdate { List<SyncTask.UpdateItem<Datastore, Map>> updateItems ->
                    def updatedItems = []
                    for (item in updateItems) {
                        def masterItem = item.masterItem
                        Datastore existingItem = item.existingItem
                        def save = false

                        if (existingItem.online != (masterItem.status == "Connected")) {
                            existingItem.online = (masterItem.status == "Connected")
                            save = true
                        }

                        if (existingItem.name != masterItem.name) {
                            existingItem.name = masterItem.name
                            save = true
                        }

                        if (existingItem.freeSpace != masterItem.availablePhysicalCapacity) {
                            existingItem.freeSpace = masterItem.availablePhysicalCapacity
                            save = true
                        }

                        if (existingItem.storageSize != masterItem.totalPhysicalCapacity) {
                            existingItem.storageSize = masterItem.totalPhysicalCapacity
                            save = true
                        }

                        if (masterItem.attachedClusterUuids.size() > 0) {
                            def clusterObjs = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, '', null).filter { poolProjection ->
                                return poolProjection.internalId != null && poolProjection.type == 'Cluster' && masterItem.attachedClusterUuids.contains(poolProjection.externalId)
                            }.toList().blockingGet()
                            clusterObjs.forEach({ cluster ->
                                log.info "update clusterObj: ${cluster}"
                                log.info "update clusterObj.uuid: ${cluster.externalId}"
                            })
                            existingItem.assignedZonePools = clusterObjs
                        } else {
                            existingItem.assignedZonePools = []
                            save = true
                        }

                        if (save) {
                            updatedItems << existingItem
                        }
                    }
                    if (updatedItems.size() > 0) {
                        morpheusContext.async.cloud.datastore.bulkSave(updatedItems).blockingGet()
                    }
                }.onDelete { removeItems ->
                    if (removeItems) {
                        morpheusContext.async.cloud.datastore.remove(removeItems, null).blockingGet()
                    }
                }.start()
            }
        } catch (e) {
            log.info "Error in execute of DatastoresSync: ${e}", e
        }
        log.info "END: execute DatastoresSync: ${cloud.id}"
    }
}
