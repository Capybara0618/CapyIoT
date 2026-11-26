package io.capyiot.service;

import java.net.URI;

final class ServiceUris {
    private ServiceUris(){}
    static URI https(URI uri,boolean originOnly) {
        if(uri==null||!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null
                ||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getPort()==0||uri.getPort()>65535
                ||originOnly&&uri.getRawPath()!=null&&!uri.getRawPath().isEmpty()&&!uri.getRawPath().equals("/"))
            throw new IllegalArgumentException("A fixed HTTPS service origin is required");
        return uri;
    }
}
