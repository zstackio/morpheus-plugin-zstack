package com.morpheusdata.zstack.plugin

import com.morpheusdata.PrepareHostResponse
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.core.providers.HostProvisionProvider
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.VirtualImageType
import com.morpheusdata.model.HostType;
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.Workload
import com.morpheusdata.model.Process
import com.morpheusdata.model.ProcessEvent
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.zstack.plugin.utils.ZStackComputeUtility
import groovy.util.logging.Slf4j

@Slf4j
class ZstackProvisionProvider extends AbstractProvisionProvider implements WorkloadProvisionProvider, HostProvisionProvider {
    public static final String PROVISION_PROVIDER_CODE = 'zstack-provision-provider'

    protected MorpheusContext context
    protected Plugin plugin

    public ZstackProvisionProvider(Plugin plugin, MorpheusContext ctx) {
        super()
        this.@context = ctx
        this.@plugin = plugin
    }

    /**
     * This method is called before runWorkload and provides an opportunity to perform action or obtain configuration
     * that will be needed in runWorkload. At the end of this method, if deploying a ComputeServer with a VirtualImage,
     * the sourceImage on ComputeServer should be determined and saved.
     * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
     *                 how best to provision the workload
     * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
     *                        in running the Workload. This will be passed along into runWorkload
     * @param opts additional configuration options that may have been passed during provisioning
     * @return Response from API
     */
    @Override
    ServiceResponse<PrepareWorkloadResponse> prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
        log.info("ZstackProvisionProvider prepareWorkload: ${workload},${workloadRequest},${opts}")
        ServiceResponse<PrepareWorkloadResponse> resp = new ServiceResponse<PrepareWorkloadResponse>(
                true, // successful
                '', // no message
                null, // no errors
                new PrepareWorkloadResponse(workload: workload) // adding the workload to the response for convenience
        )
        return resp
    }

    /**
     * Some older clouds have a provision type code that is the exact same as the cloud code. This allows one to set it
     * to match and in doing so the provider will be fetched via the cloud providers {@link CloudProvider#getDefaultProvisionTypeCode()} method.
     * @return code for overriding the ProvisionType record code property
     */
    @Override
    String getProvisionTypeCode() {
        return PROVISION_PROVIDER_CODE
    }

    /**
     * Provide an icon to be displayed for ServicePlans, VM detail page, etc.
     * where a circular icon is displayed
     * @since 0.13.6* @return Icon
     */
    @Override
    Icon getCircularIcon() {
        // TODO: change icon paths to correct filenames once added to your project
        return new Icon(path: 'zstack-circular.svg', darkPath: 'zstack-circular-dark.svg')
    }

    /**
     * Provides a Collection of OptionType inputs that need to be made available to various provisioning Wizards
     * @return Collection of OptionTypes
     */
    @Override
    Collection<OptionType> getOptionTypes() {
        Collection<OptionType> options = []
        options << new OptionType(
                name: 'skip agent install',
                code: 'provisionType.zstackServer.noAgent',
                category: 'provisionType.zstackServer',
                inputType: OptionType.InputType.CHECKBOX,
                fieldName: 'noAgent',
                fieldContext: 'config',
                fieldCode: 'gomorpheus.optiontype.SkipAgentInstall',
                fieldLabel: 'Skip Agent Install',
                fieldGroup: 'Advanced Options',
                displayOrder: 4,
                required: false,
                enabled: true,
                editable: false,
                global: false,
                placeHolder: null,
                helpBlock: 'Skipping Agent installation will result in a lack of logging and guest operating system statistics. Automation scripts may also be adversely affected.',
                defaultValue: null,
                custom: false,
                fieldClass: null
        )
        // TODO: create some option types for provisioning and add them to collection
        return options
    }

    /**
     * Provides a Collection of OptionType inputs for configuring node types
     * @since 0.9.0* @return Collection of OptionTypes
     */
    @Override
    Collection<OptionType> getNodeOptionTypes() {
        Collection<OptionType> nodeOptions = []
//        nodeOptions << new OptionType(
//                name: 'virtual image',
//                category: 'provisionType.zstack.custom',
//                code: 'provisionType.zstack.custom.containerType.virtualImageId',
//                fieldContext: 'containerType',
//                fieldName: 'virtualImage.id',
//                fieldCode: 'gomorpheus.label.vmImage',
//                fieldLabel: 'VM Image',
//                fieldGroup: null,
//                inputType: OptionType.InputType.SELECT,
//                displayOrder: 10,
//                fieldClass: null,
//                required: false,
//                editable: false,
//                noSelection: 'Select',
//                optionSourceType: "zs",
//                optionSource: 'zsVirtualImages'
//        )

        return nodeOptions
    }

    /**
     * Provides a Collection of StorageVolumeTypes that are available for root StorageVolumes
     * @return Collection of StorageVolumeTypes
     */
    @Override
    Collection<StorageVolumeType> getRootVolumeStorageTypes() {
        Collection<StorageVolumeType> volumeTypes = []
        // TODO: create some storage volume types and add to collection
        return volumeTypes
    }

    /**
     * Provides a Collection of StorageVolumeTypes that are available for data StorageVolumes
     * @return Collection of StorageVolumeTypes
     */
    @Override
    Collection<StorageVolumeType> getDataVolumeStorageTypes() {
        Collection<StorageVolumeType> dataVolTypes = []
        // TODO: create some data volume types and add to collection
        return dataVolTypes
    }

    /**
     * Provides a Collection of ${@link ServicePlan} related to this ProvisionProvider that can be seeded in.
     * Some clouds do not use this as they may be synced in from the public cloud. This is more of a factor for
     * On-Prem clouds that may wish to have some precanned plans provided for it.
     * @return Collection of ServicePlan sizes that can be seeded in at plugin startup.
     */
    @Override
    Collection<ServicePlan> getServicePlans() {
        log.info("ZstackProvisionProvider getServicePlans:")
        Collection<ServicePlan> plans = []
        plans << new ServicePlan([code            : 'zstack-vm-512', editable: true, name: '512MB Memory', description: '512MB Memory', sortOrder: 0, maxCores: 1,
                                  maxStorage      : 10l * 1024l * 1024l * 1024l, maxMemory: 1l * 512l * 1024l * 1024l, maxCpu: 1,
                                  customMaxStorage: true, customMaxDataStorage: true, addVolumes: true])

        plans << new ServicePlan([code            : 'zstack-vm-1024', editable: true, name: '1GB Memory', description: '1GB Memory', sortOrder: 1, maxCores: 1,
                                  maxStorage      : 10l * 1024l * 1024l * 1024l, maxMemory: 1l * 1024l * 1024l * 1024l, maxCpu: 1,
                                  customMaxStorage: true, customMaxDataStorage: true, addVolumes: true])

        plans << new ServicePlan([code            : 'zstack-vm-2048', editable: true, name: '2GB Memory', description: '2GB Memory', sortOrder: 2, maxCores: 1,
                                  maxStorage      : 20l * 1024l * 1024l * 1024l, maxMemory: 2l * 1024l * 1024l * 1024l, maxCpu: 1,
                                  customMaxStorage: true, customMaxDataStorage: true, addVolumes: true])

        plans << new ServicePlan([code            : 'zstack-vm-4096', editable: true, name: '4GB Memory', description: '4GB Memory', sortOrder: 3, maxCores: 1,
                                  maxStorage      : 40l * 1024l * 1024l * 1024l, maxMemory: 4l * 1024l * 1024l * 1024l, maxCpu: 1,
                                  customMaxStorage: true, customMaxDataStorage: true, addVolumes: true])

        plans << new ServicePlan([code            : 'zstack-vm-8192', editable: true, name: '8GB Memory', description: '8GB Memory', sortOrder: 4, maxCores: 2,
                                  maxStorage      : 80l * 1024l * 1024l * 1024l, maxMemory: 8l * 1024l * 1024l * 1024l, maxCpu: 2,
                                  customMaxStorage: true, customMaxDataStorage: true, addVolumes: true])

        plans << new ServicePlan([code            : 'zstack-vm-16384', editable: true, name: '16GB Memory', description: '16GB Memory', sortOrder: 5, maxCores: 2,
                                  maxStorage      : 160l * 1024l * 1024l * 1024l, maxMemory: 16l * 1024l * 1024l * 1024l, maxCpu: 2,
                                  customMaxStorage: true, customMaxDataStorage: true, addVolumes: true])

        plans << new ServicePlan([code            : 'zstack-vm-24576', editable: true, name: '24GB Memory', description: '24GB Memory', sortOrder: 6, maxCores: 4,
                                  maxStorage      : 240l * 1024l * 1024l * 1024l, maxMemory: 24l * 1024l * 1024l * 1024l, maxCpu: 4,
                                  customMaxStorage: true, customMaxDataStorage: true, addVolumes: true])

        plans << new ServicePlan([code            : 'zstack-vm-32768', editable: true, name: '32GB Memory', description: '32GB Memory', sortOrder: 7, maxCores: 4,
                                  maxStorage      : 320l * 1024l * 1024l * 1024l, maxMemory: 32l * 1024l * 1024l * 1024l, maxCpu: 4,
                                  customMaxStorage: true, customMaxDataStorage: true, addVolumes: true])

        plans << new ServicePlan([code            : 'internal-custom-zstack', editable: false, name: 'zstack Custom', description: 'zstack Custom', sortOrder: 0,
                                  customMaxStorage: true, customMaxDataStorage: true, addVolumes: true, customCpu: true, customCores: true, customMaxMemory: true, deletable: false, provisionable: false,
                                  maxStorage      : 0l, maxMemory: 0l, maxCpu: 0,])
        // TODO: create some service plans (sizing like cpus, memory, etc) and add to collection
        return plans
    }

    /**
     * Validates the provided provisioning options of a workload. A return of success = false will halt the
     * creation and display errors
     * @param opts options
     * @return Response from API. Errors should be returned in the errors Map with the key being the field name and the error
     * message as the value.
     */
    @Override
    ServiceResponse validateWorkload(Map opts) {
        log.info "validateWorkload opts : ${opts}"

        List<Long> rootVolumesSizes = opts.volumes.findAll { it.rootVolume == true }.collect {
            it.maxStorage == 0 ? it.size * 1024L * 1024L * 1024L : it.maxStorage
        }
        List<Long> rootVolumePsId = opts.volumes.findAll { it.rootVolume == true && it.datastoreId != null }.collect {
            it.datastoreId
        }
        List<Long> dataVolumesSizes = opts.volumes.findAll { it.rootVolume == false }.collect {
            it.maxStorage == 0 ? it.size * 1024L * 1024L * 1024L : it.maxStorage
        }
        List<String> networkIds = opts.networkInterfaces.findAll { it.network?.id != null }.collect {
            it.network.id
        }
        Long imageId = opts.customOptions.imageId

        log.info "validateWorkload rootVolumesSizes : ${rootVolumesSizes} , dataVolumesSizes: ${dataVolumesSizes} , nonNullNetworkIds：${networkIds} "
        log.info "validateWorkload rootVolumePsId : ${rootVolumePsId} , imageIds: ${imageId}"

        Long cpuNum = opts.servicePlanOptions.maxCores
        Long menNum = opts.servicePlanOptions.maxMemory
        log.info "validateWorkload cpuNum : ${cpuNum} , menNum: ${menNum}"

        if (rootVolumesSizes.size() != 1) {
            return new ServiceResponse(success: false, msg: 'root volume is required')
        }
        if (networkIds.size() != 1) {
            return new ServiceResponse(success: false, msg: 'network must select at least one')
        }
        if (imageId == null) {
            return new ServiceResponse(success: false, msg: 'image must select at least one')
        }
        return ServiceResponse.success()
    }

    /**
     * This method is a key entry point in provisioning a workload. This could be a vm, a container, or something else.
     * Information associated with the passed Workload object is used to kick off the workload provision request
     * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
     *                 how best to provision the workload
     * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
     *                        in running the Workload
     * @param opts additional configuration options that may have been passed during provisioning
     * @return Response from API
     */
    @Override
    ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
        log.info("ZstackProvisionProvider runWorkload cloudConfigOpts: ${workloadRequest.cloudConfigOpts}")
        log.info("ZstackProvisionProvider runWorkload opts: ${opts}")

        HttpApiClient client
        ProvisionResponse provisionResponse = new ProvisionResponse(success: true)

        Process process = workloadRequest?.process ?: null
        context.async.process.startProcessStep(process, new ProcessEvent(type: ProcessEvent.ProcessType.provisionConfig), 'configuring')

        // Inform Morpheus to install the agent (or not) after the server is created
        provisionResponse.noAgent = true
        provisionResponse.installAgent = false

        ComputeServer server = workload.server
        try {
            Cloud cloud = server.cloud
            VirtualImage virtualImage = server.sourceImage
            def authConfig = plugin.getAuthConfig(cloud)
            client = new HttpApiClient()
            client.networkProxy = cloud.apiProxy

            List<Long> rootVolumesSizes = workloadRequest.cloudConfigOpts.server.volumes.findAll {
                it.rootVolume == true
            }.collect { it.maxStorage }
            List<Long> dataVolumesSizes = workloadRequest.cloudConfigOpts.server.volumes.findAll {
                it.rootVolume == false
            }.collect { it.maxStorage }

            def primaryExternalId = opts.networkConfig.primaryInterface.externalId
            def extraExternalIds = opts.networkConfig.extraInterfaces?.collect { it.externalId } ?: []
            def networkUuidList = [primaryExternalId] + extraExternalIds

            def vmInfo = [
                    name          : opts.name,
                    imageUuid     : virtualImage.externalId,
                    rootDiskSize  : rootVolumesSizes.get(0),
                    cpuNum        : workloadRequest.cloudConfigOpts.server.maxCores,
                    memorySize    : workloadRequest.cloudConfigOpts.server.maxMemory,
                    l3NetworkUuids: networkUuidList,
                    dataDiskSizes : dataVolumesSizes
            ]

            context.async.process.startProcessStep(process, new ProcessEvent(type: ProcessEvent.ProcessType.provisionDeploy), 'deploying vm')

            log.info("ZstackProvisionProvider vmInfo: ${vmInfo}")
            def createResults = ZStackComputeUtility.createVm(client, authConfig, vmInfo)
            if (createResults.success != true) {
                return new ServiceResponse(success: false, msg: provisionResponse.message ?: 'createVm config error', error: provisionResponse.message, data: provisionResponse)
            }

            context.async.process.startProcessStep(process, new ProcessEvent(type: ProcessEvent.ProcessType.provisionLaunch), 'starting vm')

            server.externalId = createResults.results.inventory.uuid
            server.statusDate = new Date()
            server.osDevice = '/dev/sda'
            server.dataDevice = '/dev/sda'
            server.lvmEnabled = false
            server.capacityInfo = new ComputeCapacityInfo(maxCores: workloadRequest.cloudConfigOpts.server.maxCores, maxMemory: workloadRequest.cloudConfigOpts.server.maxMemory,
                    maxStorage: workloadRequest.cloudConfigOpts.server.maxStorage)
            server.computeServerType = new ComputeServerType(code: 'zstack-other-vm')
            server.managed = true
            server.discovered = false
            switch (createResults.results.inventory.platform) {
                case "Windows":
                    server.computeServerType = new ComputeServerType(code: 'zstack-windows-vm')
                    break
                case "Linux":
                    server.computeServerType = new ComputeServerType(code: 'zstack-vm')
                    break
            }
            context.async.computeServer.bulkSave([server]).blockingGet()

            server.volumes.forEach { volume ->
                def res = createResults.results.inventory.allVolumes?.find { it.deviceId == volume.displayOrder }
                log.info("ZstackProvisionProvider runWorkload allVolume : ${res}")
                if (res) {
                    volume.externalId = res.uuid
                    volume.name = res.name
                    context.async.storageVolume.save([volume]).blockingGet()
                    log.info("ZstackProvisionProvider runWorkload update : ${volume.name}, ${volume.externalId}")
                }
            }

            server.interfaces.forEach(anInterface -> {
                if (anInterface.primaryInterface) {
                    def res = createResults.results.inventory.vmNics?.find { it.deviceId == 0 }
                    log.info("ZstackProvisionProvider runWorkload primaryInterface interface : ${res}")
                    anInterface.externalId = res.uuid
                    anInterface.name = res.internalName
                    anInterface.publicIpAddress = res.ip
                    anInterface.macAddress = res.mac
                    log.info("ZstackProvisionProvider runWorkload  primaryInterface update: ${anInterface.externalId}")

                } else {
                    def res = createResults.results.inventory.vmNics?.find {
                        it.deviceId == anInterface.name.replaceAll("[^\\d]", "").toInteger()
                    }
                    log.info("ZstackProvisionProvider runWorkload interface : ${res}")
                    anInterface.externalId = res.uuid
                    anInterface.name = res.internalName
                    anInterface.publicIpAddress = res.ip
                    anInterface.macAddress = res.mac
                }
                context.async.computeServer.computeServerInterface.save([anInterface]).blockingGet()
            })
            return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
        } catch (e) {
            log.info "runWorkload fail : ${e}", e
            provisionResponse.setError(e.message)
            return new ServiceResponse(success: false, msg: e.message, error: e.message, data: provisionResponse)
        } finally {
            if (client) {
                client.shutdownClient()
            }
        }
    }

    /**
     * This method is called after successful completion of runWorkload and provides an opportunity to perform some final
     * actions during the provisioning process. For example, ejected CDs, cleanup actions, etc
     * @param workload the Workload object that has been provisioned
     * @return Response from the API
     */
    @Override
    ServiceResponse finalizeWorkload(Workload workload) {
        log.info("ZstackProvisionProvider finalizeWorkload: ${workload}")
        return ServiceResponse.success()
    }

    /**
     * Issues the remote calls necessary top stop a workload element from running.
     * @param workload the Workload we want to shut down
     * @return Response from API
     */
    @Override
    ServiceResponse stopWorkload(Workload workload) {
        log.info("ZstackProvisionProvider stopWorkload: ${workload}")
        return plugin.getCloudProvider().stopServer(workload.getServer());
    }

    /**
     * Issues the remote calls necessary to start a workload element for running.
     * @param workload the Workload we want to start up.
     * @return Response from API
     */
    @Override
    ServiceResponse startWorkload(Workload workload) {
        log.info("ZstackProvisionProvider startWorkload: ${workload}")
        return plugin.getCloudProvider().startServer(workload.getServer());
    }

    /**
     * Issues the remote calls to restart a workload element. In some cases this is just a simple alias call to do a stop/start,
     * however, in some cases cloud providers provide a direct restart call which may be preferred for speed.
     * @param workload the Workload we want to restart.
     * @return Response from API
     */
    @Override
    ServiceResponse restartWorkload(Workload workload) {
        log.info("ZstackProvisionProvider restartWorkload: ${workload}")
        def computeServer = workload.getServer()
        try {
            if (computeServer.managed == true || computeServer.computeServerType?.controlPower) {
                Cloud cloud = computeServer.cloud
                HttpApiClient client = new HttpApiClient()
                client.networkProxy = cloud.apiProxy
                def authConfig = plugin.getAuthConfig(cloud)
                def startResults = ZStackComputeUtility.operateVm(client, authConfig, computeServer.externalId, ZStackComputeUtility.restartVm)
                if (startResults.success != true) {
                    return ServiceResponse.error('restart server failed: ${startResults.error}')
                }
            } else {
                log.info("restartWorkload - ignoring request for unmanaged instance")
            }
            return ServiceResponse.success()
        } catch (e) {
            log.info("restart Server error: ${e}", e)
            return ServiceResponse.error('Error restart server: ${e.message}')
        }
    }

    /**
     * This is the key method called to destroy / remove a workload. This should make the remote calls necessary to remove any assets
     * associated with the workload.
     * @param workload to remove
     * @param opts map of options
     * @return Response from API
     */
    @Override
    ServiceResponse removeWorkload(Workload workload, Map opts) {
        log.info("ZstackProvisionProvider removeWorkload: ${workload}")
        log.info("ZstackProvisionProvider removeWorkload externalId: ${workload.externalId}")
        log.info("ZstackProvisionProvider removeWorkload opts: ${opts}")
        HttpApiClient client = new HttpApiClient()
        try {
            if (workload.server?.externalId) {
                stopWorkload(workload)
                ComputeServer server = workload.server
                Cloud cloud = server.cloud
                client.networkProxy = cloud.apiProxy
                def authConfig = plugin.getAuthConfig(cloud)
                ZStackComputeUtility.deleteVm(client, authConfig, workload.server?.externalId)

                List<Long> dataVolumeUuids = workload.server.volumes.findAll {
                    it.rootVolume == false && it.externalId != null
                }.collect { it.externalId }
                dataVolumeUuids.forEach(volumeUuid -> {
                    ZStackComputeUtility.deleteVolume(client, authConfig, volumeUuid)
                })
            } else {
                return ServiceResponse.success()
            }
        } catch (e) {
            log.info "removeWorkload fail : ${e}", e
        } finally {
            if (client) {
                client.shutdownClient()
            }
        }
        return ServiceResponse.success()
    }

    /**
     * Method called after a successful call to runWorkload to obtain the details of the ComputeServer. Implementations
     * should not return until the server is successfully created in the underlying cloud or the server fails to
     * create.
     * @param server to check status
     * @return Response from API. The publicIp and privateIp set on the WorkloadResponse will be utilized to update the ComputeServer
     */
    @Override
    ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
        log.info("ZstackProvisionProvider getServerDetails: ${server}")

        return new ServiceResponse<ProvisionResponse>(true, null, null, new ProvisionResponse(success: true))
    }

    /**
     * Method called before runWorkload to allow implementers to create resources required before runWorkload is called
     * @param workload that will be provisioned
     * @param opts additional options
     * @return Response from API
     */
    @Override
    ServiceResponse createWorkloadResources(Workload workload, Map opts) {
        log.info("ZstackProvisionProvider createWorkloadResources: ${workload},${opts}")

        return ServiceResponse.success()
    }

    /**
     * Stop the server
     * @param computeServer to stop
     * @return Response from API
     */
    @Override
    ServiceResponse stopServer(ComputeServer computeServer) {
        log.info("ZstackProvisionProvider stopServer: ${computeServer}")
        return ServiceResponse.success()
    }

    /**
     * Start the server
     * @param computeServer to start
     * @return Response from API
     */
    @Override
    ServiceResponse startServer(ComputeServer computeServer) {
        log.info("ZstackProvisionProvider startServer: ${computeServer}")
        return ServiceResponse.success()
    }

    /**
     * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
     *
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
        return PROVISION_PROVIDER_CODE
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

    String getDefaultInstanceTypeDescription() {
        return "Create a virtual machine on the ZStack platform"
    }

    @Override
    ServiceResponse validateHost(ComputeServer computeServer, Map map) {
        log.info("validateHost: ${computeServer}")
        return null
    }

    @Override
    ServiceResponse<PrepareHostResponse> prepareHost(ComputeServer computeServer, HostRequest hostRequest, Map map) {
        log.info("validateHost: ${computeServer}, ${map}")
        return null
    }

    @Override
    ServiceResponse<ProvisionResponse> runHost(ComputeServer computeServer, HostRequest hostRequest, Map map) {
        log.info("validateHost: ${computeServer}, ${map}")
        return null
    }

    @Override
    ServiceResponse finalizeHost(ComputeServer computeServer) {
        log.info("validateHost: ${computeServer}")
        return null
    }

    @Override
    Boolean supportsCustomServicePlans() {
        return true;
    }

    @Override
    Boolean customSupported() {
        return true;
    }

    @Override
    Boolean hasNetworks() {
        return true
    }

    @Override
    Boolean canAddVolumes() {
        return true
    }

    @Override
    Boolean canCustomizeRootVolume() {
        return true
    }

    @Override
    Boolean canCustomizeDataVolumes() {
        return true
    }

    @Override
    HostType getHostType() {
        HostType.vm
    }

    @Override
    String serverType() {
        return "vm"
    }

    @Override
    Collection<VirtualImageType> getVirtualImageTypes() {
        def virtualImageTypes = [
                new VirtualImageType(code: 'vhd', name: 'VHD'),
                new VirtualImageType(code: 'xva', name: 'XVA'),
        ]

        return virtualImageTypes
    }

    @Override
    Boolean hasNodeTypes() {
        return true;
    }

    @Override
    String getNodeFormat() {
        return "vm"
    }
}
