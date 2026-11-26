package io.capyiot.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Opaque actor token; authenticity and current authorization remain the receiving service's responsibility. */
public final class ActorCredential {
    private final String token;
    private ActorCredential(String token){this.token=token;}
    public static ActorCredential bearer(String authorization) {
        if(authorization==null||!authorization.startsWith("Bearer "))throw ServiceFailure.denied(401,"ACTOR_TOKEN_INVALID");
        String value=authorization.substring(7);
        if(value.length()>16384||!value.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
            throw ServiceFailure.denied(401,"ACTOR_TOKEN_INVALID");
        return new ActorCredential(value);
    }
    public String authorization(){return "Bearer "+token;}
    public String digest(){return digest(token.getBytes(StandardCharsets.US_ASCII));}
    static String digest(byte[] value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    @Override public String toString(){return "ActorCredential[REDACTED]";}
}
