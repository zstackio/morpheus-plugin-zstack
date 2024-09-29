package com.morpheusdata.zstack.plugin

import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.zstack.plugin.utils.ZStackComputeUtility
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class ZstackOptionSourceProvider extends AbstractOptionSourceProvider {

    ZstackPlugin plugin
    MorpheusContext morpheusContext

    ZstackOptionSourceProvider(ZstackPlugin plugin, MorpheusContext context) {
        this.plugin = plugin
        this.morpheusContext = context
    }

    @Override
    MorpheusContext getMorpheus() {
        return this.morpheusContext
    }

    @Override
    Plugin getPlugin() {
        return this.plugin
    }

    @Override
    String getCode() {
        return 'zstack-option-source-plugin'
    }

    @Override
    String getName() {
        return 'ZStack Option Source Plugin'
    }

    @Override
    List<String> getMethodNames() {
        return new ArrayList<String>(['zstackPluginZones', 'zstackProvisionImage'])
    }

    /**
     * Load/create a cloud with credentials and auth info set on it.. overlay any arg config
     * @param args
     * @return
     */
    private Cloud loadLookupZone(args) {
        log.debug "loadLookupZone: $args"
        Cloud tmpCloud = new Cloud()
        try {
            def cloudArgs = args?.size() > 0 ? args.getAt(0) : null
            if (cloudArgs?.zone) {
                // Case when changes are made in the config dialog
                tmpCloud.serviceUrl = cloudArgs.zone.serviceUrl
                tmpCloud.serviceUsername = cloudArgs.zone.serviceUsername
                tmpCloud.servicePassword = cloudArgs.zone.servicePassword
                if (tmpCloud.servicePassword == '************' && cloudArgs?.zoneId?.toLong()) {
                    def cloud = morpheusContext.cloud.getCloudById(cloudArgs?.zoneId?.toLong()).blockingGet()
                    tmpCloud.servicePassword = cloud.servicePassword
                }
                tmpCloud.setConfigProperty('domainId', cloudArgs.config?.domainId)

                Map credentialConfig = morpheusContext.accountCredential.loadCredentialConfig(cloudArgs?.credential, cloudArgs.zone).blockingGet()
                tmpCloud.accountCredentialLoaded = true
                tmpCloud.accountCredentialData = credentialConfig?.data
            } else {
                // Case when the config dialog opens without any changes
                def zoneId = cloudArgs?.zoneId?.toLong()
                if (zoneId) {
                    log.debug "using zoneId: ${zoneId}"
                    tmpCloud = morpheusContext.cloud.getCloudById(zoneId).blockingGet()

                    // Load the credential for the cloud
                    def authData = plugin.getAuthConfig(tmpCloud)
                    def username = authData.username
                    def password = authData.password
                    tmpCloud.accountCredentialData = null // force the user of serviceUsername / servicePassword
                    tmpCloud.serviceUsername = username
                    tmpCloud.servicePassword = password

                    // Overlay any settings passed in
                    if (cloudArgs.zone?.serviceUrl)
                        tmpCloud.serviceUrl = cloudArgs.zone?.serviceUrl

                    if (cloudArgs.zone?.serviceUsername)
                        tmpCloud.serviceUsername = cloudArgs.zone?.serviceUsername

                    if (cloudArgs.zone?.password && cloudArgs.zone.password != MorpheusUtils.passwordHidden)
                        tmpCloud.servicePassword = cloudArgs.zone.servicePassword

                    if (cloudArgs.config?.domainId)
                        tmpCloud.setConfigProperty('domainId', cloudArgs.config?.domainId)
                }
            }
        } catch (e) {
            log.error "Error in loadLookupZone: ${e}", e
        }
        tmpCloud
    }

    def zstackPluginZones(args) {
        log.info("zstackPluginZones: ${args}")
        HttpApiClient client

        def rtn = []
        try {
            Cloud cloud = loadLookupZone(args)
            def cloudArgs = args?.size() > 0 ? args.getAt(0) : null
            NetworkProxy proxySettings = cloud.apiProxy
            client = new HttpApiClient()
            client.networkProxy = proxySettings

            if (cloud.serviceUrl && cloud.serviceUsername && cloud.servicePassword) {
                def authConfig = plugin.getAuthConfig(cloud)
                def zoneListResults = ZStackComputeUtility.listZones(client, authConfig)
                if (zoneListResults.success && !zoneListResults.error?.size()) {
                    def tmpProjects = []
                    ZStackComputeUtility.InventoriesResult<ZStackComputeUtility.ZoneInventory> result = zoneListResults.results
                    for (ZStackComputeUtility.ZoneInventory zoneInventory : result.inventories) {
                        tmpProjects << [name: zoneInventory.name, value: zoneInventory.uuid]
                    }
                    tmpProjects = tmpProjects.sort { a, b -> a.name?.toLowerCase() <=> b.name?.toLowerCase() }
                    rtn += tmpProjects
                }
            }
        } catch (Exception e) {
            log.info("Error loading zone for zstack cloud create form: {}", e)
        } finally {
            if (client) {
                client.shutdownClient()
            }
        }

        return rtn += [[name: 'ALL_Zone', value: "all_zone"]]
    }


    def zstackProvisionImage(args) {
        log.info "zstackProvisionImage: ${args}"
        def cloudId = args?.size() > 0 ? args.getAt(0).zoneId.toLong() : null
        def accountId = args?.size() > 0 ? args.getAt(0).accountId.toLong() : null
        Cloud tmpCloud = morpheusContext.async.cloud.get(cloudId).blockingGet()

        def options = []

        def poolListProjections = morpheusContext.async.virtualImage.listIdentityProjections(new DataQuery().withFilters([
                new DataFilter<String>("category", "zstack.image"),
                new DataOrFilter(
                        new DataFilter<Boolean>("systemImage", false),
                        new DataOrFilter(
                                new DataFilter("owner", null),
                                new DataFilter<Long>("owner.id", tmpCloud.owner.id)
                        )
                )
        ])).toList().blockingGet()

        options = morpheusContext.async.virtualImage.listById(poolListProjections.collect {
            it.id
        }).map {[name: it.name, value: it.externalId]}.toList().blockingGet()

        log.info "zstackProvisionImage options : ${options}"
        return options
    }
}
