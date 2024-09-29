package com.morpheusdata.zstack.plugin

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.*
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.zstack.plugin.utils.AuthConfig
import com.morpheusdata.zstack.plugin.utils.ZStackComputeUtility
import com.morpheusdata.zstack.plugin.sync.ClustersSync
import com.morpheusdata.zstack.plugin.sync.HostsSync
import com.morpheusdata.zstack.plugin.sync.NetworksSync
import com.morpheusdata.zstack.plugin.sync.PrimaryStoragesSync
import com.morpheusdata.zstack.plugin.sync.VirtualMachinesSync
import com.morpheusdata.zstack.plugin.sync.VirtualImageLocationSync
import groovy.util.logging.Slf4j

@Slf4j
class ZstackCloudProvider implements CloudProvider {
    public static final String CLOUD_PROVIDER_CODE = 'zstack-plugin.cloud'
    static final UNAUTHORIZED_ERROR = 'Unauthorized'


    protected MorpheusContext context
    protected Plugin plugin

    public ZstackCloudProvider(Plugin plugin, MorpheusContext ctx) {
        super()
        this.@plugin = plugin
        this.@context = ctx
    }

    /**
     * Grabs the description for the CloudProvider
     * @return String
     */
    @Override
    String getDescription() {
        return 'ZStack plugin!'
    }

    /**
     * Returns the Cloud logo for display when a user needs to view or add this cloud. SVGs are preferred.
     * @since 0.13.0* @return Icon representation of assets stored in the src/assets of the project.
     */
    @Override
    Icon getIcon() {
        // TODO: change icon paths to correct filenames once added to your project
        return new Icon(path: "zstack.svg", darkPath: "zstack-dark.svg")
    }

    /**
     * Returns the circular Cloud logo for display when a user needs to view or add this cloud. SVGs are preferred.
     * @since 0.13.6* @return Icon
     */
    @Override
    Icon getCircularIcon() {
        return new Icon(path: "zstack-circular.svg", darkPath: "zstack-circular-dark.svg")
    }

    /**
     * Provides a Collection of OptionType inputs that define the required input fields for defining a cloud integration
     * @return Collection of OptionType
     */
    @Override
    Collection<OptionType> getOptionTypes() {
        OptionType apiUrl = new OptionType(
                name: 'Identity Api Url',
                code: 'zstack-plugin-api-url',
                fieldName: 'serviceUrl',
                displayOrder: 0,
                fieldLabel: 'Identity Api Url',
                required: true,
                inputType: OptionType.InputType.TEXT,
                placeHolder: 'https://0.0.0.0:5000',
                fieldContext: 'domain'
        )
        OptionType credentials = new OptionType(
                code: 'zstack-plugin-credential',
                inputType: OptionType.InputType.CREDENTIAL,
                name: 'Credentials',
                fieldName: 'type',
                fieldLabel: 'Credentials',
                fieldContext: 'credential',
                required: true,
                defaultValue: 'local',
                displayOrder: 20,
        )
        OptionType username = new OptionType(
                name: 'Username',
                code: 'zstack-plugin-username',
                fieldName: 'serviceUsername',
                displayOrder: 30,
                fieldLabel: 'Username',
                required: true,
                inputType: OptionType.InputType.TEXT,
                fieldContext: 'domain',
                localCredential: true
        )
        OptionType password = new OptionType(
                name: 'Password',
                code: 'zstack-plugin-password',
                fieldName: 'servicePassword',
                displayOrder: 40,
                fieldLabel: 'Password',
                required: true,
                inputType: OptionType.InputType.PASSWORD,
                fieldContext: 'domain',
                localCredential: true
        )
        OptionType zone = new OptionType(
                name: 'Zone',
                code: 'zstack-plugin-zone',
                fieldName: 'zoneName',
                displayOrder: 50,
                fieldLabel: 'Zone',
                required: false,
                inputType: OptionType.InputType.SELECT,
                fieldContext: 'config',
                dependsOn: 'zstack-plugin-password,zstack-plugin-username,credential-type',
                optionSource: 'zstackPluginZones'
        )

        OptionType inventoryInstances = new OptionType(
                name: 'import existing Vm Instances',
                code: 'zstack-import-existing',
                fieldName: 'importExisting',
                displayOrder: 90,
                fieldLabel: 'import existing Vm Instances',
                required: false,
                inputType: OptionType.InputType.CHECKBOX,
                fieldContext: 'config'
        )

        return [apiUrl, credentials, username, password, zone, inventoryInstances]
    }

    /**
     * Grabs available provisioning providers related to the target Cloud Plugin. Some clouds have multiple provisioning
     * providers or some clouds allow for service based providers on top like (Docker or Kubernetes).
     * @return Collection of ProvisionProvider
     */
    @Override
    Collection<ProvisionProvider> getAvailableProvisionProviders() {
        return this.@plugin.getProvidersByType(ProvisionProvider) as Collection<ProvisionProvider>
    }

    /**
     * Grabs available backup providers related to the target Cloud Plugin.
     * @return Collection of BackupProvider
     */
    @Override
    Collection<BackupProvider> getAvailableBackupProviders() {
        Collection<BackupProvider> providers = []
        return providers
    }

    /**
     * Provides a Collection of {@link NetworkType} related to this CloudProvider
     * @return Collection of NetworkType
     */
    @Override
    Collection<NetworkType> getNetworkTypes() {
        NetworkType privateNetwork = new NetworkType([
                code              : 'zstack-private-network',
                category          : 'zstack',
                name              : 'ZStack Private Network',
                externalType      : 'External',
                description       : '',
                overlay           : false,
                creatable         : true,
                nameEditable      : true,
                cidrEditable      : true,
                dhcpServerEditable: true,
                dnsEditable       : true,
                gatewayEditable   : true,
                vlanIdEditable    : false,
                canAssignPool     : true,
                deletable         : true,
                hasNetworkServer  : true,
                hasCidr           : true,
                cidrRequired      : true,
                optionTypes       : getCommonNetworkOptionTypes()
        ])

        NetworkType publicNetwork = new NetworkType([
                code              : 'zstack-public-network',
                category          : 'zstack',
                name              : 'ZStack public Network',
                externalType      : 'External',
                description       : '',
                overlay           : false,
                creatable         : true,
                nameEditable      : true,
                cidrEditable      : true,
                dhcpServerEditable: true,
                dnsEditable       : true,
                gatewayEditable   : true,
                vlanIdEditable    : false,
                canAssignPool     : true,
                deletable         : true,
                hasNetworkServer  : true,
                hasCidr           : true,
                cidrRequired      : true,
                optionTypes       : getCommonNetworkOptionTypes()
        ])
        NetworkType systemNetwork = new NetworkType([
                code              : 'zstack-system-network',
                category          : 'zstack',
                name              : 'ZStack system Network',
                externalType      : 'External',
                description       : '',
                overlay           : false,
                creatable         : true,
                nameEditable      : true,
                cidrEditable      : true,
                dhcpServerEditable: true,
                dnsEditable       : true,
                gatewayEditable   : true,
                vlanIdEditable    : false,
                canAssignPool     : true,
                deletable         : true,
                hasNetworkServer  : true,
                hasCidr           : true,
                cidrRequired      : true,
                optionTypes       : getCommonNetworkOptionTypes()
        ])
        return [privateNetwork, publicNetwork, systemNetwork]
    }

    private List<OptionType> getCommonNetworkOptionTypes() {
        [
                new OptionType(code: 'zstack.network.cidr', inputType: OptionType.InputType.TEXT, name: 'cidr',
                        category: 'network.global', fieldName: 'cidr', fieldLabel: 'cidr', fieldContext: 'domain', required: true, enabled: true,
                        editable: true, global: false, placeHolder: null, helpBlock: '', defaultValue: null, custom: false, displayOrder: 40, fieldClass: null,
                        wrapperClass: null, fieldCode: 'gomorpheus.infr password = new OptionType(astructure.network.cidr',
                        fieldComponent: 'network', ownerEditable: true, tenantEditable: false),
                new OptionType(code: 'zstack.network.gateway', inputType: OptionType.InputType.TEXT, name: 'gateway',
                        category: 'network.global', fieldName: 'gateway', fieldLabel: 'gateway', fieldContext: 'domain', required: false, enabled: true,
                        editable: true, global: false, placeHolder: null, helpBlock: '', defaultValue: null, custom: false, displayOrder: 15, fieldClass: null,
                        wrapperClass: null, fieldCode: 'gomorpheus.infrastructure.network.gateway',
                        fieldComponent: 'network', ownerEditable: true, tenantEditable: false),
                new OptionType(code: 'zstack.network.dhcpRange', inputType: OptionType.InputType.TEXT, name: 'dhcpRange', showOnEdit: true,
                        category: 'network.global', fieldName: 'dhcpRange', fieldLabel: 'DHCP Allocation', fieldContext: 'config', required: false, enabled: true,
                        editable: true, global: false, placeHolder: 'x.x.x.x,x.x.x.x', helpBlock: '', defaultValue: null, custom: false, displayOrder: 35, fieldClass: null,
                        wrapperClass: null, fieldCode: 'gomorpheus.infrastructure.network.dhcpAllocation'),
                new OptionType(code: 'zstack.network.hostRoutes', inputType: OptionType.InputType.TEXT, name: 'hostRoutes', showOnEdit: true,
                        category: 'network.global', fieldName: 'hostRoutes', fieldCode: 'gomorpheus.optiontype.HostRoutes', fieldLabel: 'Host Routes', fieldContext: 'config', required: false, enabled: true,
                        editable: false, global: false, placeHolder: 'x.x.x.x,x.x.x.x', helpBlock: '', defaultValue: null, custom: false, displayOrder: 40, fieldClass: null,
                        ownerEditable: true, tenantEditable: false)
        ]
    }


    /**
     * Provides a Collection of {@link NetworkSubnetType} related to this CloudProvider
     * @return Collection of NetworkSubnetType
     */
    @Override
    Collection<NetworkSubnetType> getSubnetTypes() {
        Collection<NetworkSubnetType> subnets = []
        return subnets
    }

    /**
     * Provides a Collection of {@link StorageVolumeType} related to this CloudProvider
     * @return Collection of StorageVolumeType
     */
    @Override
    Collection<StorageVolumeType> getStorageVolumeTypes() {
        Collection<StorageVolumeType> volumeTypes = []
        volumeTypes << new StorageVolumeType([
                code: 'zstack-disk',
                name: 'Disk'
        ])
        return volumeTypes
    }

    /**
     * Provides a Collection of {@link StorageControllerType} related to this CloudProvider
     * @return Collection of StorageControllerType
     */
    @Override
    Collection<StorageControllerType> getStorageControllerTypes() {
        Collection<StorageControllerType> controllerTypes = []
        return controllerTypes
    }

    /**
     * Grabs all {@link ComputeServerType} objects that this CloudProvider can represent during a sync or during a provision.
     * @return collection of ComputeServerType
     */
    @Override
    Collection<ComputeServerType> getComputeServerTypes() {
        def hypervisor = new ComputeServerType([
                code               : 'zstack-hypervisor',
                name               : 'ZStack Hypervisor',
                description        : '',
                platform           : PlatformType.linux,
                nodeType           : '',
                enabled            : true,
                selectable         : false,
                externalDelete     : false,
                managed            : false,
                controlPower       : false,
                controlSuspend     : false,
                creatable          : false,
                displayOrder       : 0,
                hasAutomation      : false,
                containerHypervisor: false,
                bareMetalHost      : false,
                vmHypervisor       : true,
                agentType          : ComputeServerType.AgentType.host,
                provisionTypeCode  : 'zstack-provision-provider'])

        ComputeServerType vmType = new ComputeServerType()
        vmType.name = 'ZStack Linux VM'
        vmType.code = 'zstack-vm'
        vmType.description = 'ZStack Linux VM'
        vmType.reconfigureSupported = true
        vmType.hasAutomation = true
        vmType.supportsConsoleKeymap = true
        vmType.platform = PlatformType.linux
        vmType.managed = false
        vmType.guestVm = true
        vmType.enabled = true
        vmType.selectable = false
        vmType.externalDelete = false
        vmType.agentType = ComputeServerType.AgentType.none
        vmType.provisionTypeCode = 'zstack-plugin.provision'
        vmType.nodeType = 'unmanaged'

        ComputeServerType otherType = new ComputeServerType()
        otherType.name = 'ZStack Other VM'
        otherType.code = 'zstack-other-vm'
        otherType.description = 'ZStack Other VM'
        otherType.reconfigureSupported = true
        otherType.hasAutomation = true
        otherType.supportsConsoleKeymap = true
        otherType.platform = PlatformType.other
        otherType.managed = false
        otherType.guestVm = true
        otherType.enabled = true
        otherType.selectable = false
        otherType.externalDelete = false
        otherType.agentType = ComputeServerType.AgentType.none
        otherType.provisionTypeCode = 'zstack-plugin.provision'
        otherType.nodeType = 'morpheus-other-vm-node'

        ComputeServerType windowsType = new ComputeServerType()
        windowsType.name = 'ZStack Windows VM'
        windowsType.code = 'zstack-windows-vm'
        windowsType.description = 'ZStack Windows VM'
        windowsType.reconfigureSupported = true
        windowsType.hasAutomation = true
        windowsType.supportsConsoleKeymap = true
        windowsType.platform = PlatformType.windows
        windowsType.managed = false
        windowsType.guestVm = true
        windowsType.enabled = true
        windowsType.selectable = false
        windowsType.externalDelete = false
        windowsType.agentType = ComputeServerType.AgentType.none
        windowsType.provisionTypeCode = 'zstack-plugin.provision'
        windowsType.nodeType = 'morpheus-windows-vm-node'

        [hypervisor, vmType, windowsType, otherType]
    }

    /**
     * Validates the submitted cloud information to make sure it is functioning correctly.
     * If a {@link ServiceResponse} is not marked as successful then the validation results will be
     * bubbled up to the user.
     * @param cloudInfo cloud
     * @param validateCloudRequest Additional validation information
     * @return ServiceResponse
     */
    @Override
    ServiceResponse validate(Cloud cloudInfo, ValidateCloudRequest validateCloudRequest) {
        log.info("cloudInfo: ${cloudInfo} ")
        log.info("validateCloudRequest: ${validateCloudRequest} ")
        try {
            if (cloudInfo) {
                def username
                def password
                username = validateCloudRequest.opts?.zone?.serviceUsername
                password = validateCloudRequest.opts?.zone?.servicePassword
                if (password == '************' && cloudInfo.id) {
                    password = cloudInfo.servicePassword
                }
                if (username?.length() < 1) {
                    return new ServiceResponse(success: false, msg: 'Enter a username')
                } else if (password?.length() < 1) {
                    return new ServiceResponse(success: false, msg: 'Enter a password')
                } else if (cloudInfo.serviceUrl?.length() < 1) {
                    return new ServiceResponse(success: false, msg: 'Enter an api url')
                } else {
                    if (cloudInfo.enabled) {
                        //test api call
                        NetworkProxy proxySettings = cloudInfo.apiProxy
                        HttpApiClient client = new HttpApiClient()
                        client.networkProxy = proxySettings
                        Map configMap = cloudInfo.getConfigMap()
                        AuthConfig authConfig = new AuthConfig(identityUrl: cloudInfo.serviceUrl, cloudConfig: configMap, domainId: configMap.domainId, username: username, password: password)
                        def session = ZStackComputeUtility.login(client, authConfig)
                        if (session) {
                            authConfig.session = session
                            ZStackComputeUtility.logout(client, authConfig)
                            return ServiceResponse.success()
                        }
                        return new ServiceResponse(success: false, msg: 'No session found')
                    } else {
                        return ServiceResponse.success()
                    }
                }
            } else {
                return new ServiceResponse(success: false, msg: 'No cloud found')
            }
        } catch (e) {
            log.error('Error validating cloud', e)
            return new ServiceResponse(success: false, msg: 'Error validating cloud')
        }
        return ServiceResponse.success()
    }

    def initializeCloudConfig(Cloud cloud) {
        log.info "initializeCloudConfig: ${cloud.id}"
        HttpApiClient client
        Cloud rtn = cloud
        try {
            def configMap = cloud.getConfigMap()
            cloud = morpheus.cloud.getCloudById(cloud.id).blockingGet()
            if (cloud.enabled == true) {
                NetworkProxy proxySettings = cloud.apiProxy
                client = new HttpApiClient()
                client.networkProxy = proxySettings
                // Fetch the token to set some API level config
                AuthConfig authConfig = plugin.getAuthConfig(cloud)
                log.info "initializeCloudConfig authConfig: ${authConfig}"
                def session = ZStackComputeUtility.login(client, authConfig)
                if (session) {
                    log.info "initializeCloudConfig session: ${session}"
                    configMap.session = session
                    cloud.setConfigMap(configMap)
                    morpheus.cloud.save(cloud).blockingGet()
                }
            }
            if (cloud.id) {
                rtn = morpheus.cloud.getCloudById(cloud.id).blockingGet()
            }
        } catch (e) {
            log.error "Exception in initializeCloudConfig ${cloud.id} : ${e}", e
        } finally {
            if (client) {
                client.shutdownClient()
            }
        }
        return rtn
    }

    /**
     * Called when a Cloud From Morpheus is first saved. This is a hook provided to take care of initial state
     * assignment that may need to take place.
     * @param cloudInfo instance of the cloud object that is being initialized.
     * @return ServiceResponse
     */
    @Override
    ServiceResponse initializeCloud(Cloud cloudInfo) {
        ServiceResponse rtn = new ServiceResponse(success: false)
        log.info "Initializing code: ${cloudInfo.code}"
        log.info "Initializing Cloud: ${cloudInfo}"
        log.info "initializeCloud config: ${cloudInfo.configMap}"

        try {
            cloudInfo = initializeCloudConfig(cloudInfo)
            log.info "Initializing cloudInfo session: ${cloudInfo.getConfigMap().session}"
        } catch (e) {
            log.info("refresh cloud error: ${e}", e)
            rtn.msg = "Error in setting up cloud: ${e}"
            rtn.error = rtn.msg
        }

        return rtn
    }

    /**
     * Zones/Clouds are refreshed periodically by the Morpheus Environment. This includes things like caching of brownfield
     * environments and resources such as Networks, Datastores, Resource Pools, etc.
     * @param cloudInfo cloud
     * @return ServiceResponse. If ServiceResponse.success == true, then Cloud status will be set to Cloud.Status.ok. If
     * ServiceResponse.success == false, the Cloud status will be set to ServiceResponse.data['status'] or Cloud.Status.error
     * if not specified. So, to indicate that the Cloud is offline, return `ServiceResponse.error('cloud is not reachable', null, [status: Cloud.Status.offline])`
     */
    @Override
    ServiceResponse refresh(Cloud cloudInfo) {
        log.info "refresh cloudInfo session : ${cloudInfo.configMap.session}"
        def rtn = [success: false]
        HttpApiClient client
        Cloud cloud = morpheus.cloud.getCloudById(cloudInfo.id).blockingGet()

        try {
            def syncDate = new Date()
            def apiUrlObj = new URL(cloud.serviceUrl)
            def apiHost = apiUrlObj.getHost()
            def apiPort = apiUrlObj.getPort() > 0 ? apiUrlObj.getPort() : (apiUrlObj?.getProtocol()?.toLowerCase() == 'https' ? 5443 : 8080)
            def hostOnline = ConnectionUtils.testHostConnectivity(apiHost, apiPort, true, true, cloud.apiProxy)
            log.info "apiHost：${apiHost} apiPort：${apiPort} hostOnline : ${hostOnline}"
            if (hostOnline) {
                morpheus.async.cloud.updateCloudStatus(cloud, Cloud.Status.syncing, null, syncDate)
                NetworkProxy proxySettings = cloud.apiProxy
                client = new HttpApiClient()
                client.networkProxy = proxySettings
                (new ClustersSync(this.plugin, cloud, client)).execute()
                (new HostsSync(this.plugin, cloud, client)).execute()
                (new NetworksSync(this.plugin, cloud, client)).execute()
                (new PrimaryStoragesSync(this.plugin, cloud, client)).execute()
                (new VirtualMachinesSync(this.plugin, cloud, client)).execute()
                (new VirtualImageLocationSync(this.plugin, cloud, client)).execute()

                morpheus.async.cloud.updateCloudStatus(cloud, Cloud.Status.ok, null, syncDate)
            } else {
                morpheus.async.cloud.updateCloudStatus(cloud, Cloud.Status.offline, 'ZStack not reachable', syncDate)
            }
            rtn.success = true
        } catch (e) {
            log.info("refresh error: ${e}", e)
        } finally {
            if (client) {
                client.shutdownClient()
            }
        }
        return rtn
    }

    /**
     * Zones/Clouds are refreshed periodically by the Morpheus Environment. This includes things like caching of brownfield
     * environments and resources such as Networks, Datastores, Resource Pools, etc. This represents the long term sync method that happens
     * daily instead of every 5-10 minute cycle
     * @param cloudInfo cloud
     */
    @Override
    void refreshDaily(Cloud cloudInfo) {
        log.info "refreshDaily cloudInfo session : ${cloudInfo.configMap.session}"
    }

    /**
     * Called when a Cloud From Morpheus is removed. This is a hook provided to take care of cleaning up any state.
     * @param cloudInfo instance of the cloud object that is being removed.
     * @return ServiceResponse
     */
    @Override
    ServiceResponse deleteCloud(Cloud cloudInfo) {
        return ServiceResponse.success()
    }

    /**
     * Returns whether the cloud supports {@link CloudPool}
     * @return Boolean
     */
    @Override
    Boolean hasComputeZonePools() {
        return false
    }

    /**
     * Returns whether a cloud supports {@link Network}
     * @return Boolean
     */
    @Override
    Boolean hasNetworks() {
        return true
    }

    /**
     * Returns whether a cloud supports {@link CloudFolder}
     * @return Boolean
     */
    @Override
    Boolean hasFolders() {
        return false
    }

    /**
     * Returns whether a cloud supports {@link Datastore}
     * @return Boolean
     */
    @Override
    Boolean hasDatastores() {
        return true
    }

    /**
     * Returns whether a cloud supports bare metal VMs
     * @return Boolean
     */
    @Override
    Boolean hasBareMetal() {
        return false
    }

    /**
     * Indicates if the cloud supports cloud-init. Returning true will allow configuration of the Cloud
     * to allow installing the agent remotely via SSH /WinRM or via Cloud Init
     * @return Boolean
     */
    @Override
    Boolean hasCloudInit() {
        return true
    }

    /**
     * Indicates if the cloud supports the distributed worker functionality
     * @return Boolean
     */
    @Override
    Boolean supportsDistributedWorker() {
        return true
    }

    /**
     * Called when a server should be started. Returning a response of success will cause corresponding updates to usage
     * records, result in the powerState of the computeServer to be set to 'on', and related instances set to 'running'
     * @param computeServer server to start
     * @return ServiceResponse
     */
    @Override
    ServiceResponse startServer(ComputeServer computeServer) {
        log.info("startServer: ${computeServer}")
        def rtn = [success: false]
        try {
            if (computeServer.managed == true || computeServer.computeServerType?.controlPower) {
                Cloud cloud = computeServer.cloud
                HttpApiClient client = new HttpApiClient()
                client.networkProxy = cloud.apiProxy
                def authConfig = plugin.getAuthConfig(cloud)
                def startResults = ZStackComputeUtility.operateVm(client, authConfig, computeServer.externalId, ZStackComputeUtility.startVm)
                if (startResults.success == true) {
                    rtn.success = true
                }
            } else {
                log.info("startServer - ignoring request for unmanaged instance")
            }
        } catch (e) {
            rtn.msg = "Error starting server: ${e.message}"
            log.info("startServer error: ${e}", e)
        }
        return new ServiceResponse(rtn)
    }

    /**
     * Called when a server should be stopped. Returning a response of success will cause corresponding updates to usage
     * records, result in the powerState of the computeServer to be set to 'off', and related instances set to 'stopped'
     * @param computeServer server to stop
     * @return ServiceResponse
     */
    @Override
    ServiceResponse stopServer(ComputeServer computeServer) {
        log.info("stopServer: ${computeServer}")
        def rtn = [success: false]
        try {
            if (computeServer.managed == true || computeServer.computeServerType?.controlPower) {
                Cloud cloud = computeServer.cloud
                HttpApiClient client = new HttpApiClient()
                client.networkProxy = cloud.apiProxy
                def authConfig = plugin.getAuthConfig(cloud)
                def stopResults = ZStackComputeUtility.operateVm(client, authConfig, computeServer.externalId, ZStackComputeUtility.stopVm)
                if (stopResults.success == true) {
                    rtn.success = true
                }
            } else {
                log.info("stopServer - ignoring request for unmanaged instance")
            }
        } catch (e) {
            rtn.msg = "Error stopping server: ${e.message}"
            log.info("stopServer error: ${e}", e)
        }
        return new ServiceResponse(rtn)
    }

    /**
     * Called when a server should be deleted from the Cloud.
     * @param computeServer server to delete
     * @return ServiceResponse
     */
    @Override
    ServiceResponse deleteServer(ComputeServer computeServer) {
        return ServiceResponse.success()
    }

    /**
     * Grabs the singleton instance of the provisioning provider based on the code defined in its implementation.
     * Typically Providers are singleton and instanced in the {@link Plugin} class
     * @param providerCode String representation of the provider short code
     * @return the ProvisionProvider requested
     */
    @Override
    ProvisionProvider getProvisionProvider(String providerCode) {
        return getAvailableProvisionProviders().find { it.code == providerCode }
    }

    /**
     * Returns the default provision code for fetching a {@link ProvisionProvider} for this cloud.
     * This is only really necessary if the provision type code is the exact same as the cloud code.
     * @return the provision provider code
     */
    @Override
    String getDefaultProvisionTypeCode() {
        return ZstackProvisionProvider.PROVISION_PROVIDER_CODE
    }

    /**
     * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
     * @return an implementation of the MorpheusContext for running Future based rxJava queries
     */
    @Override
    MorpheusContext getMorpheus() {
        return this.@context
    }

    /**
     * Returns the instance of the Plugin class that this provider is loaded from
     * @return Plugin class contains references to other providers
     */
    @Override
    Plugin getPlugin() {
        return this.@plugin
    }

    /**
     * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
     * that is seeded or generated related to this provider will reference it by this code.
     * @return short code string that should be unique across all other plugin implementations.
     */
    @Override
    String getCode() {
        return CLOUD_PROVIDER_CODE
    }

    /**
     * Provides the provider name for reference when adding to the Morpheus Orchestrator
     * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
     *
     * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
     */
    @Override
    String getName() {
        return 'ZStack'
    }
}
