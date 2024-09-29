package com.morpheusdata.zstack.plugin.sync


import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.OsType
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import com.morpheusdata.model.projection.VirtualImageLocationIdentityProjection
import com.morpheusdata.zstack.plugin.ZstackPlugin
import com.morpheusdata.zstack.plugin.utils.ZStackComputeUtility
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable


@Slf4j
class VirtualImageLocationSync {
    private Cloud cloud
    private MorpheusContext morpheusContext
    private ZstackPlugin plugin
    private HttpApiClient apiClient

    VirtualImageLocationSync(ZstackPlugin zstackPlugin, Cloud cloud, HttpApiClient apiClient) {
        this.plugin = zstackPlugin
        this.cloud = cloud
        this.morpheusContext = zstackPlugin.morpheus
        this.apiClient = apiClient
    }


    def execute() {
        try {
            log.info "Execute VirtualImageLocationSync STARTED: ${cloud.id}"
            def authConfig = plugin.getAuthConfig(cloud)
            def cloudItems = ZStackComputeUtility.listImages(apiClient, authConfig).results.inventories
            log.info("VirtualImage found: $cloudItems")

            Observable domainRecords = morpheusContext.async.virtualImage.location.listIdentityProjections(new DataQuery().withFilters([
                    new DataFilter("refType", "ComputeZone"),
                    new DataFilter("refId", cloud.id)
            ]))

            Observable logRecords = morpheusContext.async.virtualImage.location.listIdentityProjections(new DataQuery().withFilters([
                    new DataFilter("refType", "ComputeZone"),
                    new DataFilter("refId", cloud.id)
            ]))
            logRecords.blockingForEach { log.info("Existing Location: ${it.imageName}($it.externalId})") }

            //domainRecords.each { record -> log.debug("Domain Record found: ${record.subscribe()}") }
            SyncTask<VirtualImageLocationIdentityProjection, Map, VirtualImageLocation> syncTask = new SyncTask<>(domainRecords, cloudItems)

            syncTask.addMatchFunction { VirtualImageLocationIdentityProjection domainObject, Map cloudItem ->
                domainObject.externalId == cloudItem.uuid
            }.onAdd { List<Map> newItems ->
                addMissingVirtualImageLocations(newItems)
            }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<VirtualImageLocationIdentityProjection, VirtualImageLocation>> updateItems ->
                morpheusContext.async.virtualImage.location.listById(updateItems.collect {
                    it.existingItem.id
                } as List<Long>)
            }.onUpdate { List<SyncTask.UpdateItem<VirtualImageLocation, Map>> updateItems ->
                updateMatchedVirtualImageLocations(updateItems)
            }.onDelete { removeItems ->
                removeMissingVirtualImageLocations(removeItems)
            }.start()

        } catch (e) {
            log.error("Error in VirtualImageSync execute : ${e}", e)
        }
        log.debug("Execute VirtualImageSync COMPLETED: ${cloud.id}")
    }


    private addMissingVirtualImageLocations(Collection<Map> objList) {
        log.info "addMissingVirtualImageLocations: ${objList?.size()}"

        Observable domainRecords = morpheusContext.async.virtualImage.listIdentityProjections(new DataQuery().withFilters([
                new DataFilter<String>("category", "zstack.image"),
                new DataOrFilter(
                        new DataFilter<Boolean>("systemImage", false),
                        new DataOrFilter(
                                new DataFilter("owner", null),
                                new DataFilter<Long>("owner.id", cloud.owner.id)
                        )
                )
        ]))

        SyncTask<VirtualImageIdentityProjection, Map, VirtualImage> syncTask = new SyncTask<>(domainRecords, objList)
        syncTask.addMatchFunction { VirtualImageIdentityProjection domainObject, Map cloudItem ->
            domainObject.externalId && (domainObject.externalId == cloudItem.vmid.toString())
        }.addMatchFunction { VirtualImageIdentityProjection domainObject, Map cloudItem ->
            !domainObject.externalId && (domainObject.externalId == cloudItem.uuid)
        }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map>> updateItems ->
            Map<Long, SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map>> updateItemMap = updateItems.collectEntries {
                [(it.existingItem.id): it]
            }
            morpheusContext.async.virtualImage.listById(updateItems?.collect {
                it.existingItem.id
            }).map { VirtualImage virtualImage ->
                SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map> matchItem = updateItemMap[virtualImage.id]
                return new SyncTask.UpdateItem<VirtualImage, Map>(existingItem: virtualImage, masterItem: matchItem.masterItem)
            }
        }.onAdd { itemsToAdd ->
            addMissingVirtualImages(itemsToAdd)
        }.onUpdate { List<SyncTask.UpdateItem<VirtualImage, Map>> updateItems ->
            // Found the VirtualImage for this location.. just need to create the location
            addMissingVirtualImageLocationsForImages(updateItems)
        }.onDelete { itemsToRemove ->
            // Nothing to do....
        }.start()
    }


    private addMissingVirtualImages(Collection<Map> addList) {
        log.info "addMissingVirtualImages ${addList?.size()}"

        def adds = []
        addList.each {
            log.info("Creating virtual image: $it")
            VirtualImage virtImg = new VirtualImage(buildVirtualImageConfig(it))
            VirtualImageLocation virtImgLoc = new VirtualImageLocation(buildLocationConfig(virtImg))
            virtImg.imageLocations = [virtImgLoc]
            adds << virtImg
        }

        log.info "About to create ${adds.size()} virtualImages"
        morpheusContext.async.virtualImage.create(adds, cloud).blockingGet()
    }


    private addMissingVirtualImageLocationsForImages(List<SyncTask.UpdateItem<VirtualImage, Map>> addItems) {
        log.debug "addMissingVirtualImageLocationsForImages ${addItems?.size()}"

        def locationAdds = []
        addItems?.each { add ->
            VirtualImage virtualImage = add.existingItem
            VirtualImageLocation location = new VirtualImageLocation(buildLocationConfig(virtualImage))
            locationAdds << location
        }

        if (locationAdds) {
            log.debug "About to create ${locationAdds.size()} locations"
            morpheusContext.async.virtualImage.location.create(locationAdds, cloud).blockingGet()
        }
    }


    private updateMatchedVirtualImageLocations(List<SyncTask.UpdateItem<VirtualImageLocation, Map>> updateList) {
        log.debug "updateMatchedVirtualImages: $cloud.name($cloud.id) ${updateList.size()} images"
        def saveLocationList = []
        def saveImageList = []
        def virtualImagesById = morpheusContext.async.virtualImage.listById(updateList.collect {
            it.existingItem.virtualImage.id
        }).toMap { it.id }.blockingGet()

        for (def updateItem in updateList) {
            def existingItem = updateItem.existingItem
            def virtualImage = virtualImagesById[existingItem.virtualImage.id]
            def cloudItem = updateItem.masterItem
            def virtualImageConfig = buildVirtualImageConfig(cloudItem)
            def save = false
            def saveImage = false
            def state = 'Active'

            def imageName = virtualImageConfig.name
            log.info("updateMatchedVirtualImageLocations Existing Name: $existingItem.imageName, New Name: $imageName")
            if (existingItem.imageName != imageName) {
                existingItem.imageName = imageName

                //?? What is this for?
                //if(virtualImage.imageLocations?.size() < 2) {
                virtualImage.name = imageName
                saveImage = true
                //}
                save = true
            }
            if (existingItem.externalId != virtualImageConfig.externalId) {
                existingItem.externalId = virtualImageConfig.externalId
                save = true
            }
            if (virtualImage.status != state) {
                virtualImage.status = state
                saveImageList << virtualImage
            }
            if (existingItem.imageRegion != cloud.regionCode) {
                existingItem.imageRegion = cloud.regionCode
                save = true
            }
            if (virtualImage.remotePath != virtualImageConfig.remotePath) {
                virtualImage.remotePath = virtualImageConfig.remotePath
                saveImage = true
            }
            if (virtualImage.imageRegion != virtualImageConfig.imageRegion) {
                virtualImage.imageRegion = virtualImageConfig.imageRegion
                saveImage = true
            }
            if (virtualImage.minDisk != virtualImageConfig.minDisk) {
                virtualImage.minDisk = virtualImageConfig.minDisk as Long
                saveImage = true
            }
            if (virtualImage.bucketId != virtualImageConfig.bucketId) {
                virtualImage.bucketId = virtualImageConfig.bucketId
                saveImage = true
            }
            if (virtualImage.systemImage == null) {
                virtualImage.systemImage = false
                saveImage = true
            }

            if (save) {
                saveLocationList << existingItem
            }

            if (saveImage) {
                saveImageList << virtualImage
            }
        }

        if (saveLocationList) {
            morpheusContext.async.virtualImage.location.save(saveLocationList, cloud).blockingGet()
        }
        if (saveImageList) {
            morpheusContext.async.virtualImage.save(saveImageList.unique(), cloud).blockingGet()
        }
    }


    private Map buildLocationConfig(VirtualImage image) {
        return [
                virtualImage: image,
                code        : "zstack.ve.image.${cloud.id}.${image.externalId}",
                internalId  : image.internalId,
                externalId  : image.externalId,
                imageName   : image.name,
                imageRegion : cloud.regionCode,
                isPublic    : false,
                refType     : 'ComputeZone',
                refId       : cloud.id
        ]
    }


    private buildVirtualImageConfig(Map cloudItem) {
        def imageConfig = [
                account    : cloud.account,
                category   : "zstack.image",
                name       : cloudItem.name,
                code       : "zstack.image.${cloudItem.uuid}",
                imageType  : ImageType.valueOf(cloudItem.format),
                status     : 'Active',
                minDisk    : cloudItem.size,
                isPublic   : false,
                externalId : cloudItem.uuid,
                internalId : cloudItem.uuid,
                imageRegion: cloud.regionCode,
                systemImage: false,
                refType    : 'ComputeZone',
                refId      : "${cloud.id}",
                bucketId   : cloudItem.backupStorageRefs.getAt(0).backupStorageUuid,
                osType     : (cloudItem.platform.toLowerCase() == PlatformType.windows ? new OsType(code: 'windows') : new OsType(code: 'linux')) ?: new OsType(code: 'other.64')
        ]

        return imageConfig
    }

    private removeMissingVirtualImageLocations(List<VirtualImageLocationIdentityProjection> removeList) {
        log.debug "removeMissingVirtualImageLocations: ${removeList?.size()}"
        def virtualImagesById = morpheusContext.async.virtualImage.listById(removeList.collect {
            it.virtualImage.id
        }).toMap {
            it.id
        }.blockingGet()
        log.info("VirtualImages: " + virtualImagesById.toString())

        def removeVirtualImages = []

        removeList.each { removeItem ->
            log.info("removeList VirtualImage ID is: $removeItem.virtualImage.id")
            def virtualImage = virtualImagesById[removeItem.virtualImage.id]
            if (virtualImage.imageLocations.size() == 1 && !virtualImage.systemImage) {
                removeVirtualImages << virtualImage
            }
        }
        log.info("Removing Locations: $removeList")
        morpheusContext.async.virtualImage.location.bulkRemove(removeList).blockingGet()
        //removeVirtualImages.each {
        log.info("Removing Virtual Images: $removeVirtualImages")
        morpheusContext.async.virtualImage.bulkRemove(removeVirtualImages).blockingGet()
        //}
    }


    public clean() {

        Observable domainRecords = morpheusContext.async.virtualImage.list(new DataQuery().withFilters([
                new DataFilter<String>("category", "zstack.image"),
                new DataOrFilter(
                        new DataFilter<Boolean>("systemImage", false),
                        new DataOrFilter(
                                new DataFilter("owner", null),
                                new DataFilter<Long>("owner.id", cloud.owner.id)
                        )
                )
        ]))

        Collection<VirtualImageIdentityProjection> imagesToDelete = []
        domainRecords.blockingIterable().each { virtualImage ->
            if (virtualImage.imageLocations.size() == 1 && !virtualImage.systemImage) {
                log.info("Virtual Image To Remove: $virtualImage.name($virtualImage.externalId)")
                imagesToDelete << virtualImage
            }
        }

        morpheusContext.async.virtualImage.bulkRemove(imagesToDelete).blockingGet()
    }
}
