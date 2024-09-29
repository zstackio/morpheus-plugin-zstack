/*
* Copyright 2022 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.morpheusdata.zstack.plugin

import com.morpheusdata.core.Plugin
import groovy.util.logging.Slf4j
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import com.morpheusdata.zstack.plugin.utils.AuthConfig

@Slf4j
class ZstackPlugin extends Plugin {

    private String cloudProviderCode

    @Override
    String getCode() {
        return 'zstack-plugin'
    }

    @Override
    void initialize() {
        this.setName("ZStack")
        def zstackCloud = new ZstackCloudProvider(this, this.morpheus)
        this.registerProvider(zstackCloud)
        this.registerProvider(new ZstackProvisionProvider(this, this.morpheus))
        this.registerProvider(new ZstackOptionSourceProvider(this, this.morpheus))
        cloudProviderCode = zstackCloud.code
    }

    ZstackCloudProvider getCloudProvider() {
        return this.getProviderByCode(cloudProviderCode) as ZstackCloudProvider
    }


    /**
     * Called when a plugin is being removed from the plugin manager (aka Uninstalled)
     */
    @Override
    void onDestroy() {
        //nothing to do for now
    }

    AuthConfig getAuthConfig(Cloud cloud) {
        AuthConfig rtn = new AuthConfig([
                identityUrl: cloud.serviceUrl,
                domainId   : cloud.getConfigProperty('domainId'),
                username   : null,
                password   : null,
                cloudId    : cloud.id,
                cloudConfig: cloud.configMap,
        ])

        if (!cloud.accountCredentialLoaded) {
            AccountCredential accountCredential
            try {
                accountCredential = this.morpheus.cloud.loadCredentials(cloud.id).blockingGet()
            } catch (e) {
                // If there is no credential on the cloud, then this will error
                // TODO: Change to using 'maybe' rather than 'blockingGet'?
            }
            cloud.accountCredentialLoaded = true
            cloud.accountCredentialData = accountCredential?.data
        }

        if (cloud.accountCredentialData && cloud.accountCredentialData.containsKey('username')) {
            rtn.username = cloud.accountCredentialData['username']
        } else {
            rtn.username = cloud.serviceUsername
        }
        if (cloud.accountCredentialData && cloud.accountCredentialData.containsKey('password')) {
            rtn.password = cloud.accountCredentialData['password']
        } else {
            rtn.password = cloud.servicePassword
        }
        rtn
    }

}
