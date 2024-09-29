package com.morpheusdata.zstack.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.zstack.plugin.ZstackPlugin
import com.morpheusdata.zstack.plugin.utils.ZStackComputeUtility
import groovy.util.logging.Slf4j

@Slf4j
class VirtualMachinesSync {

    private Cloud cloud
    private MorpheusContext morpheusContext
    private ZstackPlugin plugin
    private HttpApiClient apiClient
    private ComputeServerInterfaceType nicType

    public VirtualMachinesSync(ZstackPlugin zstackPlugin, Cloud cloud, HttpApiClient apiClient) {
        this.plugin = zstackPlugin
        this.cloud = cloud
        this.morpheusContext = zstackPlugin.morpheus
        this.apiClient = apiClient
        this.nicType = new ComputeServerInterfaceType([
                code        : 'zstack-normal-nic',
                externalId  : 'NORMAL_NIC',
                name        : 'ZStack VNIC',
                defaultType : true,
                enabled     : true,
                displayOrder: 1
        ])
    }

    def execute() {
        log.info "BEGIN: execute VirtualMachinesSync: ${cloud.id}"
        def rtn = [success: false]
        def startTime = new Date().time
        try {
            def authConfig = plugin.getAuthConfig(cloud)
            def listResults = ZStackComputeUtility.listVirtualMachines(apiClient, authConfig)
            if (listResults.success == true) {

                def domainRecords = morpheusContext.async.computeServer.listIdentityProjections(cloud.id, null).filter { ComputeServerIdentityProjection projection ->
                    projection.computeServerTypeCode != 'zstack-hypervisor'
                }

                Map hosts = getAllHosts()
                Map clusters = getAllClusters()
                Map networks = getAllNetworks()
                Map psMap = getAllPss()
                SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(domainRecords, listResults.results.inventories)
                syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
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
                    addMissingVirtualMachines(cloud, hosts, clusters, networks, itemsToAdd, psMap)
                }.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
                    updateMatchedVirtualMachines(cloud, hosts, clusters, networks, updateItems, psMap)
                }.onDelete { removeItems ->
                    removeMissingVirtualMachines(cloud, removeItems)
                }.start()
            }
        } catch (e) {
            log.info "VirtualMachinesSync error in execute : ${e}", e
        }
        def endTime = new Date().time
        log.info "END: execute VirtualMachinesSync: ${cloud.id} in ${endTime - startTime} ms"
    }

    private Map getAllHosts() {
        def hostIdentitiesMap = morpheusContext.async.computeServer.listIdentityProjections(cloud.id, null).filter {
            it.computeServerTypeCode == 'zstack-hypervisor'
        }.toMap { it.externalId }.blockingGet()
        log.info "getAllHosts: ${hostIdentitiesMap}"
        hostIdentitiesMap
    }

    private Map getAllClusters() {
        def resourcePoolProjectionIds = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, '', null).filter { poolProjection ->
            return poolProjection.internalId != null && poolProjection.type == 'Cluster'
        }.map { it.id }.toList().blockingGet()
        def resourcePoolsMap = morpheusContext.async.cloud.pool.listById(resourcePoolProjectionIds).toMap {
            it.externalId
        }.blockingGet()
        log.info "getClusters: ${resourcePoolsMap}"
        resourcePoolsMap
    }

    private Map getAllNetworks() {
        def resourcePoolProjectionIds = morpheusContext.async.cloud.network.listIdentityProjections(cloud.id).map {
            it.id
        }.toList().blockingGet()

        def networkProjectionsMap = morpheusContext.async.cloud.network.listById(resourcePoolProjectionIds).toMap {
            it.externalId
        }.blockingGet()
        log.info "getAllNetworks: ${networkProjectionsMap}"
        networkProjectionsMap
    }

    private Map getAllPss() {
        def psMap = morpheusContext.async.cloud.datastore.list(new DataQuery().withFilters(
                new DataFilter('refId', cloud.id),
                new DataFilter('refType', "ComputeZone")
        )).toMap {
            it.externalId
        }.blockingGet()
        log.info "getAllPss: ${psMap}"
        psMap
    }

    private buildVmConfig(Map cloudItem, Map clusters, Map hosts) {
        CloudPool resourcePool = clusters[cloudItem?.clusterUuid]
        def ipAddress = cloudItem.vmNics?.getAt(0)?.ip
        def vmConfig = [
                account     : cloud.account,
                externalId  : cloudItem.uuid,
                name        : cloudItem.name,
                externalIp  : ipAddress,
                internalIp  : ipAddress,
                sshHost     : ipAddress,
                provision   : false,
                cloud       : cloud,
                lvmEnabled  : false,
                managed     : false,
                discovered  : true,
                serverType  : 'unmanaged',
                resourcePool: resourcePool,
                maxCpu      : cloudItem.cpuNum,
                maxMemory   : cloudItem.memorySize,
                usedCpu     : cloudItem.cpuNum,
                usedMemory  : cloudItem.memorySize,
                uniqueId    : cloudItem.uuid,
                internalId  : cloudItem.uuid,
                powerState  : cloudItem.state == 'Running' ? ComputeServer.PowerState.on : ComputeServer.PowerState.off,
                parentServer: cloudItem.hostUuid ? hosts[cloudItem.hostUuid] : null,
                osType      : (cloudItem.platform.toLowerCase() == PlatformType.windows ? 'windows' : 'linux') ?: virtualImage?.platform,
                serverOs    : new OsType(code: 'unknown')
        ]
        vmConfig
    }


    private addMissingVirtualMachines(Cloud cloud, Map hosts, Map clusters, Map networks, List addList, Map pss) {
        log.info "addMissingVirtualMachines: ${cloud} ${addList.size()}"
        def doInventory = cloud.getConfigProperty('importExisting')
        if (!(doInventory == 'on' || doInventory == 'true' || doInventory == true)) {
            log.info "do not import Existing: ${doInventory}"
            return
        }
        for (cloudItem in addList) {
            try {
                def vmConfig = buildVmConfig(cloudItem, clusters, hosts)
                ComputeServer add = new ComputeServer(vmConfig)
                add.computeServerType = new ComputeServerType(code: 'zstack-other-vm')
                switch (cloudItem.platform) {
                    case "Windows":
                        add.computeServerType = new ComputeServerType(code: 'zstack-windows-vm')
                        break
                    case "Linux":
                        add.computeServerType = new ComputeServerType(code: 'zstack-vm')
                        break
                }
                add.status = "provisioned"
                ComputeServer savedServer = morpheusContext.async.computeServer.create(add).blockingGet()

                // ps
                def syncResults = syncVolumes(savedServer, cloudItem.allVolumes, pss)
                log.info "volumeSync result: ${syncResults}"
                savedServer.capacityInfo = new ComputeCapacityInfo(maxCores: cloudItem.cpuNum, maxMemory: cloudItem.memorySize, maxStorage: syncResults.maxStorage)
                // Networks
                def interfaceChanges = syncInterfaces(savedServer, cloudItem.vmNics, networks)
                morpheusContext.async.computeServer.bulkSave([savedServer]).blockingGet()
                log.info "interfaceChanges result: ${interfaceChanges}"

                log.info "success add vm: ${savedServer}"

            } catch (e) {
                log.info "Error in creating vm: ${e}", e
            }
        }
    }

    private syncInterfaces(ComputeServer server, List cloudNics, Map networks) {
        log.info "syncInterfaces: ${server} | ${cloudNics}"
        def rtn = false
        try {

            def existingInterfaces = server.interfaces
            def masterInterfaces = cloudNics

            def matchFunction = { morpheusItem, Map cloudItem ->
                morpheusItem.externalId == cloudItem.uuid
            }
            def syncLists = buildSyncLists(existingInterfaces, masterInterfaces, matchFunction)

            // Update existing ones
            def saveList = []
            syncLists.updateList?.each { updateItem ->
                def masterInterface = updateItem.masterItem
                ComputeServerInterface existingInterface = updateItem.existingItem

                def save = false
                if (existingInterface.primaryInterface != (masterInterface.deviceId == 0)) {
                    existingInterface.primaryInterface = (masterInterface.deviceId == 0)
                    save = true
                }

                def network = networks[masterInterface.l3NetworkUuid]
                if (existingInterface.network?.id != network?.id) {
                    existingInterface.network = network
                    save = true
                }

                def ipAddress = masterInterface.ip
                if (existingInterface.ipAddress != ipAddress) {
                    existingInterface.ipAddress = ipAddress
                    save = true
                }

                def macAddress = masterInterface.mac
                if (existingInterface.macAddress != macAddress) {
                    existingInterface.macAddress = macAddress
                    save = true
                }


                if (existingInterface.displayOrder != masterInterface.deviceId) {
                    existingInterface.displayOrder = masterInterface.deviceId
                    save = true
                }

                if (existingInterface.name != masterInterface.internalName) {
                    existingInterface.name = masterInterface.internalName
                    save = true
                }

                if (save) {
                    saveList << existingInterface
                }
            }
            if (saveList?.size() > 0) {
                morpheusContext.async.computeServer.computeServerInterface.save(saveList).blockingGet()
                rtn = true
            }

            // Remove old ones
            if (syncLists.removeList?.size() > 0) {
                morpheusContext.async.computeServer.computeServerInterface.remove(syncLists.removeList, server).blockingGet()
                rtn = true
            }

            // Add new ones
            def createList = []
            syncLists.addList?.each { addItem ->
                Network networkProj = networks[addItem.l3NetworkUuid]
                def newInterface = new ComputeServerInterface(
                        externalId: addItem.uuid,
                        macAddress: addItem.mac,
                        name: addItem.internalName,
                        type: nicType,
                        ipAddress: addItem.ip,
                        network: networkProj,
                        displayOrder: addItem.deviceId,
                )
                if (newInterface.displayOrder == 0) {
                    newInterface.primaryInterface = true
                } else {
                    newInterface.primaryInterface = false
                }
                def addresses = []
                addItem.usedIps?.each { userIp ->
                    addresses << [address: userIp.ip, type: NetAddress.AddressType.IPV4]
                }
                addresses?.each { addr ->
                    newInterface.addresses << new NetAddress(addr)
                }
                createList << newInterface
            }
            if (createList?.size() > 0) {
                log.info "Add new network: ${createList}"
                morpheusContext.async.computeServer.computeServerInterface.create(createList, server).blockingGet()
                log.info "after new network: ${createList}"
                rtn = true
            }
        } catch (e) {
            log.info("syncInterfaces error: ${e}", e)
        }
        return rtn
    }


    static buildSyncLists(existingItems, masterItems, matchExistingToMasterFunc) {
        log.info "buildSyncLists: ${existingItems}, ${masterItems}"
        def rtn = [addList: [], updateList: [], removeList: []]
        try {
            existingItems?.each { existing ->
                def matches = masterItems?.findAll { matchExistingToMasterFunc(existing, it) }
                if (matches?.size() > 0) {
                    matches?.each { match ->
                        rtn.updateList << [existingItem: existing, masterItem: match]
                    }
                } else {
                    rtn.removeList << existing
                }
            }
            masterItems?.each { masterItem ->
                def match = rtn?.updateList?.find {
                    it.masterItem == masterItem
                }
                if (!match) {
                    rtn.addList << masterItem
                }
            }
        } catch (e) {
            log.info "buildSyncLists error: ${e}", e
        }
        return rtn
    }


    private syncVolumes(ComputeServer locationOrServer, ArrayList externalVolumes, Map psMap) {
        def serverVolumes = locationOrServer.volumes?.sort { it.displayOrder }
        def rtn = [changed: false, maxStorage: 0l]

        try {
            def matchFunction = { morpheusItem, Map cloudItem ->
                (morpheusItem.externalId && morpheusItem.externalId == "${cloudItem.uuid}".toString())
            }

            def syncLists = buildSyncLists(serverVolumes, externalVolumes, matchFunction)
            def saveList = []
            syncLists.updateList?.each { updateMap ->
                log.info "syncVolumes Updating ${updateMap}"
                StorageVolume existingVolume = updateMap.existingItem
                def volume = updateMap.masterItem
                volume.maxStorage = volume.actualSize ?: 0l
                rtn.maxStorage += volume.maxStorage
                def save = false
                if (existingVolume.maxStorage != volume.maxStorage) {
                    //update it
                    existingVolume.maxStorage = volume.maxStorage
                    save = true
                }

                if (existingVolume.rootVolume != (volume.type == "Root")) {
                    existingVolume.rootVolume = (volume.type == "Root")
                    save = true
                }

                if (existingVolume.diskIndex != volume.deviceId) {
                    existingVolume.diskIndex = volume.deviceId
                    save = true
                }
                if (existingVolume.datastore?.externalId != volume.primaryStorageUuid) {
                    existingVolume.datastore = psMap[volume.primaryStorageUuid]
                    save = true
                }
                if (save) {
                    saveList << existingVolume
                }
            }

            if (saveList) {
                rtn.changed = true
                log.info "Found ${saveList?.size()} volumes to update"
                morpheusContext.async.storageVolume.bulkSave(saveList).blockingGet()
            }

            // The removes
            if (syncLists.removeList) {
                rtn.changed = true
                if (locationOrServer instanceof ComputeServer) {
                    morpheusContext.async.storageVolume.remove(syncLists.removeList, locationOrServer, false).blockingGet()
                } else {
                    morpheusContext.async.storageVolume.remove(syncLists.removeList, locationOrServer).blockingGet()
                }
            }

            // The adds
            def newVolumes = buildNewStorageVolumes(syncLists.addList, cloud, locationOrServer, psMap)
            if (newVolumes) {
                rtn.changed = true
                newVolumes?.each { rtn.maxStorage += it.maxStorage }
                morpheusContext.async.storageVolume.create(newVolumes, locationOrServer).blockingGet()
                log.info "newVolumes: ${newVolumes}"
            }
        } catch (e) {
            log.info "Error in syncVolumes: ${e}", e
        }
        rtn
    }

    static buildNewStorageVolumes(volumes, cloud, locationOrServer, Map psMap) {
        log.info "buildNewStorageVolumes: ${volumes?.size()} ${locationOrServer} ${psMap}"
        def rtn = []
        def existingVolumes = locationOrServer?.volumes
        def newIndex = existingVolumes?.size() ?: 0

        volumes?.eachWithIndex { volume, index ->
            def volumeConfig = [
                    name        : volume.uuid,
                    account     : cloud.account,
                    maxStorage  : volume.actualSize,
                    usedStorage : volume.size,
                    externalId  : volume.uuid,
                    internalId  : volume.uuid,
                    unitNumber  : volume.deviceId,
                    type        : new StorageVolumeType(code: 'zstack-disk'),
                    datastore   : psMap[volume.primaryStorageUuid],
                    displayOrder: volume.deviceId,
                    rootVolume  : (volume.type == "Root"),
                    removable   : !(volume.type == "Root"),
                    diskIndex   : (index + newIndex),
            ]
            StorageVolume newVolume = new StorageVolume(volumeConfig)
            rtn << newVolume
        }
        return rtn
    }


    private updateMatchedVirtualMachines(Cloud cloud, Map hosts, Map clusters, Map networks, List updateList, Map pss) {
        log.debug "updateMatchedVirtualMachines: ${cloud} ${updateList.size()} ${clusters}"

        def clusterExternalIds = updateList.collect { it.masterItem.clusterUuid }.unique()
        List<CloudPool> zoneClusters = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, null, null).filter {
            it.type == 'Cluster' && it.externalId in clusterExternalIds
        }.toList().blockingGet()
        log.info "updateMatchedVirtualMachines clusterExternalIds:${clusterExternalIds} ,zoneClusters: ${zoneClusters}"

        for (update in updateList) {
            ComputeServer currentServer = update.existingItem
            def cloudItem = update.masterItem
            if (currentServer) {
                def vmConfig = buildVmConfig(cloudItem, clusters, hosts)
                def save = false

                if (currentServer.name != vmConfig.name) {
                    currentServer.name = vmConfig.name
                    save = true
                }

                if (currentServer.externalIp != vmConfig.externalIp) {
                    currentServer.externalIp = vmConfig.externalIp
                    currentServer.internalIp = vmConfig.externalIp
                    currentServer.sshHost = vmConfig.externalIp
                    save = true
                }

                if (currentServer.resourcePool?.id != vmConfig.resourcePool?.id) {
                    currentServer.resourcePool = vmConfig.resourcePool
                    save = true
                }

                if (currentServer.maxMemory != vmConfig.maxMemory) {
                    currentServer.maxMemory = vmConfig.maxMemory
                    currentServer.usedMemory = vmConfig.maxMemory
                    save = true
                }

                if (currentServer.maxCpu != vmConfig.maxCores) {
                    currentServer.maxCpu = vmConfig.maxCores
                    currentServer.usedCpu = vmConfig.maxCores
                    save = true
                }

                if (currentServer.parentServer?.id != vmConfig.parentServer?.id) {
                    currentServer.parentServer = vmConfig.parentServer
                    save = true
                }

                log.info "update powerState result: ${currentServer.powerState}  ${vmConfig.powerState}"
                log.info "update osType result: ${currentServer.osType}  ${vmConfig.osType}"

                if (currentServer.powerState != vmConfig.powerState) {
                    currentServer.powerState = vmConfig.powerState
                    save = true
                    if (currentServer.computeServerType?.guestVm) {
                        morpheusContext.async.computeServer.updatePowerState(currentServer.id, currentServer.powerState).blockingGet()
                    }
                }

                if (save) {
                    morpheusContext.async.computeServer.bulkSave([currentServer]).blockingGet()
                }

                // ps
                def syncResults = syncVolumes(currentServer, cloudItem.allVolumes, pss)
                log.info "update volumeSync result: ${syncResults}"
                currentServer.capacityInfo = new ComputeCapacityInfo(maxCores: cloudItem.cpuNum, maxMemory: cloudItem.memorySize, maxStorage: syncResults.maxStorage)
                // Networks
                def interfaceChanges = syncInterfaces(currentServer, cloudItem.vmNics, networks)
                log.info "update interfaceChanges result: ${interfaceChanges}"
            }
        }
    }

    private removeMissingVirtualMachines(Cloud cloud, List removeList) {
        log.info "removeMissingVirtualMachines: ${cloud} ${removeList.size()}"
        for (ComputeServerIdentityProjection removeItem in removeList) {
            try {
                log.info("remove vm: ${removeItem}")
                morpheusContext.async.computeServer.bulkRemove([removeItem]).blockingGet()
            } catch (e) {
                log.info "Error removing virtual machine: ${e}", e
                log.info("Unable to remove Server from inventory, Perhaps it is associated with an instance currently... ${removeItem.name} - ID: ${removeItem.id}")
            }
        }
    }
}
