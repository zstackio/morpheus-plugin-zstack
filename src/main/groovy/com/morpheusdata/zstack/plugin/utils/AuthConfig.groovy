package com.morpheusdata.zstack.plugin.utils

class AuthConfig {
    public String identityUrl
    public String username
    public String password
    public Long cloudId
    public Map cloudConfig
    public String domainId
    public String session
    public String userId
    public String expires
    public Boolean expireSession // whether the token should be expired

    @Override
    public String toString() {
        "cloudId: ${cloudId}, identityUrl: ${identityUrl}, domainId: ${domainId}, cloudConfig: ${cloudConfig}, userId: ${userId}, expires: ${expires}"
    }
}
