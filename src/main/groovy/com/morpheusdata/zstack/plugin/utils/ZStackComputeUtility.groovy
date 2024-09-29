package com.morpheusdata.zstack.plugin.utils

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import org.apache.http.client.methods.*
import org.apache.http.entity.*
import org.apache.http.impl.client.*

import java.security.MessageDigest

@Slf4j
class ZStackComputeUtility {
    static ignoreSsl = true
    static final UNAUTHORIZED_ERROR = 'Unauthorized'
    static tokenBuffer = 1000l * 10l //10 second buffer
    static final startVm = "startVmInstance"
    static final stopVm = "stopVmInstance"
    static final restartVm = "rebootVmInstance"

    static final timeoutInSec = 300

    static operateVm(HttpApiClient client, AuthConfig authConfig, String vmUuid, String actionName) {
        def rtn = [success: false]
        def session = login(client, authConfig)

        if (!session) {
            rtn.error = 'Not Authorized'
            return rtn
        }

        try {
            def requestOpts = [headers: buildHeaders([:]), body: [(actionName): [(actionName == stopVm ? "stopHA" : ""): (actionName == stopVm ? "true" : "")]]]
            log.info("requestOpts results: ${requestOpts}")
            authConfig.session = session
            def results = callApi(client, authConfig.identityUrl, String.format("/zstack/v1/vm-instances/%s/actions", vmUuid), session, requestOpts, 'PUT')
            log.info("operate vm API call results: ${results}")

            if (results.success == true) {
                def parsedUrl = new URL(results.data.location)
                def apiUrl = "${parsedUrl.protocol}://${parsedUrl.host}:${parsedUrl.port}"
                def path = parsedUrl.file

                def pending = true
                def attempts = 0

                while (pending && attempts < timeoutInSec) {
                    sleep(1000)

                    results = callApi(client, apiUrl, path, session, [headers: buildHeaders([:])], 'GET')
                    log.info("call back results: ${results}")

                    if (results.data?.inventory?.uuid) {
                        pending = false
                        rtn.success = true
                        rtn.results = results.data
                    } else if (!results.success) {
                        rtn.error = "operateVm failed, ${results.data.error}"
                        break
                    }

                    attempts++
                }

                if (pending && attempts == timeoutInSec) {
                    rtn.error = "operateVm timed out after ${attempts} attempts"
                }
            } else if (results.error == UNAUTHORIZED_ERROR) {
                rtn.error = 'Unauthorized action. Logging out.'
            } else {
                rtn.error = results.error ?: 'Unknown error'
            }

        } catch (Exception e) {
            rtn.error = "Exception during operate vm: ${e.message}"
            log.info("operate vm Exception: ", e)
        } finally {
            logout(client, authConfig)
            log.info("${actionName} rtn: ${rtn}")
        }
        return rtn
    }

    static createVm(HttpApiClient client, AuthConfig authConfig, Map vmInfo) {
        def rtn = [success: false]
        def session = login(client, authConfig)

        if (!session) {
            rtn.error = 'Not Authorized'
            return rtn
        }

        try {
            def requestBody = [
                    params    : [
                            name                : vmInfo.name,  // Assuming `name` should be `actionName`
                            strategy            : 'InstantStart',  // Assuming this is a fixed value
                            imageUuid           : vmInfo.imageUuid,  // Placeholder value
                            rootDiskSize        : vmInfo.rootDiskSize,  // Placeholder value
                            cpuNum              : vmInfo.cpuNum,  // Placeholder value
                            memorySize          : vmInfo.memorySize,  // Placeholder value
                            l3NetworkUuids      : vmInfo.l3NetworkUuids,  // Placeholder value
                            sshKeyPairUuids     : [],
                            defaultL3NetworkUuid: vmInfo.l3NetworkUuids[0],  // Placeholder value
                            systemTags          : [
                                    'resourceConfig::vm::vm.clock.track::host',
                                    'cdroms::Empty::None::None',
                                    'vmConsoleMode::vnc',
                                    'cleanTraffic::false'
                            ],
                            rootVolumeSystemTags: [],
                            dataVolumeSystemTags: [],
                            reservedMemorySize  : 0,
                            dataDiskSizes       : vmInfo.dataDiskSizes
                    ],
                    systemTags: [
                            'resourceConfig::vm::vm.clock.track::host',
                            'cdroms::Empty::None::None',
                            'vmConsoleMode::vnc',
                            'cleanTraffic::false'
                    ]
            ]

            if (vmInfo.primaryStorageUuidForRootVolume != null) {
                requestBody.params.primaryStorageUuidForRootVolume = vmInfo.primaryStorageUuidForRootVolume
            }

            def requestOpts = [
                    headers: buildHeaders([:]),
                    body   : requestBody
            ]

            log.info("Request options: ${requestOpts}")

            authConfig.session = session
            def results = callApi(client, authConfig.identityUrl, "/zstack/v1/vm-instances", session, requestOpts, 'POST')
            log.info("API call results: ${results}")

            if (results.success == true) {
                def parsedUrl = new URL(results.data.location)
                def apiUrl = "${parsedUrl.protocol}://${parsedUrl.host}:${parsedUrl.port}"
                def path = parsedUrl.file

                def pending = true
                def attempts = 0

                while (pending && attempts < 300) {
                    sleep(1000)

                    results = callApi(client, apiUrl, path, session, [headers: buildHeaders([:])], 'GET')
                    log.info("call back results: ${results}")

                    if (!results.success) {
                        break
                    }

                    if (results.data?.inventory?.uuid) {
                        pending = false
                        rtn.success = true
                        rtn.results = results.data
                    } else {
                        rtn.error = "createVm failed, ${results.data.error}"
                    }

                    attempts++
                }

                if (pending) {
                    rtn.error = "createVm timed out after ${attempts} attempts"
                }
            } else if (results.error == UNAUTHORIZED_ERROR) {
                rtn.error = 'Unauthorized action. Logging out.'
            } else {
                rtn.error = results.error ?: 'Unknown error'
            }
        } catch (Exception e) {
            rtn.error = "Exception during createVm: ${e.message}"
            log.info("createVm Exception: ", e)
        } finally {
            logout(client, authConfig)
        }

        return rtn
    }

    static deleteVm(HttpApiClient client, AuthConfig authConfig, String vmUuid) {
        def rtn = [success: false]
        def session = login(client, authConfig)

        if (!session) {
            rtn.error = 'deleteVm not authorized'
            return rtn
        }

        try {
            def requestOpts = [headers: buildHeaders([:])]
            def results = callApi(client, authConfig.identityUrl, String.format("/zstack/v1/vm-instances/%s", vmUuid), session, requestOpts, 'DELETE')
            log.info("deleteVm results: ${results}")
            sleep(2000)
            requestOpts.body = [expungeVmInstance: {}]
            results = callApi(client, authConfig.identityUrl, String.format("/zstack/v1/vm-instances/%s/actions", vmUuid), session, requestOpts, 'PUT')
            log.info("expungeVmInstance results: ${results}")
            sleep(1000)
            def parsedUrl = new URL(results.data.location)
            def apiUrl = "${parsedUrl.protocol}://${parsedUrl.host}:${parsedUrl.port}"
            def path = parsedUrl.file
            results = callApi(client, apiUrl, path, session, [headers: buildHeaders([:])], 'GET')
            log.info("expungeVmInstance callback results: ${results}")
        } catch (Exception e) {
            rtn.error = "Exception during deleteVm: ${e.message}"
            log.info("deleteVm Exception: ", e)
        } finally {
            logout(client, authConfig)
        }

        return rtn
    }

    static deleteVolume(HttpApiClient client, AuthConfig authConfig, String volumeUuid) {
        def rtn = [success: false]
        def session = login(client, authConfig)

        if (!session) {
            rtn.error = 'deleteVm not authorized'
            return rtn
        }

        try {
            def requestOpts = [headers: buildHeaders([:])]
            def results = callApi(client, authConfig.identityUrl, String.format("/zstack/v1/volumes/%s", volumeUuid), session, requestOpts, 'DELETE')
            log.info("deleteVolume results: ${results}")
            sleep(2000)
            requestOpts.body = [expungeDataVolume: {}]
            results = callApi(client, authConfig.identityUrl, String.format("/zstack/v1/volumes/%s/actions", volumeUuid), session, requestOpts, 'PUT')
            log.info("expungeDataVolume results: ${results}")
            sleep(1000)
            def parsedUrl = new URL(results.data.location)
            def apiUrl = "${parsedUrl.protocol}://${parsedUrl.host}:${parsedUrl.port}"
            def path = parsedUrl.file
            results = callApi(client, apiUrl, path, session, [headers: buildHeaders([:])], 'GET')
            log.info("expungeDataVolume callback results: ${results}")
        } catch (Exception e) {
            rtn.error = "Exception during deleteVolume: ${e.message}"
            log.info("deleteVolume Exception: ", e)
        } finally {
            logout(client, authConfig)
        }

        return rtn
    }

    static listClusters(HttpApiClient client, AuthConfig authConfig) {
        def rtn = [success: false]
        def session = login(client, authConfig)
        if (session) {
            def requestOpts = [headers: buildHeaders([:])]
            def results = callApi(client, authConfig.identityUrl, "/zstack/v1/clusters", session, requestOpts, 'GET')
            log.info("listClusters results: ${results}")
            if (results.success == true) {
                rtn.success = true
                rtn.results = results.data
            } else if (results.error == UNAUTHORIZED_ERROR) {
                rtn.error = results.error
            }
            authConfig.session = session
            logout(client, authConfig)
        } else {
            rtn.error = 'Not Authorized'
        }
        return rtn
    }

    static listImages(HttpApiClient client, AuthConfig authConfig) {
        def rtn = [success: false]
        def session = login(client, authConfig)
        if (session) {
            def requestOpts = [headers: buildHeaders([:]), query: [q: "system=false"]]
            def results = callApi(client, authConfig.identityUrl, "/zstack/v1/images", session, requestOpts, 'GET')
            log.info("listClusters results: ${results}")
            if (results.success == true) {
                rtn.success = true
                rtn.results = results.data
            } else if (results.error == UNAUTHORIZED_ERROR) {
                rtn.error = results.error
            }
            authConfig.session = session
            logout(client, authConfig)
        } else {
            rtn.error = 'Not Authorized'
        }
        return rtn
    }

    static listNetworks(HttpApiClient client, AuthConfig authConfig) {
        def rtn = [success: false]
        def session = login(client, authConfig)
        if (session) {
            def requestOpts = [headers: buildHeaders([:])]
            def results = callApi(client, authConfig.identityUrl, "/zstack/v1/l3-networks", session, requestOpts, 'GET')
            log.info("Hypervisors results: ${results}")
            if (results.success == true) {
                rtn.success = true
                rtn.results = results.data
            } else if (results.error == UNAUTHORIZED_ERROR) {
                rtn.error = results.error
            }
            authConfig.session = session
            logout(client, authConfig)
        } else {
            rtn.error = 'Not Authorized'
        }
        return rtn
    }

    static listPrimaryStorages(HttpApiClient client, AuthConfig authConfig) {
        def rtn = [success: false]
        def session = login(client, authConfig)
        if (session) {
            def requestOpts = [headers: buildHeaders([:])]
            def results = callApi(client, authConfig.identityUrl, "/zstack/v1/primary-storage", session, requestOpts, 'GET')
            log.info("Hypervisors results: ${results}")
            if (results.success == true) {
                rtn.success = true
                rtn.results = results.data
            } else if (results.error == UNAUTHORIZED_ERROR) {
                rtn.error = results.error
            }
            authConfig.session = session
            logout(client, authConfig)
        } else {
            rtn.error = 'Not Authorized'
        }
        return rtn
    }

    static listVirtualMachines(HttpApiClient client, AuthConfig authConfig) {
        def rtn = [success: false]
        def session = login(client, authConfig)
        if (session) {
            def requestOpts = [headers: buildHeaders([:])]
            def results = callApi(client, authConfig.identityUrl, "/zstack/v1/vm-instances", session, requestOpts, 'GET')
            log.info("Hypervisors results: ${results}")
            if (results.success == true) {
                rtn.success = true
                rtn.results = results.data
            } else if (results.error == UNAUTHORIZED_ERROR) {
                rtn.error = results.error
            }
            authConfig.session = session
            logout(client, authConfig)
        } else {
            rtn.error = 'Not Authorized'
        }
        return rtn
    }

    static listHypervisors(HttpApiClient client, AuthConfig authConfig) {
        def rtn = [success: false]
        def session = login(client, authConfig)
        if (session) {
            def requestOpts = [headers: buildHeaders([:])]
            def results = callApi(client, authConfig.identityUrl, "/zstack/v1/hosts", session, requestOpts, 'GET')
            log.info("list Hypervisors results: ${results}")
            if (results.success == true) {
                rtn.success = true
                rtn.results = results.data
            } else if (results.error == UNAUTHORIZED_ERROR) {
                rtn.error = results.error
            }
            authConfig.session = session
            logout(client, authConfig)
        } else {
            rtn.error = 'Not Authorized'
        }
        return rtn
    }

    static listZones(HttpApiClient client, AuthConfig authConfig) {
        def rtn = [success: false]
        def session = login(client, authConfig)
        if (session) {
            def requestOpts = [headers: buildHeaders([:])]
            def results = callApi(client, authConfig.identityUrl, "/zstack/v1/zones", session, requestOpts, 'GET')
            log.info("list project results: ${results}")
            if (results.success == true) {
                rtn.success = true
                rtn.results = results.data
            } else if (results.error == UNAUTHORIZED_ERROR) {
                rtn.error = results.error
            }
            authConfig.session = session
            logout(client, authConfig)
        } else {
            rtn.error = 'Not Authorized'
        }

        return rtn
    }

    static String login(HttpApiClient client, AuthConfig authConfig) {
        // Build token request body
        def config = authConfig.cloudConfig
        def ipAddr = authConfig.identityUrl
        def username = authConfig.username ?: config?.username
        def password = authConfig.password ?: config?.password
        def apiPath = '/zstack/v1/accounts/login'
        def secret = sha512(password)
        def body = [
                logInByAccount: [
                        accountName: username,
                        password   : secret
                ]
        ]

        def headers = buildHeaders([:])
        def requestOpts = [headers: headers, body: body]
        log.info("get path: ${apiPath} headers: ${headers} body: ${body}")
        def results = callApi(client, ipAddr, apiPath, null, requestOpts, 'PUT')
        log.info("getSession got: ${results}")
        def isSuccess = results?.success && results?.error != true
        if (isSuccess) {
            InventoryResult<SessionInventory> result = results.data
            log.info("getSession got: ${result.inventory}")
            return result.inventory.uuid
        }

        return null
    }

    static boolean logout(HttpApiClient client, AuthConfig authConfig) {
        // Build token request body
        def ipAddr = authConfig.identityUrl
        def apiPath = String.format("/zstack/v1/accounts/sessions/%s", authConfig.session)
        def headers = buildHeaders([:])
        def requestOpts = [headers: headers, body: []]
        def results = callApi(client, ipAddr, apiPath, null, requestOpts, 'PUT')
        log.info("logout results: ${results}")
        def isSuccess = results?.success && results?.error != true
        log.info("logout isSuccess: ${isSuccess}")
        return isSuccess
    }


    static buildHeaders(Map headers, String session = null) {
        headers = headers ?: [:]

        if (!headers['Content-Type']) {
            headers['Content-Type'] = 'application/json; charset=utf-8'
        }
        if (session) {
            headers['authorization'] = String.format('OAuth %s', session)
        }

        return headers
    }

    static ServiceResponse callApi(HttpApiClient client, apiUrl, path, session, opts = [:], method = 'POST') {
        log.info(String.format("Url:%s%s", apiUrl, path))
        ServiceResponse rtn = new ServiceResponse([success: false, headers: [:], error: false])

        try {
            if (!opts.headers) {
                opts.headers = [:]
            }
            if (!opts.headers['Content-Type']) {
                opts.headers['Content-Type'] = 'application/json; charset=utf-8'
            }
            if (session) {
                opts.headers.'authorization' = String.format('OAuth %s', session)
                opts.headers.'User-Agent' = 'Mozilla/5.0 Ubuntu/8.10 Firefox/3.0.4 Morpheus'
            }
            def query = [:]
            if (opts.query) {
                opts.query?.each { k, v ->
                    query[k] = v.toString()
                }
            }
            log.info(String.format("opts.query : %s", query))
            rtn = client.callJsonApi(apiUrl, path, null, null,
                    new HttpApiClient.RequestOptions(headers: opts.headers, body: opts.body, queryParams: query, contentType: 'application/json; charset=utf-8', ignoreSSL: true), method.toString())
            if (rtn.errorCode == '401') {
                rtn.error = UNAUTHORIZED_ERROR
            } else if (rtn.errorCode && !rtn.error) {
                rtn.error = getApiError(rtn)
            } else {
                if (!rtn.data && rtn.content) {
                    try {
                        rtn.results = new groovy.json.JsonSlurper().parseText(rtn.content)
                    } catch (ex2) {
                        log.info("Error parsing json response: {}", ex2, ex2)
                    }
                }
            }

        } catch (e) {
            log.info("callApi error: {}", e, e)
            rtn.error = e.message
        }
        return rtn
    }

    static getApiError(ServiceResponse apiResponse) {
        def rtn
        def apiData = apiResponse.data
        if (apiData instanceof Number) {
            rtn = apiData.toString()
        } else if (apiData instanceof CharSequence) {
            rtn = apiData
        } else if (apiData instanceof Map) {
            if (apiData.error instanceof Map) {
                rtn = "${apiData.error.message} (${apiData.error.code})"
            } else if (apiData.forbidden && apiData.forbidden instanceof Map) {
                rtn = apiData.forbidden.message?.toString()
            } else {
                rtn = (apiData.error ?: apiResponse.error)?.toString()
            }
        }
        return rtn
    }

    static String sha512(String input) {
        MessageDigest md = MessageDigest.getInstance("SHA-512")
        byte[] hashBytes = md.digest(input.bytes)
        return hashBytes.collect { String.format("%02x", it) }.join()
    }

    static class SessionInventory {
        public String uuid
        public String accountUuid
        public String userUuid
        public Date expiredDate
        public Date createDate
        public boolean noSessionEvaluation
    }

    static class ZoneInventory {
        public String uuid
        public String name
        public String description
        public String state
        public String type
        public boolean isDefault
        public String createDate
        public String lastOpDate
    }

    static class ClusterInventory {
        private String name
        private String uuid
        private String description
        private String state
        private String hypervisorType
        private String createDate
        private String lastOpDate
        private String zoneUuid
        private String type
        private String architecture
    }

    static class KVMHostInventory {
        public String username;
        public String password;
        public Integer sshPort;
        public String osDistribution;
        public String osRelease;
        public String osVersion;
        public String zoneUuid;
        public String name;
        public String uuid;
        public String clusterUuid;
        public String description;
        public String managementIp;
        public String hypervisorType;
        public String state;
        public String status;
        public Long totalCpuCapacity;
        public Long availableCpuCapacity;
        public Integer cpuSockets;
        public Long totalMemoryCapacity;
        public Long availableMemoryCapacity;
        public Integer cpuNum;
        public String ipmiAddress;
        public String ipmiUsername;
        public Integer ipmiPort;
        public String ipmiPowerStatus;
        public String cpuStatus;
        public String memoryStatus;
        public String diskStatus;
        public String nicStatus;
        public String gpuStatus;
        public String powerSupplyStatus;
        public String fanStatus;
        public String raidStatus;
        public String temperatureStatus;
        public String architecture;
        public String createDate;
        public String lastOpDate;
    }

    static class InventoriesResult<T> {
        private Map<Object, Object> schema
        public List<T> inventories
    }

    static class InventoryResult<T> {
        public T inventory
    }
}
